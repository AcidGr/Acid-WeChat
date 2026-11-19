package com.acid.wechat.agent

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

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
        var chatVisible: Boolean = false,
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
            put("chatVisible", chatVisible)
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
            fun fromJson(obj: JSONObject): TimelineItem {
                val kind = obj.optString("kind", "agent")
                val srcId = obj.optLong("sourceMsgId", 0L)
                val srcSvrId = obj.optLong("sourceMsgSvrId", 0L)
                val defaultVisible = when (kind) {
                    "peer" -> true
                    "master", "agent" -> srcId > 0L || srcSvrId > 0L
                    else -> false
                }
                return TimelineItem(
                    id = obj.optString("id", ""),
                    kind = kind,
                    senderName = obj.optString("senderName", ""),
                    timestampMs = obj.optLong("timestampMs", 0L),
                    timeText = obj.optString("timeText", ""),
                    text = obj.optString("text", ""),
                    compacted = obj.optBoolean("compacted", false),
                    chatVisible = if (obj.has("chatVisible")) obj.optBoolean("chatVisible", defaultVisible) else defaultVisible,
                    sourceMsgId = srcId,
                    sourceMsgSvrId = srcSvrId,
                    toolName = obj.optString("toolName", ""),
                    toolSummary = obj.optString("toolSummary", ""),
                    toolArgs = obj.optString("toolArgs", ""),
                    toolResult = obj.optString("toolResult", ""),
                    running = false,
                    isError = obj.optBoolean("isError", false),
                )
            }
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

    private val wakeRegexLock = Any()
    @Volatile private var cachedWakeToken: String = ""
    @Volatile private var cachedWakeRegex: Regex? = null
    @Volatile private var cachedLeadingWakeRegex: Regex? = null
    private val multiSpaceRegex = Regex("[ \\t\\u2005\\u00A0]{2,}")
    private val lineSpaceRegex = Regex("[ \\t\\u2005\\u00A0]*\\n[ \\t\\u2005\\u00A0]*")
    private val trailingBracketSpaceRegex = Regex("[ \\t\\u2005\\u00A0]+\\]")

    private fun isAsciiWordChar(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_'

    private fun trimHorizontalAndStandard(raw: String): String =
        raw.trim { it.isWhitespace() || it == '\u2005' || it == '\u00A0' }

    private fun wakeRegexPair(wakeWord: String): Pair<Regex, Regex>? {
        val token = wakeWord.trim()
        if (token.isEmpty()) return null
        if (token == cachedWakeToken) {
            val w = cachedWakeRegex
            val l = cachedLeadingWakeRegex
            if (w != null && l != null) return w to l
        }
        synchronized(wakeRegexLock) {
            if (token == cachedWakeToken) {
                val w = cachedWakeRegex
                val l = cachedLeadingWakeRegex
                if (w != null && l != null) return w to l
            }
            val escaped = Regex.escape(token)
            val first = token.first()
            val last = token.last()
            val prefix = if (isAsciiWordChar(first) || first == '@' || first == '/' || first == '.') {
                "(?<![a-zA-Z0-9_])"
            } else {
                ""
            }
            val suffix = when {
                last in 'a'..'z' || last in 'A'..'Z' -> "(?![a-zA-Z])"
                last in '0'..'9' -> "(?![0-9])"
                else -> ""
            }
            val fullRegex = Regex("$prefix$escaped$suffix", RegexOption.IGNORE_CASE)
            val leadingRegex = Regex(
                "^[ \\t\\u2005\\u00A0]*$escaped$suffix[:：,， \\t\\u2005\\u00A0]*",
                setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
            )
            cachedWakeToken = token
            cachedWakeRegex = fullRegex
            cachedLeadingWakeRegex = leadingRegex
            return fullRegex to leadingRegex
        }
    }

    fun buildWakeWordRegex(wakeWord: String): Regex? = wakeRegexPair(wakeWord)?.first

    fun buildLeadingWakeWordRegex(wakeWord: String): Regex? = wakeRegexPair(wakeWord)?.second

    fun containsWakeWord(raw: String, wakeWord: String = AgentConfigStore.getConfig().wakeWord): Boolean =
        buildWakeWordRegex(wakeWord)?.containsMatchIn(raw) == true

    /**
     * Masks/removes configured wake-word trigger tokens before passing conversation text to the LLM or compactor.
     * Leaves text untouched when wake-word mode is disabled.
     */
    fun stripWakeWordForModel(raw: String, cfg: AgentConfigStore.ModelConfig = AgentConfigStore.getConfig()): String {
        if (!cfg.wakeWordEnabled) return trimHorizontalAndStandard(raw)
        return stripWakeWord(raw, cfg.wakeWord)
    }

    fun stripWakeWord(raw: String, wakeWord: String): String {
        val (regex, leadingRegex) = wakeRegexPair(wakeWord) ?: return trimHorizontalAndStandard(raw)
        val closeIdx = findQuotePrefixEnd(raw)
        if (closeIdx >= 0) {
            val quoteRaw = raw.substring(0, closeIdx + 1)
            val replyRaw = raw.substring(closeIdx + 1)
            val quoteClean = trimHorizontalAndStandard(
                lineSpaceRegex.replace(
                    multiSpaceRegex.replace(
                        trailingBracketSpaceRegex.replace(regex.replace(quoteRaw, " "), "]"),
                        " ",
                    ),
                    "\n",
                ),
            )
            val replyClean = stripWakeWordFromSegment(replyRaw, regex, leadingRegex)
            return if (replyClean.isNotEmpty()) "$quoteClean $replyClean" else quoteClean
        }
        return stripWakeWordFromSegment(raw, regex, leadingRegex)
    }

    private fun findQuotePrefixEnd(raw: String): Int {
        if (!raw.startsWith("[引用 ")) return -1
        var depth = 0
        for (i in raw.indices) {
            when (raw[i]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return raw.indexOf("] ")
    }

    private fun stripWakeWordFromSegment(raw: String, regex: Regex, leadingRegex: Regex): String {
        var text = trimHorizontalAndStandard(raw)
        if (text.isEmpty()) return ""
        for (i in 0 until 16) {
            val next = leadingRegex.replace(text, "")
            if (next == text) break
            text = next
        }
        text = regex.replace(text, " ")
        text = multiSpaceRegex.replace(text, " ")
        text = lineSpaceRegex.replace(text, "\n")
        return trimHorizontalAndStandard(text)
    }
}

/**
 * Tracks recent outgoing messages sent by the Agent itself so `MsgInfoStorage.insert`
 * attributes them correctly instead of double-recording them as `master` input or re-triggering wake-word replies.
 */
object AgentSentTracker {
    enum class MediaKind { IMAGE, FILE }

    private data class PendingMedia(
        val token: Long,
        val kind: MediaKind,
        val fileName: String,
        val timestampMs: Long,
    )

    private const val TTL_MS = 30_000L
    private val recentByTalker = ConcurrentHashMap<String, MutableList<Pair<String, Long>>>()
    private val mediaTokenSeq = AtomicLong(0L)
    private val recentMediaByTalker = ConcurrentHashMap<String, MutableList<PendingMedia>>()

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

    fun markSentMedia(talker: String, kind: MediaKind, fileName: String = ""): Long {
        if (talker.isBlank()) return 0L
        val now = System.currentTimeMillis()
        val token = mediaTokenSeq.incrementAndGet()
        val list = recentMediaByTalker.getOrPut(talker) { mutableListOf() }
        synchronized(list) {
            list.removeAll { now - it.timestampMs > TTL_MS }
            list.add(PendingMedia(token, kind, fileName.trim(), now))
        }
        return token
    }

    fun cancelSentMedia(talker: String, token: Long) {
        if (talker.isBlank() || token <= 0L) return
        val list = recentMediaByTalker[talker] ?: return
        synchronized(list) {
            list.removeAll { it.token == token }
        }
    }

    fun consumeMediaSentByAgent(talker: String, msgType: Int, formattedBody: String): Boolean {
        if (talker.isBlank()) return false
        val expectedKind = when {
            msgType == 3 -> MediaKind.IMAGE
            (msgType == 49 || (msgType > 0 && (msgType and 0xFF) == 49)) && formattedBody.startsWith("[文件:") ->
                MediaKind.FILE
            else -> return false
        }
        val list = recentMediaByTalker[talker] ?: return false
        val now = System.currentTimeMillis()
        synchronized(list) {
            list.removeAll { now - it.timestampMs > TTL_MS }
            if (expectedKind == MediaKind.FILE) {
                val bodyTitle = formattedBody.removePrefix("[文件:").removeSuffix("]").trim()
                if (bodyTitle.isNotEmpty()) {
                    val exactIdx = list.indexOfFirst {
                        it.kind == MediaKind.FILE && it.fileName.equals(bodyTitle, ignoreCase = true)
                    }
                    if (exactIdx >= 0) {
                        list.removeAt(exactIdx)
                        return true
                    }
                }
            }
            val fallbackIdx = list.indexOfFirst { it.kind == expectedKind }
            if (fallbackIdx >= 0) {
                list.removeAt(fallbackIdx)
                return true
            }
        }
        return false
    }
}
