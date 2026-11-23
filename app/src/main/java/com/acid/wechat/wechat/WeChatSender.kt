package com.acid.wechat.wechat

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.acid.wechat.core.AcidIo
import com.acid.wechat.core.AcidLogger
import com.acid.wechat.core.DexKitLocator
import com.acid.wechat.core.HostContext
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.json.JSONObject
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.matchers.ParameterMatcher
import java.io.File
import java.lang.reflect.Constructor
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Sends real WeChat text, image, and file messages via DexKit-resolved WeChat internals
 * (`NetSceneSendMsg` / `NetSceneQueue`, `SendMsgMgr.sendImg`, and `AppMsgLogic.sendAppMsg`).
 */
object WeChatSender {
    private const val TAG = "WeChatSender"
    private const val MSG_TYPE_TEXT = 1
    private const val MODELBASE_PREFIX = "com.tencent.mm.modelbase."
    private const val WX_FILE_OBJECT_CLASS = "com.tencent.mm.opensdk.modelmsg.WXFileObject"
    private const val WX_MEDIA_MESSAGE_CLASS = "com.tencent.mm.opensdk.modelmsg.WXMediaMessage"

    private lateinit var ruleQueueEnqueue: DexKitLocator.DexRule
    private lateinit var ruleQueueClass: DexKitLocator.DexRule
    private lateinit var ruleSendMsgCgi: DexKitLocator.DexRule
    private lateinit var ruleSendMsgTag: DexKitLocator.DexRule
    private lateinit var ruleServiceManagerGet: DexKitLocator.DexRule
    private lateinit var ruleSendImgMethod: DexKitLocator.DexRule
    private lateinit var ruleSendAppMsgMethod: DexKitLocator.DexRule

    @Volatile private var capturedQueue: Any? = null
    @Volatile private var capturedEnqueueMethod: Method? = null
    @Volatile private var capturedSceneClass: Class<*>? = null
    @Volatile private var lastError: String = ""
    private val sentCount = AtomicInteger(0)

    data class SendResult(val ok: Boolean, val detail: String)

    fun registerDexRules() {
        ruleQueueEnqueue = DexKitLocator.registerMethod(
            id = "net_scene_queue_enqueue_v3",
            description = "WeChat NetSceneQueue.doScene(NetSceneBase, int)",
        ) {
            searchPackages("com.tencent.mm.modelbase")
            matcher {
                declaredClass {
                    usingStrings("MicroMsg.NetSceneQueue")
                }
                returnType = "boolean"
                params {
                    count(2)
                    add(ParameterMatcher())
                    add("int")
                }
            }
        }
        ruleQueueClass = DexKitLocator.registerClass(
            id = "net_scene_queue_v3",
            description = "WeChat NetSceneQueue class (modelbase)",
        ) {
            searchPackages("com.tencent.mm.modelbase")
            matcher {
                usingStrings("MicroMsg.NetSceneQueue")
                addMethod {
                    returnType = "boolean"
                    params {
                        count(2)
                        add(ParameterMatcher())
                        add("int")
                    }
                }
            }
        }
        ruleSendMsgCgi = DexKitLocator.registerClass(
            id = "net_scene_send_msg_cgi_v4",
            description = "WeChat NetSceneSendMsg (modelbase subclass) via /cgi-bin/micromsg-bin/newsendmsg",
        ) {
            excludePackages("com.tencent.mm.plugin.voip", "com.tencent.mm.console")
            matcher {
                superClass(MODELBASE_PREFIX, StringMatchType.StartsWith, false)
                usingEqStrings("/cgi-bin/micromsg-bin/newsendmsg")
            }
        }
        ruleSendMsgTag = DexKitLocator.registerClass(
            id = "net_scene_send_msg_v4",
            description = "WeChat NetSceneSendMsg (modelbase subclass) via MicroMsg.NetSceneSendMsg",
        ) {
            excludePackages("com.tencent.mm.plugin.voip", "com.tencent.mm.console")
            matcher {
                superClass(MODELBASE_PREFIX, StringMatchType.StartsWith, false)
                usingStrings("MicroMsg.NetSceneSendMsg")
            }
        }
        ruleServiceManagerGet = DexKitLocator.registerMethod(
            id = "service_manager_get_service_v1",
            description = "WeChat ServiceManager.getService(Class)",
        ) {
            matcher {
                declaredClass {
                    usingStrings("MicroMsg.ServiceManager", "Already initialized.")
                }
                usingStrings("calling getService(...)")
                params {
                    count(1)
                    add("java.lang.Class")
                }
            }
        }
        ruleSendImgMethod = DexKitLocator.registerMethod(
            id = "send_msg_mgr_send_img_v1",
            description = "WeChat SendMsgMgr.sendImg(Context, toUser, fileName, compressType, ...)",
        ) {
            matcher {
                usingStrings("MicroMsg.SendMsgMgr", "msg_mgr_send_img", "sendImg: args error, toUser[%s], fileName[%s]")
                returnType = "void"
            }
        }
        ruleSendAppMsgMethod = DexKitLocator.registerMethod(
            id = "app_msg_logic_send_v1",
            description = "WeChat AppMsgLogic.sendAppMsg(WXMediaMessage, appId, appName, toUser, scene, md5)",
        ) {
            searchPackages("com.tencent.mm.pluginsdk.model.app")
            matcher {
                declaredClass {
                    usingStrings("MicroMsg.AppMsgLogic")
                }
                returnType = "int"
                params {
                    count(6)
                    add(WX_MEDIA_MESSAGE_CLASS)
                    add("java.lang.String")
                    add("java.lang.String")
                    add("java.lang.String")
                    add("int")
                    add("java.lang.String")
                }
            }
        }
        WeChatVoiceSender.registerDexRules()
    }

    fun init() {
        WeChatVoiceSender.init()
        val enqueueMethod = if (::ruleQueueEnqueue.isInitialized) ruleQueueEnqueue.resolveMethod() else null
        val queueCls = enqueueMethod?.declaringClass
            ?: if (::ruleQueueClass.isInitialized) ruleQueueClass.resolveClass() else null

        if (queueCls == null) {
            lastError = "NetSceneQueue not resolved"
            AcidLogger.w(TAG, lastError)
            return
        }
        capturedEnqueueMethod = enqueueMethod ?: findFallbackEnqueueMethod(queueCls)
        hookQueueForCapture(queueCls, capturedEnqueueMethod)
        AcidLogger.i(
            TAG,
            "initialized WeChatSender (queue=${queueCls.name}, enqueue=${capturedEnqueueMethod?.name}, " +
                "cgi=${resolveCgiClass()?.name}, tag=${resolveTagClass()?.name})",
        )
    }

    fun sendText(talker: String, text: String): SendResult {
        if (talker.isBlank() || text.isEmpty()) {
            return recordFailure("talker and text must be non-empty")
        }
        val enqueueMethod = capturedEnqueueMethod
            ?: (if (::ruleQueueEnqueue.isInitialized) ruleQueueEnqueue.resolveMethod() else null)
            ?: return recordFailure("NetSceneQueue enqueue method not resolved (check /dexkit)")
        val queue = capturedQueue
            ?: return recordFailure("NetSceneQueue instance not yet captured; trigger any WeChat action first")

        val candidates = listOfNotNull(
            resolveCgiClass(),
            resolveTagClass(),
            capturedSceneClass,
        ).distinct()
        if (candidates.isEmpty()) {
            return recordFailure("NetSceneSendMsg class not resolved (check /dexkit)")
        }

        return runOnMainLooper {
            runCatching {
                var scene: Any? = null
                var usedCls: Class<*>? = null
                for (cls in candidates) {
                    val created = instantiateSendMsgScene(cls, talker, text)
                    if (created != null) {
                        scene = created
                        usedCls = cls
                        break
                    }
                }
                if (scene == null || usedCls == null) {
                    val sigs = candidates.joinToString(" | ") { cls ->
                        "${cls.name}(super=${cls.superclass?.simpleName}): " +
                            cls.declaredConstructors.joinToString { c ->
                                "(${c.parameterTypes.joinToString(",") { it.simpleName }})"
                            }
                    }
                    return@runCatching recordFailure("No compatible constructor in [$sigs]")
                }

                val dispatched = invokeEnqueue(enqueueMethod, queue, scene)
                if (dispatched) {
                    sentCount.incrementAndGet()
                    lastError = ""
                    AcidLogger.i(TAG, "dispatched text message to $talker via ${usedCls.name}")
                    SendResult(true, "dispatched via ${usedCls.name} -> ${enqueueMethod.declaringClass.simpleName}.${enqueueMethod.name}")
                } else {
                    recordFailure("${enqueueMethod.declaringClass.simpleName}.${enqueueMethod.name} returned false")
                }
            }.onFailure {
                AcidLogger.w(TAG, "sendText failed for $talker", it)
            }.getOrElse { err ->
                recordFailure("${err.javaClass.simpleName}: ${err.message}")
            }
        }
    }

    fun sendImage(talker: String, imageFile: File, rawImage: Boolean = false): SendResult {
        if (talker.isBlank()) return recordFailure("talker must be non-empty")
        if (!imageFile.exists() || !imageFile.isFile || imageFile.length() <= 0L) {
            return recordFailure("image file does not exist or is empty: ${imageFile.absolutePath}")
        }
        val sendImgMethod = (if (::ruleSendImgMethod.isInitialized) ruleSendImgMethod.resolveMethod() else null)
            ?: return recordFailure("SendMsgMgr.sendImg method not resolved (check /dexkit)")
        val p = sendImgMethod.parameterTypes
        if (p.size < 4 || !Context::class.java.isAssignableFrom(p[0]) ||
            p[1] != String::class.java || p[2] != String::class.java || !isIntType(p[3])
        ) {
            return recordFailure("Unexpected SendMsgMgr.sendImg signature: ${p.joinToString { it.simpleName }}")
        }

        val args = arrayOfNulls<Any?>(p.size)
        args[0] = HostContext.app
        args[1] = talker
        args[2] = imageFile.absolutePath
        args[3] = if (rawImage) 1 else 0
        for (i in 4 until p.size) {
            args[i] = when {
                p[i] == String::class.java -> ""
                !p[i].isPrimitive -> null
                p[i] == Int::class.javaPrimitiveType -> 0
                p[i] == Long::class.javaPrimitiveType -> 0L
                p[i] == Boolean::class.javaPrimitiveType -> false
                p[i] == Float::class.javaPrimitiveType -> 0f
                p[i] == Double::class.javaPrimitiveType -> 0.0
                else -> null
            }
        }

        return runOnMainLooper {
            runCatching {
                val receiver = resolveServiceInstance(sendImgMethod.declaringClass)
                    ?: return@runCatching recordFailure("SendMsgMgr service instance unavailable (${sendImgMethod.declaringClass.name})")
                sendImgMethod.isAccessible = true
                sendImgMethod.invoke(receiver, *args)
                sentCount.incrementAndGet()
                lastError = ""
                AcidLogger.i(TAG, "dispatched image to $talker (${imageFile.name}, raw=$rawImage) via ${sendImgMethod.declaringClass.name}.${sendImgMethod.name}")
                SendResult(true, "image dispatched via ${sendImgMethod.declaringClass.simpleName}.${sendImgMethod.name}")
            }.onFailure { err ->
                val root = (err as? InvocationTargetException)?.targetException ?: err
                AcidLogger.w(TAG, "sendImage failed for $talker", root)
            }.getOrElse { err ->
                val root = (err as? InvocationTargetException)?.targetException ?: err
                recordFailure("${root.javaClass.simpleName}: ${root.message}")
            }
        }
    }

    fun sendFile(talker: String, file: File, customTitle: String? = null): SendResult {
        if (talker.isBlank()) return recordFailure("talker must be non-empty")
        if (!file.exists() || !file.isFile || file.length() <= 0L) {
            return recordFailure("file does not exist or is empty: ${file.absolutePath}")
        }
        val sendAppMsgMethod = (if (::ruleSendAppMsgMethod.isInitialized) ruleSendAppMsgMethod.resolveMethod() else null)
            ?: return recordFailure("AppMsgLogic.sendAppMsg method not resolved (check /dexkit)")

        return runOnMainLooper {
            runCatching {
                val cl = HostContext.classLoader
                val wxFileCls = Class.forName(WX_FILE_OBJECT_CLASS, false, cl)
                val wxMsgCls = Class.forName(WX_MEDIA_MESSAGE_CLASS, false, cl)

                val wxFile = wxFileCls.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
                val setFilePath = runCatching { wxFileCls.getMethod("setFilePath", String::class.java) }.getOrNull()
                if (setFilePath != null) {
                    setFilePath.isAccessible = true
                    setFilePath.invoke(wxFile, file.absolutePath)
                } else {
                    wxFileCls.getField("filePath").apply { isAccessible = true }.set(wxFile, file.absolutePath)
                }

                val wxMsg = wxMsgCls.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
                wxMsgCls.getField("mediaObject").apply { isAccessible = true }.set(wxMsg, wxFile)
                wxMsgCls.getField("title").apply { isAccessible = true }.set(
                    wxMsg,
                    customTitle?.trim()?.takeIf { it.isNotEmpty() } ?: file.name,
                )
                wxMsgCls.getField("description").apply { isAccessible = true }.set(
                    wxMsg,
                    AcidIo.formatBytes(file.length()),
                )

                sendAppMsgMethod.isAccessible = true
                val ret = sendAppMsgMethod.invoke(null, wxMsg, "", "", talker, 3, null)
                if (ret is Int && ret < 0) {
                    return@runCatching recordFailure("AppMsgLogic.sendAppMsg returned $ret")
                }
                sentCount.incrementAndGet()
                lastError = ""
                AcidLogger.i(TAG, "dispatched file '${file.name}' to $talker via ${sendAppMsgMethod.declaringClass.name}.${sendAppMsgMethod.name} (ret=$ret)")
                SendResult(true, "file dispatched via ${sendAppMsgMethod.declaringClass.simpleName}.${sendAppMsgMethod.name} (ret=$ret)")
            }.onFailure { err ->
                val root = (err as? InvocationTargetException)?.targetException ?: err
                AcidLogger.w(TAG, "sendFile failed for $talker", root)
            }.getOrElse { err ->
                val root = (err as? InvocationTargetException)?.targetException ?: err
                recordFailure("${root.javaClass.simpleName}: ${root.message}")
            }
        }
    }

    fun sendVoice(talker: String, audioFile: File, durationMsOverride: Int? = null): SendResult =
        WeChatVoiceSender.sendVoice(talker, audioFile, durationMsOverride)

    internal fun dispatchNetScene(scene: Any): SendResult {
        val enqueueMethod = capturedEnqueueMethod
            ?: (if (::ruleQueueEnqueue.isInitialized) ruleQueueEnqueue.resolveMethod() else null)
            ?: return recordFailure("NetSceneQueue enqueue method not resolved (check /dexkit)")
        val queue = capturedQueue
            ?: return recordFailure("NetSceneQueue instance not yet captured; trigger any WeChat action first")

        return runOnMainLooper {
            runCatching {
                val dispatched = invokeEnqueue(enqueueMethod, queue, scene)
                if (dispatched) {
                    sentCount.incrementAndGet()
                    lastError = ""
                    SendResult(true, "dispatched via ${enqueueMethod.declaringClass.simpleName}.${enqueueMethod.name}")
                } else {
                    recordFailure("${enqueueMethod.declaringClass.simpleName}.${enqueueMethod.name} returned false")
                }
            }.onFailure {
                AcidLogger.w(TAG, "dispatchNetScene failed", it)
            }.getOrElse { err ->
                recordFailure("${err.javaClass.simpleName}: ${err.message}")
            }
        }
    }

    internal fun runOnMainLooper(block: () -> SendResult): SendResult {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return block()
        }
        var result = SendResult(false, "Dispatch timed out")
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            try { result = block() } finally { latch.countDown() }
        }
        if (!latch.await(5, TimeUnit.SECONDS)) {
            return recordFailure("Timed out waiting for main looper dispatch")
        }
        return result
    }

    internal fun resolveServiceInstance(serviceImplCls: Class<*>): Any? {
        val getServiceMethod = if (::ruleServiceManagerGet.isInitialized) ruleServiceManagerGet.resolveMethod() else null
        if (getServiceMethod != null) {
            getServiceMethod.isAccessible = true
            val candidates = linkedSetOf<Class<*>>(serviceImplCls).apply {
                addAll(serviceImplCls.interfaces)
            }
            for (c in candidates) {
                val svc = runCatching { getServiceMethod.invoke(null, c) }.getOrNull()
                if (svc != null && serviceImplCls.isInstance(svc)) {
                    return svc
                }
            }
        }
        return runCatching {
            serviceImplCls.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        }.getOrNull()
    }

    fun statusJson(): JSONObject = JSONObject().apply {
        put("queueClass", (capturedEnqueueMethod?.declaringClass ?: if (::ruleQueueClass.isInitialized) ruleQueueClass.resolveClass() else null)?.name ?: "")
        put("enqueueMethod", capturedEnqueueMethod?.name ?: "")
        put("queueCaptured", capturedQueue != null)
        put("sendMsgCgiClass", resolveCgiClass()?.name ?: "")
        put("sendMsgTagClass", resolveTagClass()?.name ?: "")
        put("capturedSceneClass", capturedSceneClass?.name ?: "")
        put("serviceManagerReady", ::ruleServiceManagerGet.isInitialized && ruleServiceManagerGet.resolveMethod() != null)
        put("sendImgReady", ::ruleSendImgMethod.isInitialized && ruleSendImgMethod.resolveMethod() != null)
        put("sendAppMsgReady", ::ruleSendAppMsgMethod.isInitialized && ruleSendAppMsgMethod.resolveMethod() != null)
        put("voice", WeChatVoiceSender.statusJson())
        put("sentCount", sentCount.get())
        put("lastError", lastError)
    }

    private fun resolveCgiClass(): Class<*>? =
        if (::ruleSendMsgCgi.isInitialized) ruleSendMsgCgi.resolveClass() else null

    private fun resolveTagClass(): Class<*>? =
        if (::ruleSendMsgTag.isInitialized) ruleSendMsgTag.resolveClass() else null

    private fun recordFailure(msg: String): SendResult {
        lastError = msg
        return SendResult(false, msg)
    }

    private fun hookQueueForCapture(queueCls: Class<*>, enqueueMethod: Method?) {
        val captureHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (capturedQueue == null && param.thisObject != null) {
                    capturedQueue = param.thisObject
                }
                if (capturedSceneClass == null && param.args != null && param.args.isNotEmpty()) {
                    val scene = param.args[0]
                    if (scene != null && looksLikeTextSendScene(scene)) {
                        capturedSceneClass = scene.javaClass
                        AcidLogger.i(TAG, "captured runtime send scene class: ${scene.javaClass.name}")
                    }
                }
            }
        }
        for (ctor in queueCls.declaredConstructors) {
            runCatching { XposedBridge.hookMethod(ctor, captureHook) }
        }
        if (enqueueMethod != null) {
            runCatching { XposedBridge.hookMethod(enqueueMethod, captureHook) }
        }
    }

    private fun looksLikeTextSendScene(scene: Any): Boolean = runCatching {
        val cls = scene.javaClass
        val hasSendCtor = cls.declaredConstructors.any { c ->
            val p = c.parameterTypes
            p.size in 2..6 && p[0] == String::class.java && p[1] == String::class.java &&
                (p.size == 2 || isIntType(p[2]))
        }
        if (!hasSendCtor) return@runCatching false
        cls.declaredMethods.any { m ->
            m.parameterTypes.isEmpty() && m.returnType == String::class.java && runCatching {
                m.isAccessible = true
                m.invoke(scene) as? String
            }.getOrNull() == "/cgi-bin/micromsg-bin/newsendmsg"
        } || cls.name.contains("SendMsg", ignoreCase = true)
    }.getOrDefault(false)

    private fun findFallbackEnqueueMethod(queueCls: Class<*>): Method? =
        queueCls.declaredMethods.firstOrNull { m ->
            val p = m.parameterTypes
            m.returnType == Boolean::class.javaPrimitiveType &&
                p.size == 2 &&
                !p[0].isPrimitive &&
                p[0] != Any::class.java &&
                p[1] == Int::class.javaPrimitiveType
        }

    private fun invokeEnqueue(method: Method, queue: Any, scene: Any): Boolean {
        method.isAccessible = true
        val res = if (method.parameterTypes.size == 2) {
            method.invoke(queue, scene, 0)
        } else {
            method.invoke(queue, scene)
        }
        return if (method.returnType == Boolean::class.javaPrimitiveType) res == true else true
    }

    private fun instantiateSendMsgScene(sendMsgCls: Class<*>, talker: String, text: String): Any? {
        val ctors = sendMsgCls.declaredConstructors.sortedBy { it.parameterTypes.size }
        for (ctor in ctors) {
            val args = buildArgsForCtor(ctor, talker, text) ?: continue
            val instance = runCatching {
                ctor.isAccessible = true
                ctor.newInstance(*args)
            }.getOrNull()
            if (instance != null) return instance
        }
        return null
    }

    private fun buildArgsForCtor(ctor: Constructor<*>, talker: String, text: String): Array<Any?>? {
        val p = ctor.parameterTypes
        if (p.size < 2 || p[0] != String::class.java || p[1] != String::class.java) return null
        val out = arrayOfNulls<Any?>(p.size)
        out[0] = talker
        out[1] = text
        if (p.size >= 3) {
            if (!isIntType(p[2])) return null
            out[2] = MSG_TYPE_TEXT
        }
        for (i in 3 until p.size) {
            val arg = when {
                !p[i].isPrimitive -> null
                p[i] == Int::class.javaPrimitiveType -> 0
                p[i] == Long::class.javaPrimitiveType -> 0L
                p[i] == Boolean::class.javaPrimitiveType -> false
                p[i] == Float::class.javaPrimitiveType -> 0f
                p[i] == Double::class.javaPrimitiveType -> 0.0
                else -> return null
            }
            out[i] = arg
        }
        return out
    }

    private fun isIntType(cls: Class<*>): Boolean =
        cls == Int::class.javaPrimitiveType || cls == Int::class.javaObjectType
}
