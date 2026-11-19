package com.acid.wechat.features

import android.content.ContentValues
import com.acid.wechat.agent.AcidAgentLoop
import com.acid.wechat.agent.AgentConfigStore
import com.acid.wechat.agent.AgentSentTracker
import com.acid.wechat.agent.AgentSessionModels
import com.acid.wechat.agent.AgentSessionStore
import com.acid.wechat.core.AcidFeature
import com.acid.wechat.core.AcidLogger
import com.acid.wechat.core.ConfigSchema
import com.acid.wechat.core.ConfigStore
import com.acid.wechat.core.FeatureOption
import com.acid.wechat.core.FeatureRegistry
import com.acid.wechat.core.MsgNoticeHelper
import com.acid.wechat.core.WcdbBridge
import com.acid.wechat.ui.ChatHeaderAiHook
import com.acid.wechat.wechat.ChatImageRepository
import com.acid.wechat.wechat.ChatQueryRepository
import com.acid.wechat.wechat.ChatRepository
import com.acid.wechat.wechat.VoiceTransBridge
import com.acid.wechat.wechat.WeChatMessageFormatter
import org.json.JSONObject
import java.lang.reflect.Field
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * Per-chat on-device Agent integration:
 * 1) Adds the top-right "AI" entry in ChattingUI via [ChatHeaderAiHook].
 * 2) Syncs live WeChat messages into active per-chat sessions.
 * 3) Auto-triggers on-device replies based on configurable group `@me` and wake-word settings.
 */
object ChatAgentFeature : AcidFeature {
    private const val TAG = "ChatAgent"
    private const val LIVE_MSG_WINDOW_MS = 90_000L
    private const val MAX_TRIGGER_IDS = 400

    override val id = "chat_agent"
    override val spec = ConfigSchema.CHAT_AGENT_ENABLED

    override fun extraOptions(): List<FeatureOption> = listOf(
        FeatureOption.from(ConfigSchema.CHAT_AGENT_PASSIVE_AT),
        FeatureOption.from(ConfigSchema.GLOBAL_AGENT_ENTRY),
    )

    private val atUserListRegex = Regex("<atuserlist>(?:<!\\[CDATA\\[)?(.*?)(?:\\]\\]>)?</atuserlist>", RegexOption.IGNORE_CASE)
    private val ignoredTalkers = setOf("notifymessage", "weixin", "fmessage", "medianote", "floatbottle")

    private val triggeredMsgIds = Collections.synchronizedSet(object : LinkedHashSet<Long>() {
        override fun add(element: Long): Boolean {
            if (size >= MAX_TRIGGER_IDS) iterator().let { if (it.hasNext()) { it.next(); it.remove() } }
            return super.add(element)
        }
    })
    private val triggerCount = AtomicInteger(0)
    @Volatile private var msgSourceFields: List<Field>? = null

    override fun registerDexRules() {
        ChatHeaderAiHook.registerDexRules()
    }

    override fun onInit() {
        ChatHeaderAiHook.init { FeatureRegistry.isEnabled(this@ChatAgentFeature) }
        MsgNoticeHelper.addInsertObserver(::onMessageInserted)
        VoiceTransBridge.addTranscriptObserver { msgId, talker, text ->
            if (AgentSessionStore.updateVoiceTranscript(talker, msgId, text)) {
                AcidAgentLoop.notifyExternalTimelineUpdate(talker)
            }
        }
        WcdbBridge.addInsertListener { table, values, _ ->
            when (table) {
                "VoiceTransText" -> handleVoiceTransInsert(values)
                "ImgInfo2" -> handleImgInfoInsert(values)
            }
        }
    }

    private fun handleVoiceTransInsert(values: ContentValues) {
        runCatching {
            val msgId = values.getAsLong("msgId")
                ?: values.getAsString("msgId")?.toLongOrNull()
                ?: return
            val text = values.getAsString("content")?.trim().orEmpty()
            if (msgId <= 0L || text.isEmpty()) return
            val row = ChatRepository.queryVoiceMessageByMsgId(msgId) ?: return
            if (AgentSessionStore.updateVoiceTranscript(row.talker, msgId, text)) {
                AcidAgentLoop.notifyExternalTimelineUpdate(row.talker)
            }
        }
    }

    private fun handleImgInfoInsert(values: ContentValues) {
        runCatching {
            val msgId = values.getAsLong("msglocalid") ?: 0L
            val msgSvrId = values.getAsLong("msgSvrId") ?: 0L
            if (msgId <= 0L && msgSvrId <= 0L) return
            val row = (if (msgId > 0L) ChatRepository.queryMessageById(msgId) else null)
                ?: (if (msgSvrId > 0L) ChatRepository.queryMessageBySvrId(msgSvrId) else null)
                ?: return
            val talker = row.getAsString("talker")?.trim().orEmpty()
            if (talker.isEmpty() || !AgentSessionStore.hasExistingSession(talker)) return
            val resolvedMsgId = row.getAsLong("msgId") ?: msgId
            val resolvedSvrId = row.getAsLong("msgSvrId") ?: msgSvrId
            val imgPath = row.getAsString("imgPath").orEmpty()
            val path = ChatImageRepository.resolveMessageImagePath(resolvedMsgId, resolvedSvrId, imgPath) ?: return
            if (AgentSessionStore.updateImagePath(talker, resolvedMsgId, resolvedSvrId, path)) {
                AcidAgentLoop.notifyExternalTimelineUpdate(talker)
            }
        }
    }

    private fun onMessageInserted(msgObj: Any, insertedMsgId: Long, values: ContentValues) {
        runCatching {
            val type = values.getAsInteger("type") ?: return
            if (type == MsgNoticeHelper.TYPE_SYSTEM) return

            val talker = values.getAsString("talker")?.trim().orEmpty()
            if (talker.isEmpty() || talker.startsWith("gh_") || talker in ignoredTalkers) return

            val isSend = (values.getAsInteger("isSend") ?: 0) != 0
            val msgSvrId = values.getAsLong("msgSvrId") ?: 0L
            val createTimeMs = values.getAsLong("createTime")?.takeIf { it > 0L } ?: System.currentTimeMillis()
            val rawContent = values.getAsString("content").orEmpty()
            val imgPath = values.getAsString("imgPath").orEmpty()
            val body = ChatQueryRepository.formatMessageBody(talker, isSend, type, rawContent, insertedMsgId, imgPath, msgSvrId)
            if (body.isBlank()) return

            if (isSend && AgentSentTracker.consumeIfSentByAgent(talker, body)) {
                return
            }
            val sentByAgentMedia = isSend && AgentSentTracker.consumeMediaSentByAgent(talker, type, body)
            val senderName = when {
                sentByAgentMedia -> "AI"
                isSend -> "master"
                else -> ChatRepository.resolveSenderDisplayName(talker, rawContent, null)
            }

            if (AgentSessionStore.hasExistingSession(talker)) {
                AgentSessionStore.ingestLiveMessageIfSessionActive(
                    talker = talker,
                    msgId = insertedMsgId,
                    msgSvrId = msgSvrId,
                    isSend = isSend,
                    senderName = senderName,
                    body = body,
                    timestampMs = createTimeMs,
                    sentByAgent = sentByAgentMedia,
                )
                AcidAgentLoop.notifyExternalTimelineUpdate(talker)
            }
            if (sentByAgentMedia) return

            if (!FeatureRegistry.isEnabled(this)) return
            if (!WeChatMessageFormatter.isTextOrAppMsgType(type)) return
            if (abs(System.currentTimeMillis() - createTimeMs) > LIVE_MSG_WINDOW_MS) return

            val triggerText = WeChatMessageFormatter.extractTriggerText(rawContent, body)
            val agentCfg = AgentConfigStore.getConfig()
            val wakeWordMatched = agentCfg.wakeWordEnabled &&
                AgentSessionModels.containsWakeWord(triggerText, agentCfg.wakeWord)
            val groupAtMatched = talker.endsWith("@chatroom") &&
                agentCfg.wakeOnGroupAt &&
                isMentioningSelf(msgObj, values)
            val shouldTrigger = if (isSend) {
                wakeWordMatched
            } else {
                ConfigStore.isEffective(ConfigSchema.CHAT_AGENT_PASSIVE_AT) &&
                    (wakeWordMatched || groupAtMatched)
            }
            if (!shouldTrigger) return

            synchronized(triggeredMsgIds) {
                if (!triggeredMsgIds.add(insertedMsgId)) return
            }

            // Ensure session is bootstrapped (including latest 15 DB messages) before firing the loop.
            AgentSessionStore.getOrLoad(talker)
            AgentSessionStore.ingestLiveMessageIfSessionActive(
                talker = talker,
                msgId = insertedMsgId,
                msgSvrId = msgSvrId,
                isSend = isSend,
                senderName = senderName,
                body = body,
                timestampMs = createTimeMs,
            )

            triggerCount.incrementAndGet()
            FeatureRegistry.recordHit(id)
            AcidLogger.i(
                TAG,
                "triggered chat agent in $talker (isSend=$isSend, msgId=$insertedMsgId, wakeWord=$wakeWordMatched, groupAt=$groupAtMatched)",
            )
            AcidAgentLoop.submitChatTrigger(talker = talker, isMaster = isSend)
        }.onFailure {
            FeatureRegistry.recordError(id, it)
            AcidLogger.w(TAG, "onMessageInserted failed", it)
        }
    }

    private fun isMentioningSelf(msgObj: Any, values: ContentValues): Boolean {
        val selfWxid = ChatRepository.querySelfWxid() ?: return false
        val lvBytes = runCatching { values.getAsByteArray("lvbuffer") }.getOrNull()
        if (lvBytes != null && lvBytes.isNotEmpty()) {
            val lvStr = String(lvBytes, Charsets.UTF_8)
            if (matchesAtUserList(lvStr, selfWxid)) return true
        }
        val fields = resolveStringFields(msgObj.javaClass)
        for (f in fields) {
            val v = runCatching { f.get(msgObj) as? String }.getOrNull() ?: continue
            if (v.contains("<atuserlist", ignoreCase = true) && matchesAtUserList(v, selfWxid)) {
                return true
            }
        }
        return false
    }

    private fun matchesAtUserList(xml: String, selfWxid: String): Boolean {
        val rawList = atUserListRegex.find(xml)?.groupValues?.getOrNull(1) ?: return false
        return rawList.split(',').any { it.trim() == selfWxid }
    }

    private fun resolveStringFields(cls: Class<*>): List<Field> {
        msgSourceFields?.let { return it }
        val collected = mutableListOf<Field>()
        var cur: Class<*>? = cls
        while (cur != null && cur != Any::class.java) {
            for (f in cur.declaredFields) {
                if (f.type == String::class.java) {
                    f.isAccessible = true
                    collected += f
                }
            }
            cur = cur.superclass
        }
        msgSourceFields = collected
        return collected
    }

    override fun statusJson(): JSONObject = super.statusJson().apply {
        val cfg = AgentConfigStore.getConfig()
        put("triggerCount", triggerCount.get())
        put("wakeOnGroupAt", cfg.wakeOnGroupAt)
        put("wakeWordEnabled", cfg.wakeWordEnabled)
        put("wakeWord", cfg.wakeWord)
    }
}
