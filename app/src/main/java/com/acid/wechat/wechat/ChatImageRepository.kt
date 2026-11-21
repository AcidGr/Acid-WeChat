package com.acid.wechat.wechat

import android.graphics.BitmapFactory
import com.acid.wechat.core.AcidLogger
import com.acid.wechat.core.HostContext
import com.acid.wechat.core.WcdbBridge
import java.io.File

/**
 * Resolves WeChat image message attachments (`type == 3`) via `ImgInfo2` and `message.imgPath`
 * to concrete readable files on disk (`bigImgPath` -> `midImgPath` -> `thumbImgPath`), with
 * 32-char hash fallback across quality tiers.
 */
object ChatImageRepository {
    private const val TAG = "ChatImageRepo"
    private const val THUMB_PREFIX = "THUMBNAIL_DIRPATH://"
    private const val SERVER_PREFIX = "SERVERID://"
    private val imgExts = listOf("", ".jpg", ".png", ".webp", ".jpeg")
    private val hashRegex = Regex("(?:^|/)(?:th_|mid_|hd_)?([0-9a-f]{32})(?:_hevc)?(?:\\.[a-z0-9]+)?$", RegexOption.IGNORE_CASE)

    data class ImageRecord(
        val localId: Long,
        val msgLocalId: Long,
        val msgSvrId: Long,
        val talker: String,
        val bigImgPath: String,
        val midImgPath: String,
        val thumbImgPath: String,
        val hevcPath: String,
    )

    fun extractImageHash(raw: String): String? {
        val cleaned = raw.trim().removePrefix(THUMB_PREFIX)
        if (cleaned.isEmpty() || cleaned.startsWith(SERVER_PREFIX)) return null
        return hashRegex.find(cleaned)?.groupValues?.getOrNull(1)?.lowercase()
    }

    fun isThumbnailPath(pathOrName: String): Boolean {
        val name = pathOrName.substringAfterLast('/')
        return name.startsWith("th_", ignoreCase = true)
    }

    fun resolveMessageImagePath(
        msgId: Long,
        msgSvrId: Long,
        msgImgPath: String = "",
    ): String? = runCatching {
        val candidates = linkedSetOf<String>()
        if (msgId > 0L || msgSvrId > 0L) {
            val cursor = WcdbBridge.rawQuery(
                "SELECT bigImgPath, midImgPath, hevcPath, thumbImgPath FROM ImgInfo2 " +
                    "WHERE msglocalid = ? OR (? > 0 AND msgSvrId = ?) " +
                    "ORDER BY CASE WHEN msgSvrId > 0 THEN 0 ELSE 1 END, id ASC LIMIT 1",
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
            resolveCandidateFile(raw, roots)?.takeIf(::isDecodableOrNonThumb)?.let {
                return@runCatching it.absolutePath
            }
        }
        for (raw in candidates) {
            val hash = extractImageHash(raw) ?: continue
            resolveBestFileForHash(hash, roots = roots)?.let { return@runCatching it.absolutePath }
        }
        for (raw in candidates) {
            resolveCandidateFile(raw, roots)?.let { return@runCatching it.absolutePath }
        }
        null
    }.onFailure {
        AcidLogger.w(TAG, "resolveMessageImagePath failed msgId=$msgId", it)
    }.getOrNull()

    fun resolveBestFileForInput(rawPath: String, preferNonThumbOnly: Boolean = false): File? {
        val trimmed = rawPath.trim()
        if (trimmed.isEmpty()) return null
        val hash = extractImageHash(trimmed)
        if (hash != null) {
            resolveBestFileForHash(hash, preferNonThumbOnly)?.let { return it }
        }
        if (preferNonThumbOnly) return null
        val direct = File(trimmed)
        if (isValidImageFile(direct) && !isThumbnailPath(direct.name)) return direct
        return if (isValidImageFile(direct)) direct else null
    }

    fun resolveBestFileForHash(
        hash: String,
        preferNonThumbOnly: Boolean = false,
        roots: List<File> = resolveImage2Roots(),
    ): File? {
        val cleanHash = hash.trim().lowercase()
        if (cleanHash.length != 32) return null
        val sub = "${cleanHash.substring(0, 2)}/${cleanHash.substring(2, 4)}"
        val nonThumbNames = listOf(
            "$cleanHash.jpg", "$cleanHash.png", "$cleanHash.webp", "$cleanHash.jpeg", cleanHash,
            "mid_$cleanHash.jpg", "mid_$cleanHash", "hd_$cleanHash.jpg", "hd_$cleanHash", "${cleanHash}_hevc",
        )
        val thumbNames = if (preferNonThumbOnly) emptyList() else listOf(
            "th_$cleanHash.jpg", "th_$cleanHash.png", "th_$cleanHash.webp", "th_$cleanHash",
        )
        return findFirstFile(roots, sub, nonThumbNames, requireDecodable = true)
            ?: findFirstFile(roots, sub, nonThumbNames, requireDecodable = false)
            ?: findFirstFile(roots, sub, thumbNames, requireDecodable = false)
    }

    private const val IMG_RECORD_SELECT =
        "SELECT i.id, i.msglocalid, i.msgSvrId, COALESCE(NULLIF(i.msgTalker,''), m.talker, ''), " +
            "i.bigImgPath, i.midImgPath, i.thumbImgPath, i.hevcPath " +
            "FROM ImgInfo2 i LEFT JOIN message m ON (i.msglocalid > 0 AND m.msgId = i.msglocalid) " +
            "OR (i.msgSvrId > 0 AND m.msgSvrId = i.msgSvrId) "

    fun queryBaseImageRecordByHash(hash: String): ImageRecord? = runCatching {
        val cleanHash = hash.trim().lowercase()
        if (cleanHash.length != 32) return@runCatching null
        val pattern = "%$cleanHash%"
        val sql = IMG_RECORD_SELECT +
            "WHERE i.thumbImgPath LIKE ? OR i.bigImgPath LIKE ? OR i.midImgPath LIKE ? OR i.hevcPath LIKE ? " +
            "ORDER BY CASE WHEN i.msgSvrId > 0 THEN 0 WHEN i.msglocalid > 0 THEN 1 ELSE 2 END, i.id ASC LIMIT 1"
        val cursor = WcdbBridge.rawQuery(sql, arrayOf(pattern, pattern, pattern, pattern))
            ?: return@runCatching null
        cursor.use { c -> if (!c.moveToFirst()) null else readRecord(c) }
    }.onFailure {
        AcidLogger.w(TAG, "queryBaseImageRecordByHash failed hash=$hash", it)
    }.getOrNull()

    fun queryImageRecordById(localId: Long): ImageRecord? = runCatching {
        if (localId <= 0L) return@runCatching null
        val cursor = WcdbBridge.rawQuery("${IMG_RECORD_SELECT}WHERE i.id = ? LIMIT 1", arrayOf(localId.toString()))
            ?: return@runCatching null
        cursor.use { c -> if (!c.moveToFirst()) null else readRecord(c) }
    }.getOrNull()

    fun resolveRecordNonThumbFile(record: ImageRecord): File? {
        val roots = resolveImage2Roots()
        val candidates = listOf(record.bigImgPath, record.midImgPath, record.hevcPath)
        for (raw in candidates) {
            if (raw.isBlank() || isThumbnailPath(raw)) continue
            resolveCandidateFile(raw, roots)?.takeIf(::isDecodableImage)?.let { return it }
        }
        for (raw in candidates) {
            if (raw.isBlank() || isThumbnailPath(raw)) continue
            resolveCandidateFile(raw, roots)?.let { return it }
        }
        val hash = listOf(record.bigImgPath, record.midImgPath, record.thumbImgPath, record.hevcPath)
            .firstNotNullOfOrNull { extractImageHash(it) }
        return hash?.let { resolveBestFileForHash(it, preferNonThumbOnly = true, roots = roots) }
    }

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

    private fun readRecord(c: android.database.Cursor): ImageRecord = ImageRecord(
        localId = c.getLong(0),
        msgLocalId = c.getLong(1),
        msgSvrId = c.getLong(2),
        talker = c.getString(3).orEmpty().trim(),
        bigImgPath = c.getString(4).orEmpty().trim(),
        midImgPath = c.getString(5).orEmpty().trim(),
        thumbImgPath = c.getString(6).orEmpty().trim(),
        hevcPath = c.getString(7).orEmpty().trim(),
    )

    private fun findFirstFile(
        roots: List<File>,
        sub: String,
        names: List<String>,
        requireDecodable: Boolean,
    ): File? {
        for (root in roots) {
            for (name in names) {
                val f = File(root, "$sub/$name")
                if (isValidImageFile(f) && (!requireDecodable || isDecodableImage(f))) return f
            }
        }
        return null
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
            .removePrefix("hd_")
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

    private fun isDecodableOrNonThumb(f: File): Boolean =
        !isThumbnailPath(f.name) && isDecodableImage(f)

    fun isDecodableImage(f: File): Boolean = runCatching {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, opts)
        opts.outWidth > 0 && opts.outHeight > 0
    }.getOrDefault(false)

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
