package com.acid.wechat.features

import android.content.Intent
import com.acid.wechat.core.AcidFeature
import com.acid.wechat.core.AcidLogger
import com.acid.wechat.core.ConfigSchema
import com.acid.wechat.core.ConfigStore
import com.acid.wechat.core.FeatureRegistry
import com.acid.wechat.core.HostContext
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Blocks WeChat Tinker hot-patch loading in `attachBaseContext` and background `:patch`
 * synthesis, and cleans up downloaded patch directories to keep host DEX signatures stable.
 */
object BlockHotUpdateFeature : AcidFeature {
    private const val TAG = "BlockHotUpdate"
    private const val TINKER_APP_CLASS = "com.tencent.tinker.loader.app.TinkerApplication"
    private const val TINKER_INTERNALS_CLASS = "com.tencent.tinker.loader.shareutil.ShareTinkerInternals"
    private const val TINKER_FILE_UTIL_CLASS = "com.tencent.tinker.loader.shareutil.SharePatchFileUtil"
    private const val TINKER_PATCH_SERVICE_CLASS = "com.tencent.tinker.lib.service.TinkerPatchService"

    override val id = "disable_hot_update"
    override val spec = ConfigSchema.DISABLE_HOT_UPDATE

    private val earlyHookInstalled = AtomicBoolean(false)
    private val blockedLoadCount = AtomicInteger(0)
    private val blockedPatchServiceCount = AtomicInteger(0)
    private val cleanedFileCount = AtomicInteger(0)

    fun isEarlyEnabled(): Boolean = ConfigStore.getBooleanEarlySafe(ConfigSchema.DISABLE_HOT_UPDATE)

    fun installEarlyHooks(classLoader: ClassLoader, processName: String) {
        if (!earlyHookInstalled.compareAndSet(false, true)) return

        runCatching {
            val tinkerAppCls = Class.forName(TINKER_APP_CLASS, false, classLoader)
            XposedHelpers.findAndHookMethod(
                tinkerAppCls,
                "getTinkerFlags",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isEarlyEnabled()) return
                        param.result = 0
                        blockedLoadCount.incrementAndGet()
                        FeatureRegistry.recordHit(id)
                    }
                },
            )
            AcidLogger.i(TAG, "hooked TinkerApplication.getTinkerFlags ($processName)")
        }.onFailure {
            AcidLogger.w(TAG, "failed hooking TinkerApplication.getTinkerFlags ($processName)", it)
        }

        runCatching {
            val internalsCls = Class.forName(TINKER_INTERNALS_CLASS, false, classLoader)
            XposedHelpers.findAndHookMethod(
                internalsCls,
                "isTinkerEnabled",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isEarlyEnabled()) return
                        param.result = false
                    }
                },
            )
            AcidLogger.i(TAG, "hooked ShareTinkerInternals.isTinkerEnabled ($processName)")
        }.onFailure {
            AcidLogger.w(TAG, "failed hooking ShareTinkerInternals.isTinkerEnabled ($processName)", it)
        }

        runCatching {
            val patchServiceCls = Class.forName(TINKER_PATCH_SERVICE_CLASS, false, classLoader)
            XposedHelpers.findAndHookMethod(
                patchServiceCls,
                "onHandleIntent",
                Intent::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isEarlyEnabled()) return
                        param.result = null
                        blockedPatchServiceCount.incrementAndGet()
                        FeatureRegistry.recordHit(id)
                        AcidLogger.i(TAG, "blocked TinkerPatchService.onHandleIntent ($processName)")
                    }
                },
            )
            AcidLogger.i(TAG, "hooked TinkerPatchService.onHandleIntent ($processName)")
        }.onFailure {
            AcidLogger.w(TAG, "failed hooking TinkerPatchService.onHandleIntent ($processName)", it)
        }
    }

    override fun onInit() {
        ConfigStore.addListener { changedKeys ->
            if (changedKeys.contains(ConfigSchema.DISABLE_HOT_UPDATE.key) && FeatureRegistry.isEnabled(this)) {
                purgeTinkerPatches()
            }
        }
        if (FeatureRegistry.isEnabled(this)) {
            purgeTinkerPatches()
        }
    }

    private fun purgeTinkerPatches() {
        val app = HostContext.app
        runCatching {
            val internalsCls = Class.forName(TINKER_INTERNALS_CLASS, false, HostContext.classLoader)
            XposedHelpers.callStaticMethod(internalsCls, "setTinkerDisableWithSharedPreferences", app)
        }.onFailure {
            AcidLogger.w(TAG, "setTinkerDisableWithSharedPreferences failed", it)
        }

        val patchDirs = linkedSetOf<File>()
        runCatching {
            val fileUtilCls = Class.forName(TINKER_FILE_UTIL_CLASS, false, HostContext.classLoader)
            val dir = XposedHelpers.callStaticMethod(fileUtilCls, "getPatchDirectory", app) as? File
            if (dir != null) patchDirs += dir
        }.onFailure {
            AcidLogger.w(TAG, "SharePatchFileUtil.getPatchDirectory failed", it)
        }

        app.filesDir?.parentFile?.let { dataRoot ->
            patchDirs += File(dataRoot, "tinker")
            patchDirs += File(dataRoot, "tinker_temp")
            patchDirs += File(dataRoot, "tinker_server")
        }

        var deleted = 0
        for (dir in patchDirs) {
            if (!dir.exists()) continue
            deleted += deleteRecursive(dir)
        }
        if (deleted > 0) {
            cleanedFileCount.addAndGet(deleted)
            AcidLogger.i(TAG, "purged $deleted Tinker patch entries")
        }
    }

    private fun deleteRecursive(target: File): Int {
        var count = 0
        if (target.isDirectory) {
            target.listFiles()?.forEach { child ->
                count += deleteRecursive(child)
            }
        }
        if (runCatching { target.delete() }.getOrDefault(false)) {
            count++
        }
        return count
    }

    override fun statusJson(): JSONObject = JSONObject().apply {
        put("earlyHooked", earlyHookInstalled.get())
        put("blockedLoadCount", blockedLoadCount.get())
        put("blockedPatchServiceCount", blockedPatchServiceCount.get())
        put("cleanedFileCount", cleanedFileCount.get())
    }
}
