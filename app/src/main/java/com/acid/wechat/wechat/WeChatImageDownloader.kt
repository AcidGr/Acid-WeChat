package com.acid.wechat.wechat

import android.os.Handler
import android.os.Looper
import com.acid.wechat.core.AcidLogger
import com.acid.wechat.core.DexKitLocator
import com.acid.wechat.core.HostContext
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Triggers WeChat's native `ModelImage.DownloadImgService` on demand to upgrade a thumbnail
 * (`th_...`) to WeChat's standard mid-resolution image (`compressType = 0`, base `ImgInfo2` row).
 * Strictly forbids downloading original HD files (`reserved1` / `compressType = 1`).
 */
object WeChatImageDownloader {
    private const val TAG = "WeChatImgDownloader"
    private const val MSG_ID_TALKER_CLASS = "com.tencent.mm.plugin.msg.MsgIdTalker"
    private const val COMPRESS_TYPE_MID = 0

    private lateinit var ruleDownloadImgMethod: DexKitLocator.DexRule
    private lateinit var ruleImageFeatureService: DexKitLocator.DexRule

    @Volatile private var capturedDownloadService: Any? = null

    data class UpgradeResult(
        val ok: Boolean,
        val file: File? = null,
        val upgraded: Boolean = false,
        val detail: String = "",
        val record: ChatImageRepository.ImageRecord? = null,
    )

    fun registerDexRules() {
        ruleDownloadImgMethod = DexKitLocator.registerMethod(
            id = "img_download_service_method_v1",
            description = "WeChat DownloadImgService.addTask(imgLocalId, MsgIdTalker, compressType, ...)",
        ) {
            matcher {
                usingEqStrings("ModelImage.DownloadImgService", "] add failed, task already done")
            }
        }
        ruleImageFeatureService = DexKitLocator.registerClass(
            id = "img_feature_service_v1",
            description = "WeChat ImageFeatureService holding ImgInfoStorage & DownloadImgService",
        ) {
            matcher {
                addField {
                    type {
                        usingEqStrings("MicroMsg.ImgInfoStorage", "generateMd5: %s, %s")
                    }
                }
                addField {
                    type {
                        addMethod {
                            usingEqStrings("ModelImage.DownloadImgService", "] add failed, task already done")
                        }
                    }
                }
            }
        }
    }

    fun init() {
        val method = if (::ruleDownloadImgMethod.isInitialized) ruleDownloadImgMethod.resolveMethod() else null
        if (method == null) {
            AcidLogger.w(TAG, "DownloadImgService method not resolved; on-demand mid-res upgrade disabled")
            return
        }
        runCatching {
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (capturedDownloadService == null && param.thisObject != null) {
                        capturedDownloadService = param.thisObject
                    }
                }
            })
        }.onFailure {
            AcidLogger.w(TAG, "hook DownloadImgService failed", it)
        }
        AcidLogger.i(TAG, "WeChatImageDownloader ready: ${method.declaringClass.name}.${method.name}")
    }

    fun upgradeToMidImage(rawPathOrHash: String, timeoutMs: Long = 12_000L): UpgradeResult {
        val hash = ChatImageRepository.extractImageHash(rawPathOrHash)
            ?: return UpgradeResult(false, detail = "Invalid WeChat image hash")

        ChatImageRepository.resolveBestFileForHash(hash, preferNonThumbOnly = true)?.let { existing ->
            return UpgradeResult(true, file = existing, upgraded = false, detail = "already_cached")
        }

        val record = ChatImageRepository.queryBaseImageRecordByHash(hash)
            ?: return UpgradeResult(false, detail = "Base ImgInfo2 record not found for $hash")

        ChatImageRepository.resolveRecordNonThumbFile(record)?.let { existing ->
            return UpgradeResult(true, file = existing, upgraded = false, detail = "already_cached", record = record)
        }

        val method = (if (::ruleDownloadImgMethod.isInitialized) ruleDownloadImgMethod.resolveMethod() else null)
            ?: return UpgradeResult(false, detail = "DownloadImgService method unavailable")
        val service = resolveDownloadService(method.declaringClass)
            ?: return UpgradeResult(false, detail = "DownloadImgService instance unavailable")

        val callbackLatch = CountDownLatch(1)
        val dispatched = dispatchNativeMidDownload(method, service, record, callbackLatch)
        if (!dispatched.first) {
            return UpgradeResult(false, detail = dispatched.second)
        }

        val landed = waitForMidFile(record.localId, hash, callbackLatch, timeoutMs)
            ?: return UpgradeResult(false, detail = "Timed out waiting for mid-resolution image download")

        AcidLogger.i(TAG, "upgraded thumbnail -> mid image for ${record.talker}: ${landed.name} (${landed.length()} B)")
        return UpgradeResult(true, file = landed, upgraded = true, detail = "downloaded_mid", record = record)
    }

    private fun dispatchNativeMidDownload(
        method: Method,
        service: Any,
        record: ChatImageRepository.ImageRecord,
        callbackLatch: CountDownLatch,
    ): Pair<Boolean, String> {
        val p = method.parameterTypes
        if (p.size < 6) {
            return false to "Unexpected DownloadImgService signature (${p.size} params)"
        }
        val args = arrayOfNulls<Any?>(p.size)
        args[0] = record.localId
        args[1] = buildSecondArg(p[1], record) ?: return false to "Unsupported param[1]: ${p[1].name}"
        // Hardcode compressType = 0 so only standard compressed mid-res image is requested, never original HD.
        args[2] = COMPRESS_TYPE_MID
        args[3] = null
        args[4] = 0
        args[5] = buildCallbackProxy(p[5], callbackLatch)
        for (i in 6 until p.size) {
            args[i] = when (p[i]) {
                Int::class.javaPrimitiveType, Int::class.javaObjectType -> -1
                Boolean::class.javaPrimitiveType, Boolean::class.javaObjectType -> false
                Long::class.javaPrimitiveType, Long::class.javaObjectType -> 0L
                else -> null
            }
        }

        return runOnMainLooper {
            runCatching {
                method.isAccessible = true
                val res = method.invoke(service, *args)
                val code = (res as? Number)?.toInt()
                if (code != null && code < 0) {
                    false to "DownloadImgService returned $code"
                } else {
                    true to "ok(ret=$res)"
                }
            }.getOrElse { err ->
                AcidLogger.w(TAG, "invoke DownloadImgService failed", err)
                false to "invoke failed: ${err.message}"
            }
        }
    }

    private fun buildSecondArg(paramType: Class<*>, record: ChatImageRepository.ImageRecord): Any? {
        if (paramType == Long::class.javaPrimitiveType || paramType == Long::class.javaObjectType) {
            return record.msgSvrId.takeIf { it > 0L } ?: record.msgLocalId
        }
        if (paramType.name == MSG_ID_TALKER_CLASS || paramType.simpleName == "MsgIdTalker") {
            val id = record.msgLocalId.takeIf { it > 0L } ?: record.localId
            return runCatching {
                val ctor = paramType.getDeclaredConstructor(Long::class.javaPrimitiveType, String::class.java)
                ctor.isAccessible = true
                ctor.newInstance(id, record.talker)
            }.getOrNull()
        }
        return null
    }

    private fun buildCallbackProxy(iface: Class<*>, latch: CountDownLatch): Any? {
        if (!iface.isInterface) return null
        return Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { proxy, m, args ->
            when (m.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                "toString" -> "AcidImgDlCb"
                else -> {
                    latch.countDown()
                    defaultReturnValue(m.returnType)
                }
            }
        }
    }

    private fun defaultReturnValue(rt: Class<*>): Any? = when (rt) {
        Boolean::class.javaPrimitiveType -> false
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        else -> null
    }

    private fun waitForMidFile(
        localId: Long,
        hash: String,
        callbackLatch: CountDownLatch,
        timeoutMs: Long,
    ): File? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastSize = -1L
        var candidate: File? = null
        while (System.currentTimeMillis() < deadline) {
            val found = ChatImageRepository.resolveBestFileForHash(hash, preferNonThumbOnly = true)
                ?: ChatImageRepository.queryImageRecordById(localId)?.let {
                    ChatImageRepository.resolveRecordNonThumbFile(it)
                }
            if (found != null && found.isFile && found.length() > 32L) {
                val sz = found.length()
                if (candidate?.absolutePath == found.absolutePath && sz == lastSize) {
                    return found
                }
                candidate = found
                lastSize = sz
            }
            val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(1L)
            runCatching { callbackLatch.await(minOf(250L, remaining), TimeUnit.MILLISECONDS) }
        }
        return candidate?.takeIf { it.isFile && it.length() > 32L }
    }

    private fun resolveDownloadService(downloadCls: Class<*>): Any? {
        capturedDownloadService?.let { return it }

        val featCls = if (::ruleImageFeatureService.isInitialized) ruleImageFeatureService.resolveClass() else null
        if (featCls != null) {
            val featSvc = WeChatSender.resolveServiceInstance(featCls)
            if (featSvc != null) {
                for (f in featCls.declaredFields) {
                    if (downloadCls.isAssignableFrom(f.type)) {
                        runCatching {
                            f.isAccessible = true
                            f.get(featSvc)
                        }.getOrNull()?.let {
                            capturedDownloadService = it
                            return it
                        }
                    }
                }
                for (m in featCls.declaredMethods) {
                    if (m.parameterTypes.isEmpty() && downloadCls.isAssignableFrom(m.returnType)) {
                        val receiver = if (Modifier.isStatic(m.modifiers)) null else featSvc
                        runCatching {
                            m.isAccessible = true
                            m.invoke(receiver)
                        }.getOrNull()?.let {
                            capturedDownloadService = it
                            return it
                        }
                    }
                }
            }
        }

        for (m in downloadCls.declaredMethods) {
            if (Modifier.isStatic(m.modifiers) && m.parameterTypes.isEmpty() && downloadCls.isAssignableFrom(m.returnType)) {
                runCatching {
                    m.isAccessible = true
                    m.invoke(null)
                }.getOrNull()?.let {
                    capturedDownloadService = it
                    return it
                }
            }
        }

        return runCatching {
            downloadCls.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        }.getOrNull()
    }

    private fun runOnMainLooper(block: () -> Pair<Boolean, String>): Pair<Boolean, String> {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var res: Pair<Boolean, String> = false to "Main looper dispatch timed out"
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            try { res = block() } finally { latch.countDown() }
        }
        latch.await(5, TimeUnit.SECONDS)
        return res
    }
}
