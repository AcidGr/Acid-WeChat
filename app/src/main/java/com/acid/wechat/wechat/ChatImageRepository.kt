package com.acid.wechat.wechat

import com.acid.wechat.core.AcidLogger
import com.acid.wechat.core.HostContext
import com.acid.wechat.core.WcdbBridge
import java.io.File

/**
 * Resolves WeChat image message attachments (`type == 3`) via `ImgInfo2` and `message.imgPath`
 * to concrete readable files on disk (`bigImgPath` -> `midImgPath` -> `thumbImgPath`).
 */
object ChatImageRepository {
    private const val TAG = "ChatImageRepo"
    private const val THUMB_PREFIX = "THUMBNAIL_DIRPATH://"
    private const val SERVER_PREFIX = "SERVERID://"
    private val imgExts = listOf("", ".jpg", ".png", ".webp", ".jpeg")

    fun resolveMessageImagePath(
        msgId: Long,
        msgSvrId: Long,
        msgImgPath: String = "",
    ): String? = runCatching {
        val candidates = linkedSetOf<String>()
        if (msgId > 0L || msgSvrId > 0L) {
            val cursor = WcdbBridge.rawQuery(
                "SELECT bigImgPath, midImgPath, thumbImgPath, hevcPath FROM ImgInfo2 " +
                    "WHERE msglocalid = ? OR (? > 0 AND msgSvrId = ?) LIMIT 1",
                arrayOf(msgId.toString(), msgSvrId.toString(), msgSvrId.toString()),
            )
            cursor?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { candidates += it }
                    c.getString(1)?.let { candidates += it }
                    c.getString(2)?.let { candidates += it }
                    c.getString(3)?.let { candidates += it }
                }
            }
        }
        if (msgImgPath.isNotBlank()) candidates += msgImgPath

        val roots = resolveImage2Roots()
        for (raw in candidates) {
            resolveCandidateFile(raw, roots)?.let { return@runCatching it.absolutePath }
        }
        null
    }.onFailure {
        AcidLogger.w(TAG, "resolveMessageImagePath failed msgId=$msgId", it)
    }.getOrNull()

    fun isInsideWeChatMediaRoots(file: File): Boolean {
        val canon = runCatching { file.canonicalFile }.getOrNull() ?: return false
        val path = canon.absolutePath
        val allowedRoots = resolveImage2Roots().mapNotNull { runCatching { it.canonicalFile.parentFile }.getOrNull() }
        if (allowedRoots.any { root -> path == root.absolutePath || path.startsWith(root.absolutePath + File.separator) }) {
            return true
        }
        val pkgData = runCatching { HostContext.app.filesDir.parentFile?.canonicalFile?.absolutePath }.getOrNull()
        return pkgData != null && path.startsWith("$pkgData/")
    }

    private fun resolveCandidateFile(raw: String, roots: List<File>): File? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.startsWith(SERVER_PREFIX)) return null

        val cleaned = if (trimmed.startsWith(THUMB_PREFIX)) trimmed.removePrefix(THUMB_PREFIX) else trimmed
        if (cleaned.startsWith("/")) {
            val direct = File(cleaned)
            if (isValidImageFile(direct)) return direct
        }

        val shardBase = cleaned
            .substringAfterLast('/')
            .removePrefix("th_")
            .removePrefix("mid_")
        val shardSub = if (shardBase.length >= 4) {
            "${shardBase.substring(0, 2)}/${shardBase.substring(2, 4)}"
        } else {
            ""
        }

        val fileName = cleaned.substringAfterLast('/')
        for (root in roots) {
            if (cleaned.contains('/')) {
                val rel = File(root, cleaned)
                if (isValidImageFile(rel)) return rel
            }
            if (shardSub.isNotEmpty()) {
                for (ext in imgExts) {
                    val f = File(root, "$shardSub/$fileName$ext")
                    if (isValidImageFile(f)) return f
                }
            }
            for (ext in imgExts) {
                val flat = File(root, "$fileName$ext")
                if (isValidImageFile(flat)) return flat
            }
        }
        return null
    }

    private fun isValidImageFile(f: File): Boolean =
        f.isFile && f.canRead() && f.length() > 32L

    private fun resolveImage2Roots(): List<File> {
        val dirs = linkedSetOf<File>()
        WcdbBridge.mainDbPath()?.let { dbPath ->
            File(dbPath).parentFile?.let { acctDir ->
                File(acctDir, "image2").takeIf { it.isDirectory }?.let { dirs += it }
            }
        }
        val baseCandidates = listOfNotNull(
            HostContext.app.filesDir.parentFile?.let { File(it, "MicroMsg") },
            HostContext.app.getExternalFilesDir(null)?.parentFile?.let { File(it, "MicroMsg") },
            File("/storage/emulated/0/Android/data/${HostContext.hostPackage}/MicroMsg"),
        )
        for (mmRoot in baseCandidates) {
            if (!mmRoot.isDirectory) continue
            mmRoot.listFiles()?.forEach { child ->
                if (child.isDirectory && child.name.length == 32) {
                    File(child, "image2").takeIf { it.isDirectory }?.let { dirs += it }
                }
            }
        }
        return dirs.toList()
    }
}
