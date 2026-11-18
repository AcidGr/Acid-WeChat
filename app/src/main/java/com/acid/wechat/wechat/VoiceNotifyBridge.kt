package com.acid.wechat.wechat

import android.app.Notification
import android.app.NotificationManager
import android.os.Handler
import android.os.Looper
import com.acid.wechat.core.AcidLogger
import com.acid.wechat.core.DexKitLocator
import com.acid.wechat.core.HostContext
import com.acid.wechat.core.MsgNoticeHelper
import com.acid.wechat.core.ReflectHelper
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.luckypray.dexkit.query.matchers.ParameterMatcher
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Native WeChat notification bridge for voice transcription:
 * 1) Defers incoming `type == 34` `m0.a` notifications up to [WAIT_TIMEOUT_MS] so the first
 *    system/lockscreen/watch popup directly displays the transcribed text when ready in time.
 * 2) If timed out, posts the native `[语音]` notification cleanly and silently updates the same
 *    notification ID (`FLAG_ONLY_ALERT_ONCE`) once transcription completes, if still active.
 */
object VoiceNotifyBridge {
    private const val TAG = "VoiceNotifyBridge"
    private const val WAIT_TIMEOUT_MS = 1800L
    private const val MAX_TRANSCRIPT_LEN = 120
    private val VOICE_PLACEHOLDERS = listOf("[语音]", "[Voice]", "[語音]")

    private lateinit var ruleAppMsgNotify: DexKitLocator.DexRule
    private lateinit var ruleCreateMmNotification: DexKitLocator.DexRule
    private lateinit var ruleDispatchNotificationItem: DexKitLocator.DexRule

    private data class PendingWaiter(
        val msgId: Long,
        val talker: String,
        val m0Obj: Any,
        val args: Array<Any?>,
        val timeoutToken: Runnable,
    )

    private data class TimedOutPosted(
        val msgId: Long,
        val talker: String,
        val notifId: Int,
        val e0Obj: Any,
        val wmA: Any,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingByMsgId = ConcurrentHashMap<Long, PendingWaiter>()
    private val pendingMsgIdByTalker = ConcurrentHashMap<String, Long>()
    private val pendingReplayTexts = ConcurrentHashMap<Long, String>()
    private val replayingMsgIds = Collections.newSetFromMap(ConcurrentHashMap<Long, Boolean>())
    private val timedOutByMsgId = ConcurrentHashMap<Long, TimedOutPosted>()
    private val timedOutMsgIdByTalker = ConcurrentHashMap<String, Long>()

    private val tlsTranscript = ThreadLocal<String?>()
    private val tlsTimeoutMsgId = ThreadLocal<Long?>()
    private val tlsCapturedWmA = ThreadLocal<Any?>()

    @Volatile private var appMsgNotifyMethod: Method? = null
    @Volatile private var createMmNotifMethod: Method? = null
    @Volatile private var msgGetMsgIdMethod: Method? = null
    @Volatile private var wmContentField: Field? = null
    @Volatile private var wmTickerField: Field? = null
    @Volatile private var wmBaseNotifField: Field? = null
    @Volatile private var wmNeedSoundField: Field? = null
    @Volatile private var wmForceSilentField: Field? = null
    @Volatile private var itemNotifIdField: Field? = null
    @Volatile private var itemTalkerField: Field? = null

    fun registerDexRules() {
        ruleAppMsgNotify = DexKitLocator.registerMethod(
            id = "notif_app_msg_handle_v1",
            description = "WeChat AppMsg notification dispatch m0.a(...)",
        ) {
            searchPackages("com.tencent.mm.booter.notification")
            matcher {
                usingEqStrings("in sample Notify: needSound: %B, needShake: %B, msgContent: ==, msgType: %d, talker: %s, customNotify: %s, isRevokeMessage:%b")
                params { count(10) }
            }
        }
        ruleCreateMmNotification = DexKitLocator.registerMethod(
            id = "notif_create_mm_v1",
            description = "WeChat e0.b(wm.a) native Notification builder",
        ) {
            searchPackages("com.tencent.mm.booter.notification")
            matcher {
                usingEqStrings("MicroMsg.Notification.AppMsg.Handle", "createMMNotification: needSound=%B, forceSilent=%B")
                params { count(1); add(ParameterMatcher()) }
            }
        }
        ruleDispatchNotificationItem = DexKitLocator.registerMethod(
            id = "notif_dispatch_item_v1",
            description = "WeChat e0.a(NotificationItem, ym.z)",
        ) {
            searchPackages("com.tencent.mm.booter.notification")
            matcher {
                usingEqStrings("MicroMsg.Notification.AppMsg.Handle", "GreenManUtil.isAppVisibleForeground")
                returnType = "int"
                params { count(2); add(ParameterMatcher()); add(ParameterMatcher()) }
            }
        }
    }

    fun init(isFeatureEnabled: () -> Boolean) {
        val m0Method = ruleAppMsgNotify.resolveMethod() ?: return AcidLogger.w(TAG, "m0.a not resolved")
        val createMethod = ruleCreateMmNotification.resolveMethod() ?: return AcidLogger.w(TAG, "e0.b not resolved")
        val dispatchMethod = ruleDispatchNotificationItem.resolveMethod() ?: return AcidLogger.w(TAG, "e0.a not resolved")

        appMsgNotifyMethod = m0Method
        createMmNotifMethod = createMethod
        msgGetMsgIdMethod = MsgNoticeHelper.resolveMsgInfoClass()?.let { ReflectHelper.findMethod(it, "getMsgId", emptyList()) }

        val wmCls = createMethod.parameterTypes[0]
        wmContentField = ReflectHelper.findField(wmCls, "f")
        wmTickerField = ReflectHelper.findField(wmCls, "g")
        wmBaseNotifField = ReflectHelper.findField(wmCls, "a")
        wmNeedSoundField = ReflectHelper.findField(wmCls, "p")
        wmForceSilentField = ReflectHelper.findField(wmCls, "q")

        val itemCls = dispatchMethod.parameterTypes[0]
        itemNotifIdField = ReflectHelper.findField(itemCls, "d")
        itemTalkerField = ReflectHelper.findField(itemCls, "h")

        hookNotifyEntry(m0Method, isFeatureEnabled)
        hookCreateNotification(createMethod)
        hookDispatchItem(dispatchMethod)
        AcidLogger.i(TAG, "initialized native notification hooks: ${m0Method.declaringClass.name}.${m0Method.name}")
    }

    private fun hookNotifyEntry(m0Method: Method, isFeatureEnabled: () -> Boolean) {
        XposedBridge.hookMethod(m0Method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val args = param.args ?: return
                val msgType = args.getOrNull(5) as? Int ?: return
                val talker = args.getOrNull(6) as? String ?: return
                val isRevoke = args.getOrNull(9) as? Boolean ?: false
                val msgObj = args.getOrNull(0)
                val msgSvrId = args.getOrNull(1) as? Long ?: 0L
                val rawMsgId = (msgObj?.let { msgGetMsgIdMethod?.invoke(it) } as? Long) ?: 0L
                val msgId = if (rawMsgId > 0L) rawMsgId else {
                    if (msgSvrId > 0L) ChatRepository.queryMessageBySvrId(msgSvrId)?.getAsLong("msgId") ?: 0L else 0L
                }

                if (msgId > 0L && replayingMsgIds.remove(msgId)) {
                    val readyText = pendingReplayTexts.remove(msgId)
                    if (!readyText.isNullOrBlank()) {
                        tlsTranscript.set(readyText)
                    } else {
                        tlsTimeoutMsgId.set(msgId)
                    }
                    return
                }

                if (!isFeatureEnabled() || msgType != VoiceTransBridge.MSG_TYPE_VOICE || isRevoke || msgId <= 0L) {
                    supersedePendingForTalker(talker, args)
                    return
                }

                val row = ChatRepository.queryVoiceMessageByMsgId(msgId) ?: return
                if (row.isSend != 0) return

                val fileName = row.imgPath.ifBlank { ChatRepository.queryVoiceFileName(row.msgId, row.msgSvrId).orEmpty() }
                val cached = if (fileName.isNotBlank()) ChatRepository.queryVoiceTranscript(msgId, fileName) else null
                if (!cached.isNullOrBlank()) {
                    clearTimedOutForTalker(talker)
                    tlsTranscript.set(cached)
                    return
                }

                // Defer notification briefly so the first native popup displays the transcript directly.
                supersedePendingForTalker(talker, args)
                val argsCopy = args.clone()
                val m0Obj = param.thisObject ?: return
                val timeoutRunnable = Runnable { onWaitTimeout(msgId, talker) }
                pendingByMsgId[msgId] = PendingWaiter(msgId, talker, m0Obj, argsCopy, timeoutRunnable)
                pendingMsgIdByTalker[talker] = msgId
                mainHandler.postDelayed(timeoutRunnable, WAIT_TIMEOUT_MS)
                param.result = null
                AcidLogger.i(TAG, "deferred voice notification msgId=$msgId talker=$talker (${WAIT_TIMEOUT_MS}ms)")
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                tlsTranscript.remove()
                tlsTimeoutMsgId.remove()
                tlsCapturedWmA.remove()
            }
        })
    }

    private fun hookCreateNotification(createMethod: Method) {
        XposedBridge.hookMethod(createMethod, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val wmA = param.args?.firstOrNull() ?: return
                if (tlsTimeoutMsgId.get() != null) {
                    tlsCapturedWmA.set(wmA)
                }
                val text = tlsTranscript.get() ?: return
                applyTranscriptToWmA(wmA, text)
            }
        })
    }

    private fun hookDispatchItem(dispatchMethod: Method) {
        XposedBridge.hookMethod(dispatchMethod, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val timeoutMsgId = tlsTimeoutMsgId.get() ?: return
                if ((param.result as? Int ?: -1) == -1) return
                val item = param.args?.firstOrNull() ?: return
                val wmA = tlsCapturedWmA.get() ?: return
                val e0Obj = param.thisObject ?: return
                val notifId = (itemNotifIdField?.get(item) as? Int) ?: return
                val talker = (itemTalkerField?.get(item) as? String) ?: return
                if (notifId <= 0 || talker.isBlank()) return

                clearTimedOutForTalker(talker)
                timedOutByMsgId[timeoutMsgId] = TimedOutPosted(timeoutMsgId, talker, notifId, e0Obj, wmA)
                timedOutMsgIdByTalker[talker] = timeoutMsgId
            }
        })
    }

    fun onTranscriptReady(msgId: Long, text: String) {
        if (text.isBlank()) return
        val waiter = pendingByMsgId.remove(msgId)
        if (waiter != null) {
            pendingMsgIdByTalker.remove(waiter.talker, msgId)
            mainHandler.removeCallbacks(waiter.timeoutToken)
            pendingReplayTexts[msgId] = text
            mainHandler.post { replayWaiter(waiter, "with transcript") }
            return
        }

        val posted = timedOutByMsgId.remove(msgId) ?: return
        timedOutMsgIdByTalker.remove(posted.talker, msgId)
        mainHandler.post { silentlyUpdateActiveNotification(posted, text) }
    }

    fun onTranscriptFailed(msgId: Long) {
        timedOutByMsgId.remove(msgId)?.let { timedOutMsgIdByTalker.remove(it.talker, msgId) }
        val waiter = pendingByMsgId[msgId] ?: return
        mainHandler.post { onWaitTimeout(msgId, waiter.talker) }
    }

    private fun onWaitTimeout(msgId: Long, talker: String) {
        val waiter = pendingByMsgId.remove(msgId) ?: return
        pendingMsgIdByTalker.remove(talker, msgId)
        replayWaiter(waiter, "timeout fallback")
    }

    private fun replayWaiter(waiter: PendingWaiter, reason: String) {
        replayingMsgIds.add(waiter.msgId)
        runCatching {
            appMsgNotifyMethod?.invoke(waiter.m0Obj, *waiter.args)
            AcidLogger.i(TAG, "replayed voice notification ($reason) msgId=${waiter.msgId}")
        }.onFailure {
            replayingMsgIds.remove(waiter.msgId)
            pendingReplayTexts.remove(waiter.msgId)
            AcidLogger.w(TAG, "replay failed ($reason) msgId=${waiter.msgId}", it)
        }
    }

    private fun silentlyUpdateActiveNotification(posted: TimedOutPosted, text: String) {
        runCatching {
            val nm = HostContext.app.getSystemService(NotificationManager::class.java) ?: return
            val stillActive = nm.activeNotifications.any { it.id == posted.notifId }
            if (!stillActive) return

            applyTranscriptToWmA(posted.wmA, text)
            wmBaseNotifField?.set(posted.wmA, Notification())
            wmNeedSoundField?.setBoolean(posted.wmA, false)
            wmForceSilentField?.setBoolean(posted.wmA, false)

            val updated = createMmNotifMethod?.invoke(posted.e0Obj, posted.wmA) as? Notification ?: return
            updated.flags = updated.flags or Notification.FLAG_ONLY_ALERT_ONCE
            nm.notify(posted.notifId, updated)
            AcidLogger.i(TAG, "silently updated timed-out notification msgId=${posted.msgId} id=${posted.notifId}")
        }.onFailure { AcidLogger.w(TAG, "silent update failed msgId=${posted.msgId}", it) }
    }

    private fun applyTranscriptToWmA(wmA: Any, rawText: String) {
        val clean = rawText.trim().replace('\n', ' ').take(MAX_TRANSCRIPT_LEN)
        if (clean.isEmpty()) return
        val content = wmContentField?.get(wmA) as? String
        val ticker = wmTickerField?.get(wmA) as? String
        replaceVoiceTag(content, clean)?.let { wmContentField?.set(wmA, it) }
        replaceVoiceTag(ticker, clean)?.let { wmTickerField?.set(wmA, it) }
    }

    private fun replaceVoiceTag(src: String?, transcript: String): String? {
        if (src.isNullOrEmpty()) return null
        for (tag in VOICE_PLACEHOLDERS) {
            val idx = src.indexOf(tag, ignoreCase = true)
            if (idx >= 0) {
                return src.substring(0, idx) + "$tag $transcript" + src.substring(idx + tag.length)
            }
        }
        return null
    }

    private fun supersedePendingForTalker(talker: String, incomingArgs: Array<Any?>) {
        clearTimedOutForTalker(talker)
        val oldMsgId = pendingMsgIdByTalker.remove(talker) ?: return
        val oldWaiter = pendingByMsgId.remove(oldMsgId) ?: return
        mainHandler.removeCallbacks(oldWaiter.timeoutToken)
        // Preserve alert flags so a follow-up message doesn't lose sound/shake.
        val oldSound = oldWaiter.args.getOrNull(2) as? Boolean ?: false
        val oldShake = oldWaiter.args.getOrNull(3) as? Boolean ?: false
        if (oldSound) incomingArgs[2] = true
        if (oldShake) incomingArgs[3] = true
    }

    private fun clearTimedOutForTalker(talker: String) {
        timedOutMsgIdByTalker.remove(talker)?.let { timedOutByMsgId.remove(it) }
    }
}
