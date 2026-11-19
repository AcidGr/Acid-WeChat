package com.acid.wechat.core

import android.app.Application
import android.content.pm.PackageInfo
import java.io.File

/**
 * Holds resolved host (WeChat) runtime metadata and module paths.
 */
object HostContext {
    const val MODULE_VERSION_NAME = "0.2.0"
    const val MODULE_VERSION_CODE = 2
    const val WECHAT_PACKAGE = "com.tencent.mm"
    val SUPPORTED_WECHAT_PACKAGES: Set<String> = setOf("com.tencent.mm", "com.tencent.mn")

    @Volatile
    var hostPackage: String = WECHAT_PACKAGE
        private set

    fun isSupportedPackage(pkg: String?): Boolean = pkg != null && pkg in SUPPORTED_WECHAT_PACKAGES

    fun setHostPackage(pkg: String) {
        if (pkg.isNotBlank()) hostPackage = pkg
    }

    fun extDataDirPath(pkg: String = hostPackage): String =
        "/storage/emulated/0/Android/media/$pkg/AcidWeChat"

    fun intDataDirFallback(pkg: String = hostPackage): String =
        "/data/user/0/$pkg/files/acid_wechat"

    fun candidateDataDirs(): List<File> =
        listOf(File(extDataDirPath()), File(intDataDirFallback()))

    @Volatile
    var modulePath: String = ""
        private set

    @Volatile
    lateinit var app: Application
        private set

    @Volatile
    lateinit var classLoader: ClassLoader
        private set

    @Volatile
    var hostVersionName: String = "unknown"
        private set

    @Volatile
    var hostVersionCode: Long = 0L
        private set

    @Volatile
    var hostApkPath: String = ""
        private set

    @Volatile
    var startedAtMs: Long = 0L
        private set

    @Volatile
    var isInitialized: Boolean = false
        private set

    fun setModulePath(path: String) {
        modulePath = path
    }

    fun init(application: Application) {
        app = application
        hostPackage = application.packageName.ifBlank { hostPackage }
        classLoader = application.classLoader
        startedAtMs = System.currentTimeMillis()
        runCatching {
            val pm = application.packageManager
            val pkgInfo: PackageInfo = pm.getPackageInfo(hostPackage, 0)
            hostVersionName = pkgInfo.versionName ?: "unknown"
            hostVersionCode = pkgInfo.longVersionCode
            hostApkPath = application.applicationInfo.sourceDir ?: ""
        }
        isInitialized = true
    }

    /**
     * Resolves a writable data directory, preferring WeChat's shared external media folder
     * so logs/configs are easy to inspect from chroot, with fallback to internal filesDir.
     */
    fun getDataDir(): File {
        val extDir = File(extDataDirPath())
        if (runCatching {
                if (!extDir.exists()) extDir.mkdirs()
                extDir.exists() && extDir.canWrite()
            }.getOrDefault(false)
        ) {
            return extDir
        }
        val internalDir = File(app.filesDir, "acid_wechat")
        if (!internalDir.exists()) internalDir.mkdirs()
        return internalDir
    }
}
