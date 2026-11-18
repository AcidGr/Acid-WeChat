package com.acid.wechat.wechat

import android.content.ContentValues
import com.acid.wechat.core.HostContext
import com.acid.wechat.core.WcdbBridge
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Read-only WeChat chat/contact repository backed by [WcdbBridge] (`EnMicroMsg.db`).
 * Keeps raw SQL table/column details out of individual features.
 */
object ChatRepository {
    private val genericPlaceholders = setOf("对方", "You", "you")
    private val systemUsernames = setOf(
        "filehelper", "weixin", "fmessage", "medianote", "floatbottle",
        "qmessage", "qqmail", "newsapp", "weibo", "tmessage", "lbsapp",
        "shakeapp", "bottleapp", "feedsapp", "voipapp", "officialaccounts",
        "notification_messages", "helper_entry", "qqsync", "blogapp",
    )

    data class ContactSummary(
        val username: String,
        val displayName: String,
        val nickname: String,
        val remark: String,
        val alias: String,
    )

    fun queryIsSendBySvrId(msgSvrId: Long): Boolean? {
        val cursor = WcdbBridge.rawQuery(
            "SELECT isSend FROM message WHERE msgSvrId = ? LIMIT 1",
            arrayOf(msgSvrId.toString()),
        ) ?: return null
        return cursor.use {
            if (it.moveToFirst()) it.getInt(0) != 0 else null
        }
    }

    fun queryMessageBySvrId(msgSvrId: Long): ContentValues? =
        queryMessageRow("msgSvrId = ?", arrayOf(msgSvrId.toString()))

    fun queryMessageById(msgId: Long): ContentValues? =
        queryMessageRow("msgId = ?", arrayOf(msgId.toString()))

    private fun queryMessageRow(where: String, args: Array<String>): ContentValues? {
        val cursor = WcdbBridge.rawQuery(
            "SELECT msgId, msgSvrId, type, status, isSend, talker, content, imgPath, createTime " +
                "FROM message WHERE $where LIMIT 1",
            args,
        ) ?: return null
        return cursor.use {
            if (!it.moveToFirst()) null else ContentValues().apply {
                put("msgId", it.getLong(0))
                put("msgSvrId", it.getLong(1))
                put("type", it.getInt(2))
                put("status", it.getInt(3))
                put("isSend", it.getInt(4))
                put("talker", it.getString(5))
                put("content", it.getString(6))
                put("imgPath", it.getString(7))
                put("createTime", it.getLong(8))
            }
        }
    }

    /**
     * Resolves display name for a contact or chatroom message sender.
     * In 1v1 chats, prefers remark (`conRemark`) -> nickname -> quoted hint -> alias.
     * In group chats (`@chatroom`), resolves sender wxid from `originalContent` and prefers
     * remark (`conRemark`) -> quoted group alias -> nickname -> alias.
     */
    fun resolveSenderDisplayName(
        talker: String,
        originalContent: String?,
        quotedHint: String?,
    ): String {
        val cleanHint = quotedHint?.trim()?.takeIf { it.isNotEmpty() && it !in genericPlaceholders }
        if (talker.endsWith("@chatroom")) {
            val senderWxid = extractGroupSenderWxid(originalContent)
            val names = senderWxid?.let(::queryContactNames)
            return firstNonBlank(names?.first, cleanHint, names?.second, names?.third) ?: "对方"
        }
        val names = queryContactNames(talker)
        return firstNonBlank(names?.first, names?.second, cleanHint, names?.third) ?: "对方"
    }

    fun queryContactNames(username: String): Triple<String?, String?, String?>? = runCatching {
        val cursor = WcdbBridge.rawQuery(
            "SELECT conRemark, nickname, alias FROM rcontact WHERE username = ? LIMIT 1",
            arrayOf(username),
        ) ?: return@runCatching null
        cursor.use {
            if (!it.moveToFirst()) null
            else Triple(it.getString(0), it.getString(1), it.getString(2))
        }
    }.getOrNull()

    /**
     * Queries added personal contacts (excluding chatrooms, official accounts, and built-in system entries).
     */
    fun queryFriends(limit: Int = 1000): List<ContactSummary> = runCatching {
        val cursor = WcdbBridge.rawQuery(
            "SELECT username, conRemark, nickname, alias FROM rcontact " +
                "WHERE (type & 1) != 0 AND verifyFlag = 0 " +
                "AND username NOT LIKE '%@chatroom' " +
                "AND username NOT LIKE 'gh_%' " +
                "AND username NOT LIKE '%@app' " +
                "AND username NOT LIKE '%@stranger' " +
                "ORDER BY CASE WHEN conRemark IS NOT NULL AND conRemark != '' THEN conRemark ELSE nickname END COLLATE NOCASE ASC " +
                "LIMIT ?",
            arrayOf(limit.toString()),
        ) ?: return@runCatching emptyList()

        val result = mutableListOf<ContactSummary>()
        cursor.use { c ->
            while (c.moveToNext()) {
                val username = c.getString(0)?.trim().orEmpty()
                if (username.isEmpty() || username in systemUsernames) continue
                val remark = c.getString(1)?.trim().orEmpty()
                val nickname = c.getString(2)?.trim().orEmpty()
                val alias = c.getString(3)?.trim().orEmpty()
                val display = firstNonBlank(remark, nickname, alias, username) ?: username
                result += ContactSummary(username, display, nickname, remark, alias)
            }
        }
        result
    }.getOrDefault(emptyList())

    /**
     * Resolves a friend's locally cached WeChat avatar image file and MIME type, or null if absent.
     */
    fun findCachedAvatar(username: String): Pair<File, String>? = runCatching {
        if (username.isBlank()) return@runCatching null
        val root = resolveAvatarDir() ?: return@runCatching null
        val md5 = md5Hex(username)
        val subDir = File(root, "${md5.substring(0, 2)}/${md5.substring(2, 4)}")
        if (!subDir.isDirectory) return@runCatching null

        val directCandidates = listOf(
            File(subDir, "user_$md5.png"),
            File(subDir, "user_hd_$md5.png"),
        )
        for (file in directCandidates) {
            detectImageMime(file)?.let { return@runCatching file to it }
        }

        val innerDir = File(subDir, md5)
        if (innerDir.isDirectory) {
            val innerFiles = innerDir.listFiles()
                ?.filter { it.isFile && (it.name.startsWith("small_") || it.name.startsWith("hd_")) }
                ?.sortedByDescending { it.lastModified() }
                .orEmpty()
            for (file in innerFiles) {
                detectImageMime(file)?.let { return@runCatching file to it }
            }
        }
        null
    }.getOrNull()

    private fun resolveAvatarDir(): File? {
        WcdbBridge.mainDbPath()?.let { dbPath ->
            val dir = File(dbPath).parentFile?.let { File(it, "avatar") }
            if (dir != null && dir.isDirectory) return dir
        }
        val mmRoot = HostContext.app.filesDir.parentFile?.let { File(it, "MicroMsg") } ?: return null
        return mmRoot.listFiles()
            ?.firstOrNull { it.isDirectory && it.name.length == 32 && File(it, "avatar").isDirectory }
            ?.let { File(it, "avatar") }
    }

    private fun detectImageMime(file: File): String? {
        if (!file.isFile || !file.canRead() || file.length() < 12) return null
        val hdr = ByteArray(12)
        val read = FileInputStream(file).use { it.read(hdr) }
        if (read < 4) return null
        val b0 = hdr[0].toInt() and 0xFF
        val b1 = hdr[1].toInt() and 0xFF
        val b2 = hdr[2].toInt() and 0xFF
        val b3 = hdr[3].toInt() and 0xFF
        return when {
            b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47 -> "image/png"
            b0 == 0xFF && b1 == 0xD8 -> "image/jpeg"
            b0 == 0x52 && b1 == 0x49 && b2 == 0x46 && b3 == 0x46 -> "image/webp"
            else -> null
        }
    }

    private fun md5Hex(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(32)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            if (v < 16) sb.append('0')
            sb.append(v.toString(16))
        }
        return sb.toString()
    }

    private fun extractGroupSenderWxid(content: String?): String? {
        if (content.isNullOrEmpty()) return null
        val idx = content.indexOf(":\n")
        if (idx !in 1..64) return null
        val prefix = content.substring(0, idx).trim()
        if (prefix.isEmpty() || prefix.any { it.isWhitespace() }) return null
        return prefix
    }

    private fun firstNonBlank(vararg candidates: String?): String? =
        candidates.firstOrNull { !it.isNullOrBlank() }?.trim()
}
