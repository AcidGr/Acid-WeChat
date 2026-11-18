package com.acid.wechat.features

import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.acid.wechat.core.AcidFeature
import com.acid.wechat.core.AcidLogger
import com.acid.wechat.core.ConfigSchema
import com.acid.wechat.core.ConfigStore
import com.acid.wechat.core.DexKitLocator
import com.acid.wechat.core.FeatureOption
import com.acid.wechat.core.FeatureRegistry
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.json.JSONObject
import java.io.File
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicInteger

/**
 * Custom Export / Save Directory feature (`我的 -> 保存路径`):
 * Redirects WeChat's public file download directory (`ExportFileUtil` + `ScopedStorageUtil`)
 * and saved image/video directory (`Luggage.AndroidMediaUtil`) to user-configured paths.
 * Hooking both `d`/`e` and `i` + `ScopedStorageUtil` prevents ART intra-class inlining bypasses.
 */
object SavePathFeature : AcidFeature {
    private const val TAG = "SavePath"
    private const val EXPORT_UTIL_CLS = "com.tencent.mm.platformtools.ExportFileUtil"

    private lateinit var ruleExportDownloadPath: DexKitLocator.DexRule
    private lateinit var ruleExportDownloadExec: DexKitLocator.DexRule
    private lateinit var ruleExportDownloadAsync: DexKitLocator.DexRule
    private lateinit var ruleScopedStorageSave: DexKitLocator.DexRule
    private lateinit var ruleLuggageMediaUtil: DexKitLocator.DexRule

    private val redirectedCount = AtomicInteger(0)

    override val id = "custom_save_path"
    override val spec = ConfigSchema.CUSTOM_SAVE_PATH_ENABLED

    override fun extraOptions(): List<FeatureOption> = listOf(
        FeatureOption.from(ConfigSchema.SAVE_FILE_DIR),
        FeatureOption.from(ConfigSchema.SAVE_MEDIA_DIR),
    )

    override fun registerDexRules() {
        ruleExportDownloadPath = DexKitLocator.registerMethod(
            id = "export_file_download_path",
            description = "ExportFileUtil.getPublicDownloadDestPath(String)",
        ) {
            matcher {
                declaredClass(EXPORT_UTIL_CLS)
                usingEqStrings("[-] Fail to get public download path from system.")
            }
        }
        ruleExportDownloadExec = DexKitLocator.registerMethod(
            id = "export_file_download_exec",
            description = "ExportFileUtil.exportToPublicDownloadDir(Context, String, String)",
        ) {
            matcher {
                declaredClass(EXPORT_UTIL_CLS)
                usingEqStrings("[+] Called exportToPublicDownloadDir, src: %s, dest: %s")
            }
        }
        ruleExportDownloadAsync = DexKitLocator.registerMethod(
            id = "export_file_download_async",
            description = "ExportFileUtil.exportToPublicDownloadDirWithPermissionRequest",
        ) {
            matcher {
                declaredClass(EXPORT_UTIL_CLS)
                usingEqStrings("[+] Called exportToPublicDownloadDirWithPermissionRequest, src: %s, dest: %s")
            }
        }
        ruleScopedStorageSave = DexKitLocator.registerMethod(
            id = "scoped_storage_save",
            description = "ScopedStorageUtil.saveWithMediaStoreOrBio",
        ) {
            matcher {
                usingEqStrings("MicroMsg.ScopedStorageUtil", "Try save with MediaStore", "Try save with BIO")
            }
        }
        ruleLuggageMediaUtil = DexKitLocator.registerClass(
            id = "luggage_android_media_util",
            description = "Luggage.AndroidMediaUtil media export path helper",
        ) {
            matcher {
                usingEqStrings("Luggage.AndroidMediaUtil", "mmexport", "%s%d.%s")
            }
        }
    }

    override fun onInit() {
        hookFileDownloadPath()
        deoptimizeDownloadCallers()
        hookScopedStorageSave()
        hookMediaExportPath()
    }

    private fun hookFileDownloadPath() {
        val m = ruleExportDownloadPath.resolveMethod() ?: return
        runCatching {
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!FeatureRegistry.isEnabled(this@SavePathFeature)) return
                    val orig = param.result as? String ?: return
                    val rewritten = rewritePath(orig, ConfigSchema.SAVE_FILE_DIR, Environment.DIRECTORY_DOWNLOADS)
                        ?: return
                    param.result = rewritten
                    recordRedirect("file", rewritten)
                }
            })
        }.onFailure {
            FeatureRegistry.recordError(id, it)
            AcidLogger.w(TAG, "hookFileDownloadPath failed", it)
        }
    }

    private fun deoptimizeDownloadCallers() {
        // Force ART to keep ExportFileUtil.i un-inlined inside d() and e().
        val noop = object : XC_MethodHook() {}
        for (rule in listOf(ruleExportDownloadExec, ruleExportDownloadAsync)) {
            val m = rule.resolveMethod() ?: continue
            runCatching { XposedBridge.hookMethod(m, noop) }
        }
    }

    private fun hookScopedStorageSave() {
        val m = ruleScopedStorageSave.resolveMethod() ?: return
        runCatching {
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!FeatureRegistry.isEnabled(this@SavePathFeature)) return
                    val args = param.args ?: return
                    val origDest = args.getOrNull(2) as? String ?: return
                    val uri = args.getOrNull(3) as? Uri
                    val isDownload = uri == MediaStore.Downloads.EXTERNAL_CONTENT_URI ||
                        (uri == null && origDest.contains("/${Environment.DIRECTORY_DOWNLOADS}/"))
                    val isMedia = uri == MediaStore.Images.Media.EXTERNAL_CONTENT_URI ||
                        uri == MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    if (!isDownload && !isMedia) return

                    val spec = if (isDownload) ConfigSchema.SAVE_FILE_DIR else ConfigSchema.SAVE_MEDIA_DIR
                    val subRoot = if (isDownload) Environment.DIRECTORY_DOWNLOADS else Environment.DIRECTORY_PICTURES
                    val rewritten = rewritePath(origDest, spec, subRoot) ?: return
                    if (rewritten != origDest) {
                        args[2] = rewritten
                        recordRedirect("scoped", rewritten)
                    }
                }
            })
        }.onFailure {
            FeatureRegistry.recordError(id, it)
            AcidLogger.w(TAG, "hookScopedStorageSave failed", it)
        }
    }

    private fun hookMediaExportPath() {
        val utilCls = ruleLuggageMediaUtil.resolveClass() ?: return
        val candidates = utilCls.declaredMethods.filter { m ->
            Modifier.isStatic(m.modifiers) &&
                m.returnType == String::class.java &&
                m.parameterTypes.size == 1 &&
                m.parameterTypes[0] == String::class.java
        }
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!FeatureRegistry.isEnabled(this@SavePathFeature)) return
                val orig = param.result as? String ?: return
                if (!orig.substringAfterLast('/').startsWith("mmexport")) return
                val rewritten = rewritePath(orig, ConfigSchema.SAVE_MEDIA_DIR, Environment.DIRECTORY_PICTURES)
                    ?: return
                param.result = rewritten
                recordRedirect("media", rewritten)
            }
        }
        for (m in candidates) {
            runCatching { XposedBridge.hookMethod(m, hook) }.onFailure {
                FeatureRegistry.recordError(id, it)
                AcidLogger.w(TAG, "hookMediaExportPath failed on ${m.name}", it)
            }
        }
    }

    private fun recordRedirect(kind: String, dest: String) {
        redirectedCount.incrementAndGet()
        FeatureRegistry.recordHit(id)
        AcidLogger.i(TAG, "redirected $kind -> $dest")
    }

    private fun rewritePath(origPath: String, spec: ConfigSchema.StringSpec, subRoot: String): String? {
        val fileName = origPath.substringAfterLast('/')
        if (fileName.isEmpty()) return null
        val targetDir = resolveDirectory(ConfigStore.getString(spec), subRoot) ?: return null
        return "${targetDir.trimEnd('/')}/$fileName"
    }

    private fun resolveDirectory(rawInput: String, defaultPublicSubRoot: String): String? {
        val trimmed = rawInput.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null
        val extRoot = Environment.getExternalStorageDirectory()
        val normalized = when {
            trimmed == "/sdcard" -> extRoot.absolutePath
            trimmed.startsWith("/sdcard/") -> File(extRoot, trimmed.removePrefix("/sdcard/")).absolutePath
            else -> trimmed
        }
        val dir = when {
            normalized.startsWith("/") -> File(normalized)
            '/' in normalized -> File(extRoot, normalized.trimStart('/'))
            else -> {
                val base = Environment.getExternalStoragePublicDirectory(defaultPublicSubRoot)
                    ?: File(extRoot, defaultPublicSubRoot)
                File(base, normalized)
            }
        }
        runCatching { if (!dir.exists()) dir.mkdirs() }
        return runCatching { dir.canonicalPath }.getOrElse { dir.absolutePath }
    }

    override fun statusJson(): JSONObject {
        val fileRaw = ConfigStore.getString(ConfigSchema.SAVE_FILE_DIR)
        val mediaRaw = ConfigStore.getString(ConfigSchema.SAVE_MEDIA_DIR)
        return JSONObject().apply {
            put("redirectedCount", redirectedCount.get())
            put("fileDir", fileRaw)
            put("mediaDir", mediaRaw)
            put("resolvedFileDir", resolveDirectory(fileRaw, Environment.DIRECTORY_DOWNLOADS) ?: "")
            put("resolvedMediaDir", resolveDirectory(mediaRaw, Environment.DIRECTORY_PICTURES) ?: "")
        }
    }
}
