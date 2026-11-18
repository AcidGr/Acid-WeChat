package com.acid.wechat.core

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/**
 * Persistent DexKit descriptor cache (keyed by WeChat APK + Tinker ClassLoader fingerprint)
 * and native libdexkit.so extraction from the module APK.
 */
internal object DexKitCache {
    private const val TAG = "DexKitCache"
    private const val CACHE_FILE = "dexkit_cache.json"

    fun computeHostFingerprint(): String {
        val apk = File(HostContext.hostApkPath)
        val apkSig = if (apk.exists()) "${apk.length()}_${apk.lastModified()}" else "noapk"
        val tinkerFlag = HostContext.classLoader.toString().let {
            if (it.contains("tinker")) it.hashCode().toString(16) else "base"
        }
        return "${HostContext.hostVersionCode}_${apkSig}_$tinkerFlag"
    }

    fun clear(): Boolean = runCatching {
        val f = File(HostContext.getDataDir(), CACHE_FILE)
        if (f.exists()) f.delete() else true
    }.getOrDefault(false)

    fun loadIfValid(
        rules: Map<String, DexKitLocator.DexRule>,
        ruleOrder: List<String>,
        expectedFp: String,
    ): Int {
        return runCatching {
            val f = File(HostContext.getDataDir(), CACHE_FILE)
            if (!f.exists() || f.length() == 0L) return -1
            val root = JSONObject(f.readText(Charsets.UTF_8))
            if (root.optString("fingerprint") != expectedFp) return -1
            val entries = root.optJSONObject("rules") ?: return -1

            var hits = 0
            for (id in ruleOrder) {
                val rule = rules[id] ?: continue
                val entry = entries.optJSONObject(id) ?: return -1
                val target = DexKitLocator.ResolvedTarget.fromJson(entry)
                if (!isTargetResolvable(target)) {
                    AcidLogger.w(TAG, "cached rule [$id] invalid in current ClassLoader, invalidating cache")
                    return -1
                }
                rule.target = target
                rule.status = DexKitLocator.RuleStatus.CACHED
                rule.error = null
                hits++
            }
            hits
        }.getOrDefault(-1)
    }

    fun save(
        rules: Map<String, DexKitLocator.DexRule>,
        ruleOrder: List<String>,
        fingerprint: String,
    ) {
        runCatching {
            val rulesObj = JSONObject()
            for (id in ruleOrder) {
                val target = rules[id]?.target ?: continue
                rulesObj.put(id, target.toJson())
            }
            val root = JSONObject().apply {
                put("fingerprint", fingerprint)
                put("savedAt", System.currentTimeMillis())
                put("rules", rulesObj)
            }
            File(HostContext.getDataDir(), CACHE_FILE).writeText(root.toString(2), Charsets.UTF_8)
        }.onFailure {
            AcidLogger.w(TAG, "saveCache failed", it)
        }
    }

    @Suppress("UnsafeDynamicallyLoadedCode")
    fun ensureNativeLoaded(): Boolean {
        if (runCatching { System.loadLibrary("dexkit"); true }.getOrDefault(false)) {
            return true
        }
        val modPath = HostContext.modulePath
        if (modPath.isBlank()) {
            AcidLogger.e(TAG, "modulePath empty, cannot extract libdexkit.so")
            return false
        }
        return runCatching {
            val soDir = File(HostContext.app.codeCacheDir, "acid_wechat")
            if (!soDir.exists()) soDir.mkdirs()
            val soFile = File(soDir, "libdexkit.so")
            ZipFile(modPath).use { zip ->
                val entry = zip.getEntry("lib/arm64-v8a/libdexkit.so")
                    ?: error("lib/arm64-v8a/libdexkit.so not found in $modPath")
                if (!soFile.exists() || soFile.length() != entry.size) {
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(soFile).use { output -> input.copyTo(output) }
                    }
                    soFile.setReadable(true, false)
                    soFile.setExecutable(true, false)
                }
            }
            System.load(soFile.absolutePath)
            AcidLogger.i(TAG, "loaded extracted libdexkit.so from ${soFile.absolutePath}")
            true
        }.onFailure {
            AcidLogger.e(TAG, "failed extracting/loading libdexkit.so", it)
        }.getOrDefault(false)
    }

    private fun isTargetResolvable(target: DexKitLocator.ResolvedTarget): Boolean = runCatching {
        val cls = Class.forName(target.className, false, HostContext.classLoader)
        when (target.kind) {
            DexKitLocator.RuleKind.CLASS -> true
            DexKitLocator.RuleKind.METHOD ->
                ReflectHelper.findMethodByNames(cls, target.memberName, target.paramTypes) != null
            DexKitLocator.RuleKind.CTOR -> {
                ReflectHelper.findConstructorByNames(cls, target.paramTypes)
                true
            }
        }
    }.getOrDefault(false)
}
