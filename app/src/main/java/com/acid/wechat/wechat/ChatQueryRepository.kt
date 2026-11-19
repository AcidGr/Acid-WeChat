package com.acid.wechat.wechat

import com.acid.wechat.core.AcidLogger
import com.acid.wechat.core.WcdbBridge
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Rich WeChat conversation, message, and fuzzy contact search queries over `EnMicroMsg.db`.
 */
object ChatQueryRepository {
    private const val TAG = "ChatQueryRepo"

    data class ConversationSummary(
        val username: String,
        val displayName: String,
        val isChatroom: Boolean,
        val unreadCount: Int,
        val lastTimeText: String,
        val lastContent: String,
    )

    data class MessageSummary(
        val msgId: Long,
        val msgSvrId: Long,
        val talker: String,
        val isSend: Boolean,
        val senderName: String,
        val type: Int,
        val typeLabel: String,
        val timeText: String,
        val content: String,
        val createTimeMs: Long = 0L,
        val senderWxid: String? = null,
    )

    sealed class TalkerResolution {
        data class Resolved(val username: String, val displayName: String) : TalkerResolution()
        data class Ambiguous(val candidates: List<ChatRepository.ContactSummary>) : TalkerResolution()
        data object NotFound : TalkerResolution()
    }

    fun searchContacts(
        query: String = "",
        includeChatrooms: Boolean = true,
        limit: Int = 30,
    ): List<ChatRepository.ContactSummary> = runCatching {
        val capped = limit.coerceIn(1, 100)
        val q = query.trim().lowercase(Locale.ROOT)
        val sql = buildString {
            append("SELECT username, conRemark, nickname, alias FROM rcontact WHERE (type & 1) != 0 ")
            if (!includeChatrooms) append("AND username NOT LIKE '%@chatroom' ")
            append("AND username NOT LIKE 'gh_%' AND username NOT LIKE '%@app' AND username NOT LIKE '%@stranger' ")
            append("ORDER BY CASE WHEN conRemark IS NOT NULL AND conRemark != '' THEN conRemark ELSE nickname END COLLATE NOCASE ASC ")
            append("LIMIT 1200")
        }
        val cursor = WcdbBridge.rawQuery(sql, emptyArray()) ?: return@runCatching emptyList()
        val exact = mutableListOf<ChatRepository.ContactSummary>()
        val partial = mutableListOf<ChatRepository.ContactSummary>()
        cursor.use { c ->
            while (c.moveToNext()) {
                val u = c.getString(0)?.trim().orEmpty()
                if (u.isEmpty()) continue
                val remark = c.getString(1)?.trim().orEmpty()
                val nickname = c.getString(2)?.trim().orEmpty()
                val alias = c.getString(3)?.trim().orEmpty()
                val display = remark.ifEmpty { nickname.ifEmpty { alias.ifEmpty { u } } }
                val summary = ChatRepository.ContactSummary(u, display, nickname, remark, alias)
                if (q.isEmpty()) {
                    partial += summary
                } else {
                    val fields = listOf(u, remark, nickname, alias).map { it.lowercase(Locale.ROOT) }
                    when {
                        fields.any { it == q } -> exact += summary
                        fields.any { it.contains(q) } -> partial += summary
                    }
                }
            }
        }
        (exact + partial).take(capped)
    }.onFailure {
        AcidLogger.w(TAG, "searchContacts failed", it)
    }.getOrDefault(emptyList())

    fun resolveTalker(talkerOrQuery: String): TalkerResolution {
        val raw = talkerOrQuery.trim()
        if (raw.isEmpty()) return TalkerResolution.NotFound
        if (raw == "filehelper" || raw.endsWith("@chatroom") || raw.startsWith("wxid_")) {
            val names = ChatRepository.queryContactNames(raw)
            val display = names?.first?.takeIf { it.isNotBlank() }
                ?: names?.second?.takeIf { it.isNotBlank() }
                ?: if (raw == "filehelper") "文件传输助手" else raw
            return TalkerResolution.Resolved(raw, display)
        }
        val matches = searchContacts(raw, includeChatrooms = true, limit = 10)
        if (matches.isEmpty()) return TalkerResolution.NotFound
        val exact = matches.filter {
            it.username.equals(raw, true) ||
                it.remark.equals(raw, true) ||
                it.nickname.equals(raw, true) ||
                it.alias.equals(raw, true)
        }
        if (exact.size == 1) {
            return TalkerResolution.Resolved(exact[0].username, exact[0].displayName)
        }
        if (matches.size == 1) {
            return TalkerResolution.Resolved(matches[0].username, matches[0].displayName)
        }
        return TalkerResolution.Ambiguous(if (exact.isNotEmpty()) exact else matches)
    }

    fun queryRecentConversations(limit: Int = 25): List<ConversationSummary> = runCatching {
        val capped = limit.coerceIn(1, 100)
        val cursor = WcdbBridge.rawQuery(
            "SELECT username, unReadCount, conversationTime, content FROM rconversation " +
                "WHERE conversationTime > 0 ORDER BY conversationTime DESC LIMIT ?",
            arrayOf(capped.toString()),
        ) ?: return@runCatching emptyList()

        val result = mutableListOf<ConversationSummary>()
        cursor.use { c ->
            while (c.moveToNext()) {
                val u = c.getString(0)?.trim().orEmpty()
                if (u.isEmpty()) continue
                val unread = c.getInt(1)
                val ts = c.getLong(2)
                val rawContent = c.getString(3).orEmpty()
                val display = if (u == "filehelper") "文件传输助手" else ChatRepository.resolveSenderDisplayName(u, null, null)
                result += ConversationSummary(
                    username = u,
                    displayName = display,
                    isChatroom = u.endsWith("@chatroom"),
                    unreadCount = unread,
                    lastTimeText = formatTime(ts),
                    lastContent = cleanPreview(rawContent),
                )
            }
        }
        result
    }.onFailure {
        AcidLogger.w(TAG, "queryRecentConversations failed", it)
    }.getOrDefault(emptyList())

    fun queryMessages(
        talker: String,
        limit: Int = 30,
        offset: Int = 0,
        keyword: String? = null,
    ): List<MessageSummary> = runCatching {
        val capped = limit.coerceIn(1, 100)
        val off = offset.coerceAtLeast(0)
        val kw = keyword?.trim().orEmpty()
        val sql: String
        val args: Array<String>
        if (kw.isNotEmpty()) {
            sql = "SELECT msgId, msgSvrId, type, isSend, createTime, content, imgPath FROM message " +
                "WHERE talker = ? AND content LIKE ? ORDER BY createTime DESC LIMIT ? OFFSET ?"
            args = arrayOf(talker, "%$kw%", capped.toString(), off.toString())
        } else {
            sql = "SELECT msgId, msgSvrId, type, isSend, createTime, content, imgPath FROM message " +
                "WHERE talker = ? ORDER BY createTime DESC LIMIT ? OFFSET ?"
            args = arrayOf(talker, capped.toString(), off.toString())
        }
        val cursor = WcdbBridge.rawQuery(sql, args) ?: return@runCatching emptyList()
        val rows = mutableListOf<MessageSummary>()
        cursor.use { c ->
            while (c.moveToNext()) {
                val msgId = c.getLong(0)
                val msgSvrId = c.getLong(1)
                val type = c.getInt(2)
                val isSend = c.getInt(3) != 0
                val ts = c.getLong(4)
                val raw = c.getString(5).orEmpty()
                val imgPath = c.getString(6).orEmpty()
                val senderWxid = when {
                    isSend -> ChatRepository.querySelfWxid()
                    talker.endsWith("@chatroom") -> ChatRepository.extractGroupSenderWxid(raw)
                    else -> talker
                }
                val sender = if (isSend) "master" else ChatRepository.resolveSenderDisplayName(talker, raw, null)
                val body = WeChatMessageFormatter.formatMessageBody(talker, isSend, type, raw, msgId, imgPath, msgSvrId)
                rows += MessageSummary(
                    msgId = msgId,
                    msgSvrId = msgSvrId,
                    talker = talker,
                    isSend = isSend,
                    senderName = sender,
                    type = type,
                    typeLabel = WeChatMessageFormatter.msgTypeLabel(type),
                    timeText = formatTime(ts),
                    content = body,
                    createTimeMs = ts,
                    senderWxid = senderWxid,
                )
            }
        }
        rows.reversed()
    }.onFailure {
        AcidLogger.w(TAG, "queryMessages failed", it)
    }.getOrDefault(emptyList())

    fun formatMessageBody(
        talker: String,
        isSend: Boolean,
        type: Int,
        raw: String,
        msgId: Long = 0L,
        imgPath: String = "",
        msgSvrId: Long = 0L,
    ): String = WeChatMessageFormatter.formatMessageBody(talker, isSend, type, raw, msgId, imgPath, msgSvrId)

    private fun cleanPreview(raw: String): String =
        raw.replace('\n', ' ').trim().take(120)

    private fun formatTime(tsMs: Long): String {
        if (tsMs <= 0L) return ""
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return fmt.format(Date(tsMs))
    }
}
