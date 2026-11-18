package com.acid.wechat.features

import android.os.Handler
import android.os.Looper
import com.acid.wechat.core.AcidFeature
import com.acid.wechat.core.AcidLogger
import com.acid.wechat.core.ConfigSchema
import com.acid.wechat.core.ConfigStore
import com.acid.wechat.core.FeatureOption
import com.acid.wechat.core.FeatureRegistry
import com.acid.wechat.wechat.ChatRepository
import com.acid.wechat.wechat.VoiceTransBridge
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Automatically transcribes incoming voice messages (`isSend == 0`) via [VoiceTransBridge]
 * when audio download completes, optionally expanding the text bubble and clearing the unplayed red dot.
 */
object AutoVoiceTransFeature : AcidFeature {
    private const val TAG = "AutoVoiceTrans"
    private const val MAX_TRACKED_IDS = 500
    private const val TASK_TIMEOUT_SEC = 25L

    override val id = "auto_voice_trans"
    override val spec = ConfigSchema.AUTO_VOICE_TRANS

    override fun extraOptions(): List<FeatureOption> = listOf(
        FeatureOption.from(ConfigSchema.AUTO_VOICE_TRANS_EXPAND),
        FeatureOption.from(ConfigSchema.AUTO_VOICE_CLEAR_REDDOT),
    )

    private val worker = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "AcidVoiceTrans").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    private data class VoiceTask(val msgId: Long, val talker: String, val fileName: String, val retries: Int = 0)

    private val pendingQueue = ArrayDeque<VoiceTask>()
    private val trackedMsgIds = Collections.synchronizedSet(object : LinkedHashSet<Long>() {
        override fun add(element: Long): Boolean {
            if (size >= MAX_TRACKED_IDS) iterator().let { if (it.hasNext()) { it.next(); it.remove() } }
            return super.add(element)
        }
    })
    private val expandedMsgIds = Collections.newSetFromMap(ConcurrentHashMap<Long, Boolean>())
    private val userCollapsedMsgIds = Collections.newSetFromMap(ConcurrentHashMap<Long, Boolean>())
    private val cachedTranscriptCheck = ConcurrentHashMap<Long, Boolean>()

    private var inFlightTask: VoiceTask? = null
    private var inFlightTimeout: ScheduledFuture<*>? = null

    private val transcribedCount = AtomicInteger(0)
    private val failedCount = AtomicInteger(0)

    override fun registerDexRules() {
        VoiceTransBridge.registerDexRules()
    }

    override fun onInit() {
        VoiceTransBridge.init(
            onVoiceDownloaded = { msgId ->
                if (FeatureRegistry.isEnabled(this)) {
                    worker.execute { handleDownloadFinished(msgId) }
                }
            },
            shouldForceExpand = ::shouldForceExpandUi,
            onUserCollapsed = { msgId ->
                if (expandedMsgIds.remove(msgId)) {
                    userCollapsedMsgIds.add(msgId)
                }
            },
        )
    }

    private fun shouldForceExpandUi(msgId: Long): Boolean {
        if (!FeatureRegistry.isEnabled(this) || !ConfigStore.isEffective(ConfigSchema.AUTO_VOICE_TRANS_EXPAND)) {
            return false
        }
        if (userCollapsedMsgIds.contains(msgId)) return false
        return expandedMsgIds.contains(msgId) || checkIncomingVoiceHasTranscriptCached(msgId)
    }

    private fun checkIncomingVoiceHasTranscriptCached(msgId: Long): Boolean {
        cachedTranscriptCheck[msgId]?.let { return it }
        val row = ChatRepository.queryVoiceMessageByMsgId(msgId) ?: return false
        if (row.isSend != 0) {
            cachedTranscriptCheck[msgId] = false
            return false
        }
        val fileName = row.imgPath.ifBlank { ChatRepository.queryVoiceFileName(row.msgId, row.msgSvrId).orEmpty() }
        val ok = fileName.isNotBlank() && ChatRepository.hasVoiceTranscript(msgId, fileName)
        if (ok) cachedTranscriptCheck[msgId] = true
        return ok
    }

    private fun handleDownloadFinished(msgId: Long) {
        if (!FeatureRegistry.isEnabled(this)) return
        val row = ChatRepository.queryVoiceMessageByMsgId(msgId) ?: return
        val fileName = row.imgPath.ifBlank { ChatRepository.queryVoiceFileName(row.msgId, row.msgSvrId).orEmpty() }
        if (row.isSend != 0 || row.type != VoiceTransBridge.MSG_TYPE_VOICE || row.talker.isBlank() || fileName.isBlank()) {
            return
        }
        if (!trackedMsgIds.add(msgId)) return

        if (ChatRepository.hasVoiceTranscript(msgId, fileName)) {
            transcribedCount.incrementAndGet()
            FeatureRegistry.recordHit(id)
            applyPostTransActions(msgId, fileName)
            return
        }
        pendingQueue.addLast(VoiceTask(msgId, row.talker, fileName))
        drainQueue()
    }

    private fun drainQueue() {
        if (inFlightTask != null || !FeatureRegistry.isEnabled(this)) return
        val next = pendingQueue.removeFirstOrNull() ?: return
        if (!VoiceTransBridge.isEventReady()) return

        inFlightTask = next
        inFlightTimeout = worker.schedule({
            if (inFlightTask?.msgId == next.msgId) {
                AcidLogger.w(TAG, "voice trans timeout msgId=${next.msgId}")
                failedCount.incrementAndGet()
                finishInFlight(next.msgId)
            }
        }, TASK_TIMEOUT_SEC, TimeUnit.SECONDS)

        mainHandler.post { dispatch(next) }
    }

    private fun dispatch(task: VoiceTask) {
        runCatching {
            val published = VoiceTransBridge.dispatchTranslateEvent(task.msgId, task.talker, task.fileName) { finished, text, status ->
                if (!finished) return@dispatchTranslateEvent
                worker.execute { finishInFlight(task.msgId) }
                if (!text.isNullOrBlank()) {
                    transcribedCount.incrementAndGet()
                    FeatureRegistry.recordHit(id)
                    cachedTranscriptCheck[task.msgId] = true
                    AcidLogger.i(TAG, "transcribed msgId=${task.msgId} talker=${task.talker} len=${text.length}")
                    applyPostTransActions(task.msgId, task.fileName)
                } else {
                    failedCount.incrementAndGet()
                    AcidLogger.w(TAG, "empty transcript msgId=${task.msgId} status=$status")
                }
            }
            AcidLogger.i(TAG, "dispatched ExtTranslateVoiceEvent msgId=${task.msgId} published=$published")
            if (!published) worker.execute { retryOrReleaseBusy(task) }
        }.onFailure {
            AcidLogger.w(TAG, "dispatch failed msgId=${task.msgId}", it)
            failedCount.incrementAndGet()
            worker.execute { finishInFlight(task.msgId) }
        }
    }

    private fun retryOrReleaseBusy(task: VoiceTask) {
        if (inFlightTask?.msgId != task.msgId) return
        inFlightTimeout?.cancel(false)
        inFlightTimeout = null
        inFlightTask = null
        if (task.retries < 2) {
            worker.schedule({ pendingQueue.addFirst(task.copy(retries = task.retries + 1)); drainQueue() }, 1500L, TimeUnit.MILLISECONDS)
        } else {
            failedCount.incrementAndGet()
            drainQueue()
        }
    }

    private fun finishInFlight(msgId: Long) {
        if (inFlightTask?.msgId == msgId) {
            inFlightTimeout?.cancel(false)
            inFlightTimeout = null
            inFlightTask = null
        }
        drainQueue()
    }

    private fun applyPostTransActions(msgId: Long, fileName: String) {
        val expand = ConfigStore.isEffective(ConfigSchema.AUTO_VOICE_TRANS_EXPAND)
        val clearRedDot = ConfigStore.isEffective(ConfigSchema.AUTO_VOICE_CLEAR_REDDOT)
        if (!expand && !clearRedDot) return

        if (expand) {
            expandedMsgIds.add(msgId)
            userCollapsedMsgIds.remove(msgId)
            cachedTranscriptCheck[msgId] = true
        }
        mainHandler.post {
            runCatching {
                VoiceTransBridge.applyVoiceStateAndRefresh(msgId, fileName, expand, clearRedDot)
            }.onFailure {
                AcidLogger.w(TAG, "applyPostTransActions failed msgId=$msgId", it)
            }
        }
    }

    override fun statusJson(): JSONObject = JSONObject().apply {
        put("transcribedCount", transcribedCount.get())
        put("failedCount", failedCount.get())
        put("queueSize", pendingQueue.size)
        put("inFlightMsgId", inFlightTask?.msgId ?: 0L)
        put("expandedCount", expandedMsgIds.size)
        put("hasChattingCtx", VoiceTransBridge.hasActiveContext())
    }
}
