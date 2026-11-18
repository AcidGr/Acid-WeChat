package com.acid.wechat.wechat

import android.content.ContentValues
import com.acid.wechat.core.WcdbBridge

/**
 * Read-only WeChat chat/contact repository backed by [WcdbBridge] (`EnMicroMsg.db`).
 * Keeps raw SQL table/column details out of individual features.
 */
object ChatRepository {
    private val genericPlaceholders = setOf("对方", "You", "you")

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
