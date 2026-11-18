package com.acid.wechat.agent

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared timeline, compaction, pagination, and AI-sent deduplication models for per-chat Agent sessions.
 */
object AgentSessionModels {
    const val GLOBAL_TALKER = "__global__"
    const val PAGE_SIZE = 20
    const val INITIAL_DB_SEED_COUNT = 15
    const val KEEP_RECENT_ON_COMPACT = 10

    data class TimelineItem(
        val id: String,
        val kind: String, // "peer", "master", "agent", "thinking", "tool", "memory", "error"
        val senderName: String = "",
        val timestampMs: Long = System.currentTimeMillis(),
        val timeText: String = "",
        var text: String = "",
        var compacted: Boolean = false,
        val sourceMsgId: Long = 0L,
        val sourceMsgSvrId: Long = 0L,
        var toolName: String = "",
        var toolSummary: String = "",
        var toolArgs: String = "",
        var toolResult: String = "",
        var running: Boolean = false,
        var isError: Boolean = false,
    ) {
        fun isConversationalBubble(): Boolean = kind == "peer" || kind == "master" || kind == "agent"

        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("kind", kind)
            put("senderName", senderName)
            put("timestampMs", timestampMs)
            put("timeText", timeText)
            put("text", text)
            put("compacted", compacted)
            put("sourceMsgId", sourceMsgId)
            put("sourceMsgSvrId", sourceMsgSvrId)
            put("toolName", toolName)
            put("toolSummary", toolSummary)
            put("toolArgs", toolArgs)
            put("toolResult", toolResult)
            put("running", running)
            put("isError", isError)
        }

        companion object {
            fun fromJson(obj: JSONObject): TimelineItem = TimelineItem(
                id = obj.optString("id", ""),
                kind = obj.optString("kind", "agent"),
                senderName = obj.optString("senderName", ""),
                timestampMs = obj.optLong("timestampMs", 0L),
                timeText = obj.optString("timeText", ""),
                text = obj.optString("text", ""),
                compacted = obj.optBoolean("compacted", false),
                sourceMsgId = obj.optLong("sourceMsgId", 0L),
                sourceMsgSvrId = obj.optLong("sourceMsgSvrId", 0L),
                toolName = obj.optString("toolName", ""),
                toolSummary = obj.optString("toolSummary", ""),
                toolArgs = obj.optString("toolArgs", ""),
                toolResult = obj.optString("toolResult", ""),
                running = false,
                isError = obj.optBoolean("isError", false),
            )
        }
    }

    data class SessionPage(
        val talker: String,
        val title: String,
        val items: List<TimelineItem>,
        val startIndex: Int,
        val totalCount: Int,
        val hasMore: Boolean,
        val compactCount: Int,
        val memorySummary: String,
    ) {
        fun itemsJsonArray(): JSONArray = JSONArray().apply {
            for (it in items) put(it.toJson())
        }
    }

    fun formatBubbleTime(ms: Long): String {
        val target = if (ms > 0L) ms else System.currentTimeMillis()
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(target))
    }

    fun formatContextTime(ms: Long): String {
        val target = if (ms > 0L) ms else System.currentTimeMillis()
        return SimpleDateFormat("MM月dd日 HH:mm", Locale.getDefault()).format(Date(target))
    }

    private val atAiTagRegex = Regex("(?<![a-zA-Z0-9_])@ai(?![a-zA-Z])", RegexOption.IGNORE_CASE)

    fun containsAtAi(raw: String): Boolean = atAiTagRegex.containsMatchIn(raw)

    /** Removes `@ai` trigger tokens before passing conversation text to the LLM or compactor. */
    fun stripAtAiForModel(raw: String): String =
        atAiTagRegex.replace(raw, " ")
            .replace(Regex("[ \\t]{2,}"), " ")
            .replace(Regex("\\s*\n\\s*"), "\n")
            .trim()
}

/**
 * Tracks recent outgoing messages sent by the Agent itself so `MsgInfoStorage.insert`
 * attributes them correctly instead of double-recording them as `master` input or re-triggering `@ai`.
 */
object AgentSentTracker {
    private const val TTL_MS = 30_000L
    private val recentByTalker = ConcurrentHashMap<String, MutableList<Pair<String, Long>>>()

    fun markSent(talker: String, text: String) {
        val norm = text.trim()
        if (talker.isBlank() || norm.isEmpty()) return
        val now = System.currentTimeMillis()
        val list = recentByTalker.getOrPut(talker) { mutableListOf() }
        synchronized(list) {
            list.removeAll { now - it.second > TTL_MS }
            list.add(norm to now)
        }
    }

    fun consumeIfSentByAgent(talker: String, text: String): Boolean {
        val norm = text.trim()
        if (talker.isBlank() || norm.isEmpty()) return false
        val list = recentByTalker[talker] ?: return false
        val now = System.currentTimeMillis()
        synchronized(list) {
            list.removeAll { now - it.second > TTL_MS }
            val idx = list.indexOfFirst { it.first == norm }
            if (idx >= 0) {
                list.removeAt(idx)
                return true
            }
        }
        return false
    }
}
