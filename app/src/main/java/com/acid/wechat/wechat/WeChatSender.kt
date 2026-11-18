package com.acid.wechat.wechat

import android.os.Handler
import android.os.Looper
import com.acid.wechat.core.AcidLogger
import com.acid.wechat.core.DexKitLocator
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.json.JSONObject
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.matchers.ParameterMatcher
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Sends real WeChat text messages via DexKit-resolved `NetSceneSendMsg` (subclass of `modelbase.n1`)
 * and `NetSceneQueue.doScene(NetSceneBase, int)`.
 */
object WeChatSender {
    private const val TAG = "WeChatSender"
    private const val MSG_TYPE_TEXT = 1
    private const val MODELBASE_PREFIX = "com.tencent.mm.modelbase."

    private lateinit var ruleQueueEnqueue: DexKitLocator.DexRule
    private lateinit var ruleQueueClass: DexKitLocator.DexRule
    private lateinit var ruleSendMsgCgi: DexKitLocator.DexRule
    private lateinit var ruleSendMsgTag: DexKitLocator.DexRule

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
    }

    fun init() {
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

        var result = SendResult(false, "Dispatch timed out")
        val exec = {
            result = runCatching {
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

        if (Looper.myLooper() == Looper.getMainLooper()) {
            exec()
        } else {
            val latch = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post {
                try { exec() } finally { latch.countDown() }
            }
            if (!latch.await(5, TimeUnit.SECONDS)) {
                return recordFailure("Timed out waiting for main looper dispatch")
            }
        }
        return result
    }

    fun statusJson(): JSONObject = JSONObject().apply {
        put("queueClass", (capturedEnqueueMethod?.declaringClass ?: if (::ruleQueueClass.isInitialized) ruleQueueClass.resolveClass() else null)?.name ?: "")
        put("enqueueMethod", capturedEnqueueMethod?.name ?: "")
        put("queueCaptured", capturedQueue != null)
        put("sendMsgCgiClass", resolveCgiClass()?.name ?: "")
        put("sendMsgTagClass", resolveTagClass()?.name ?: "")
        put("capturedSceneClass", capturedSceneClass?.name ?: "")
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
