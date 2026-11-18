package com.acid.wechat.wechat

import android.content.ContentValues
import com.acid.wechat.core.AcidLogger
import com.acid.wechat.core.DexKitLocator
import com.acid.wechat.core.HostContext
import com.acid.wechat.core.MsgNoticeHelper
import com.acid.wechat.core.ReflectHelper
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.luckypray.dexkit.query.matchers.ParameterMatcher
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.CopyOnWriteArrayList

/**
 * WeChat-specific adapter bridging `VoiceMsgDownloadFinishEvent`, `ExtTranslateVoiceEvent`,
 * `VoiceLogic` (`l`/`r`), `MsgInfoStorage.Sd`, and `ChattingContext` UI refresh.
 */
object VoiceTransBridge {
    private const val TAG = "VoiceTransBridge"
    const val MSG_TYPE_VOICE = 34
    private const val FLAG_VOICE_TRANS_EXPANDED = 64
    private const val EXT_TRANSLATE_EVENT_CLASS = "com.tencent.mm.autogen.events.ExtTranslateVoiceEvent"
    private const val VOICE_DOWNLOAD_FINISH_EVENT_CLASS = "com.tencent.mm.autogen.events.VoiceMsgDownloadFinishEvent"

    private lateinit var ruleEventAsyncPublish: DexKitLocator.DexRule
    private lateinit var ruleVoiceLogicUpdateRecv: DexKitLocator.DexRule
    private lateinit var ruleMsgInfoStorageUpdateNotify: DexKitLocator.DexRule
    private lateinit var ruleVoiceIsShowTransform: DexKitLocator.DexRule
    private lateinit var ruleChattingContextNotify: DexKitLocator.DexRule
    private lateinit var ruleChattingContextScroll: DexKitLocator.DexRule
    private lateinit var ruleTransformComponentAct: DexKitLocator.DexRule

    private data class EventReflector(
        val eventCls: Class<*>,
        val publishMethod: Method,
        val reqField: Field,
        val respField: Field,
        val reqFileField: Field,
        val reqMsgIdField: Field,
        val reqOpCodeField: Field,
        val reqSaveDbField: Field,
        val reqCallbackField: Field,
        val reqSceneField: Field,
        val reqTalkerField: Field,
        val respTextField: Field,
        val respFinishedField: Field,
        val respStatusField: Field,
    )

    private data class DlFinishReflector(
        val eventCls: Class<*>,
        val dataField: Field,
        val msgIdField: Field,
        val successField: Field,
    )

    @Volatile private var voiceGetMsgByFileMethod: Method? = null
    @Volatile private var voiceClearRedDotMethod: Method? = null
    @Volatile private var msgStorageSdMethod: Method? = null
    @Volatile private var chattingContextNotifyMethod: Method? = null
    @Volatile private var msgGetMsgIdMethod: Method? = null
    @Volatile private var msgGetTypeMethod: Method? = null
    @Volatile private var msgConvertToMethod: Method? = null
    @Volatile private var msgConvertFromMethod: Method? = null
    @Volatile private var eventReflector: EventReflector? = null
    @Volatile private var dlFinishReflector: DlFinishReflector? = null
    @Volatile private var activeChattingContext: WeakReference<Any>? = null
    private val transcriptObservers = CopyOnWriteArrayList<(Long, String, String) -> Unit>()

    fun addTranscriptObserver(observer: (msgId: Long, talker: String, text: String) -> Unit) {
        transcriptObservers.add(observer)
    }

    fun registerDexRules() {
        ruleEventAsyncPublish = DexKitLocator.registerMethod(
            id = "ievent_async_publish_v1",
            description = "IEvent.asyncPublish(Looper) for VoiceMsgDownloadFinishEvent",
        ) {
            searchPackages("com.tencent.mm.sdk.event")
            matcher {
                usingEqStrings("EventPoolImpl.asyncPublish event", "EventPoolImpl.asyncPublish looper")
                params { count(1); add("android.os.Looper") }
            }
        }
        ruleVoiceLogicUpdateRecv = DexKitLocator.registerMethod(
            id = "voice_logic_update_recv_v1",
            description = "VoiceLogic class anchor (updateRecv)",
        ) { matcher { usingEqStrings("END!!! updateRecv  file:"); returnType = "int" } }

        ruleMsgInfoStorageUpdateNotify = DexKitLocator.registerMethod(
            id = "msg_info_storage_update_notify_v1",
            description = "MsgInfoStorage.updateAndNotify(long, MsgInfo, boolean)",
        ) {
            searchPackages("com.tencent.mm.storage")
            matcher {
                declaredClass { usingEqStrings("MsgInfo processAddMsg insert db error") }
                usingEqStrings("notifymessage", "schedule_message")
                returnType = "int"
                params { count(3); add("long"); add(ParameterMatcher()); add("boolean") }
            }
        }
        ruleVoiceIsShowTransform = DexKitLocator.registerMethod(
            id = "voice_is_show_transform_v1",
            description = "ChattingItemVoice.isShowTransformFlag(MsgInfo, ChattingContext)",
        ) {
            searchPackages("com.tencent.mm.ui.chatting.viewitems")
            matcher {
                usingEqStrings("MicroMsg.TransformComponent", "isShowTransformFlag[%s]:%s")
                returnType = "boolean"
                params { count(2); add(ParameterMatcher()); add(ParameterMatcher()) }
            }
        }
        ruleChattingContextNotify = DexKitLocator.registerMethod(
            id = "chatting_context_notify_v1",
            description = "ChattingContext.notifyDataSetChange()",
        ) { matcher { usingEqStrings("MicroMsg.ChattingContext", "[notifyDataSetChange]"); params { count(0) } } }

        ruleChattingContextScroll = DexKitLocator.registerMethod(
            id = "chatting_context_scroll_v1",
            description = "ChattingContext.scrollToLast() for context capture",
        ) { matcher { usingEqStrings("MicroMsg.ChattingContext", "[scrollToLast]0"); params { count(0) } } }

        ruleTransformComponentAct = DexKitLocator.registerMethod(
            id = "transform_component_act_v1",
            description = "TransformComponent.goVoiceTransTextAct",
        ) {
            searchPackages("com.tencent.mm.ui.chatting.component")
            matcher { usingEqStrings("MicroMsg.TransformComponent", "go VoiceTransTextAct unsetflag MsgId:%s,isVoiceTransforming:%s") }
        }
    }

    fun init(
        onVoiceDownloaded: (Long) -> Unit,
        shouldForceExpand: (Long) -> Boolean,
        onUserCollapsed: (Long) -> Unit,
    ) {
        resolveHelpers()
        hookDownloadFinishEvent(onVoiceDownloaded)
        hookChattingHooks(shouldForceExpand, onUserCollapsed)
    }

    private fun resolveHelpers() {
        val voiceLogicCls = ruleVoiceLogicUpdateRecv.resolveMethod()?.declaringClass
        val msgInfoCls = MsgNoticeHelper.resolveMsgInfoClass()
        if (voiceLogicCls != null && msgInfoCls != null) {
            voiceGetMsgByFileMethod = voiceLogicCls.declaredMethods.firstOrNull { m ->
                Modifier.isStatic(m.modifiers) && m.parameterTypes.size == 1 &&
                    m.parameterTypes[0] == String::class.java && m.returnType == msgInfoCls
            }?.apply { isAccessible = true }
            voiceClearRedDotMethod = voiceLogicCls.declaredMethods.firstOrNull { m ->
                Modifier.isStatic(m.modifiers) && m.parameterTypes.size == 1 &&
                    m.parameterTypes[0] == msgInfoCls && m.returnType == Void.TYPE
            }?.apply { isAccessible = true }
        }
        if (msgInfoCls != null) {
            msgGetMsgIdMethod = ReflectHelper.findMethod(msgInfoCls, "getMsgId", emptyList())
            msgGetTypeMethod = ReflectHelper.findMethod(msgInfoCls, "getType", emptyList())
            msgConvertToMethod = ReflectHelper.findMethod(msgInfoCls, "convertTo", emptyList())
            msgConvertFromMethod = ReflectHelper.findMethod(
                msgInfoCls, "convertFrom", listOf(ContentValues::class.java, Boolean::class.javaPrimitiveType!!),
            )
        }
        msgStorageSdMethod = ruleMsgInfoStorageUpdateNotify.resolveMethod()
        chattingContextNotifyMethod = ruleChattingContextNotify.resolveMethod()
        eventReflector = buildEventReflector()
        dlFinishReflector = buildDlFinishReflector()
        AcidLogger.i(TAG, "ready: getMsg=${voiceGetMsgByFileMethod?.name}, r=${voiceClearRedDotMethod?.name}, sd=${msgStorageSdMethod?.name}, notify=${chattingContextNotifyMethod?.name}")
    }

    private fun hookDownloadFinishEvent(onVoiceDownloaded: (Long) -> Unit) {
        val publishMethod = ruleEventAsyncPublish.resolveMethod() ?: return
        val dlRef = dlFinishReflector ?: return
        runCatching {
            XposedBridge.hookMethod(publishMethod, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val evt = param.thisObject ?: return
                    if (evt.javaClass !== dlRef.eventCls) return
                    val data = dlRef.dataField.get(evt) ?: return
                    if (dlRef.successField.getBoolean(data)) {
                        val msgId = dlRef.msgIdField.getLong(data)
                        if (msgId > 0L) onVoiceDownloaded(msgId)
                    }
                }
            })
        }.onFailure { AcidLogger.e(TAG, "hook VoiceMsgDownloadFinishEvent failed", it) }
    }

    private fun hookChattingHooks(shouldForceExpand: (Long) -> Boolean, onUserCollapsed: (Long) -> Unit) {
        ruleChattingContextScroll.resolveMethod()?.let { m ->
            runCatching {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.thisObject?.let { activeChattingContext = WeakReference(it) }
                    }
                })
            }
        }
        ruleTransformComponentAct.resolveMethod()?.let { m ->
            runCatching {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val msgObj = param.args?.firstOrNull() ?: return
                        val msgId = (msgGetMsgIdMethod?.invoke(msgObj) as? Long) ?: return
                        if (msgId > 0L) onUserCollapsed(msgId)
                    }
                })
            }
        }
        val showMethod = ruleVoiceIsShowTransform.resolveMethod() ?: return
        runCatching {
            XposedBridge.hookMethod(showMethod, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    param.args?.getOrNull(1)?.let { activeChattingContext = WeakReference(it) }
                    if ((param.result as? Boolean) == true) return
                    val msgObj = param.args?.firstOrNull() ?: return
                    if ((msgGetTypeMethod?.invoke(msgObj) as? Int) != MSG_TYPE_VOICE) return
                    val msgId = (msgGetMsgIdMethod?.invoke(msgObj) as? Long) ?: return
                    if (msgId > 0L && shouldForceExpand(msgId)) param.result = true
                }
            })
        }.onFailure { AcidLogger.e(TAG, "hook isShowTransformFlag failed", it) }
    }

    fun isEventReady(): Boolean = eventReflector != null

    fun hasActiveContext(): Boolean = activeChattingContext?.get() != null

    fun dispatchTranslateEvent(
        msgId: Long,
        talker: String,
        fileName: String,
        onResult: (finished: Boolean, text: String?, status: Int) -> Unit,
    ): Boolean {
        val ref = eventReflector ?: return false
        val event = ref.eventCls.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val req = ref.reqField.get(event)
        ref.reqFileField.set(req, fileName)
        ref.reqMsgIdField.set(req, msgId.toString())
        ref.reqOpCodeField.setInt(req, 0)
        ref.reqSaveDbField.setInt(req, 1)
        ref.reqSceneField.setInt(req, if (talker.endsWith("@chatroom")) 2 else 1)
        ref.reqTalkerField.set(req, talker)
        ref.reqCallbackField.set(req, Runnable {
            val resp = ref.respField.get(event)
            val finished = ref.respFinishedField.getBoolean(resp)
            val text = ref.respTextField.get(resp) as? String
            val status = ref.respStatusField.getInt(resp)
            if (finished && !text.isNullOrBlank()) {
                for (obs in transcriptObservers) {
                    runCatching { obs(msgId, talker, text) }
                }
            }
            onResult(finished, text, status)
        })
        return (ref.publishMethod.invoke(event) as? Boolean) ?: true
    }

    /** Applies expand (`flag |= 64`) and red-dot clear (`VoiceLogic.r`) in at most ONE `Sd` write. */
    fun applyVoiceStateAndRefresh(msgId: Long, fileName: String, expand: Boolean, clearRedDot: Boolean) {
        val msgObj = voiceGetMsgByFileMethod?.invoke(null, fileName)
        if (msgObj != null) {
            val cv = msgConvertToMethod?.invoke(msgObj) as? ContentValues
            val flagNeedsUpdate = expand && ((cv?.getAsInteger("flag") ?: 0) and FLAG_VOICE_TRANS_EXPANDED) == 0
            if (flagNeedsUpdate && cv != null) {
                cv.put("flag", (cv.getAsInteger("flag") ?: 0) or FLAG_VOICE_TRANS_EXPANDED)
                msgConvertFromMethod?.invoke(msgObj, cv, true)
            }
            val playedBefore = isVoiceContentPlayed(cv?.getAsString("content"))
            val clearedByVoiceLogic = clearRedDot && !playedBefore && voiceClearRedDotMethod != null
            if (clearedByVoiceLogic) {
                voiceClearRedDotMethod?.invoke(null, msgObj)
            } else if (flagNeedsUpdate) {
                val storage = MsgNoticeHelper.getCapturedStorage()
                if (storage != null) msgStorageSdMethod?.invoke(storage, msgId, msgObj, true)
            }
        }
        activeChattingContext?.get()?.let { ctx -> chattingContextNotifyMethod?.invoke(ctx) }
    }

    private fun isVoiceContentPlayed(content: String?): Boolean {
        if (content.isNullOrBlank()) return false
        val parts = content.trimEnd('\n', ':').split(':')
        return parts.lastOrNull() == "1"
    }

    private fun buildDlFinishReflector(): DlFinishReflector? = runCatching {
        val cls = Class.forName(VOICE_DOWNLOAD_FINISH_EVENT_CLASS, false, HostContext.classLoader)
        val dataField = ReflectHelper.findField(cls, "g") ?: error("g not found")
        val dataCls = dataField.type
        DlFinishReflector(cls, dataField, ReflectHelper.findField(dataCls, "a")!!, ReflectHelper.findField(dataCls, "b")!!)
    }.getOrNull()

    private fun buildEventReflector(): EventReflector? = runCatching {
        val eventCls = Class.forName(EXT_TRANSLATE_EVENT_CLASS, false, HostContext.classLoader)
        val publishMethod = ReflectHelper.findMethod(eventCls, "e", emptyList()) ?: error("IEvent.e() not found")
        val reqField = ReflectHelper.findField(eventCls, "g") ?: error("event.g not found")
        val respField = ReflectHelper.findField(eventCls, "h") ?: error("event.h not found")
        val reqCls = reqField.type
        val respCls = respField.type
        EventReflector(
            eventCls, publishMethod, reqField, respField,
            ReflectHelper.findField(reqCls, "a")!!, ReflectHelper.findField(reqCls, "b")!!,
            ReflectHelper.findField(reqCls, "c")!!, ReflectHelper.findField(reqCls, "d")!!,
            ReflectHelper.findField(reqCls, "e")!!, ReflectHelper.findField(reqCls, "f")!!,
            ReflectHelper.findField(reqCls, "g")!!, ReflectHelper.findField(respCls, "a")!!,
            ReflectHelper.findField(respCls, "b")!!, ReflectHelper.findField(respCls, "c")!!,
        )
    }.getOrNull()
}
