package com.acid.wechat.agent

import com.acid.wechat.agent.AgentSessionModels.GLOBAL_TALKER
import com.acid.wechat.agent.AgentSessionModels.INITIAL_DB_SEED_COUNT
import com.acid.wechat.agent.AgentSessionModels.KEEP_RECENT_ON_COMPACT
import com.acid.wechat.agent.AgentSessionModels.PAGE_SIZE
import com.acid.wechat.agent.AgentSessionModels.SessionPage
import com.acid.wechat.agent.AgentSessionModels.TimelineItem
import com.acid.wechat.agent.AgentSessionModels.formatBubbleTime
import com.acid.wechat.wechat.ChatImageRepository
import com.acid.wechat.wechat.ChatQueryRepository
import com.acid.wechat.wechat.ChatRepository
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Per-talker session cache, 15-message DB bootstrap, live message sync,
 * 20-item reverse pagination, and objective memory compaction with gold-border flags.
 */
object AgentSessionStore {
    class ChatSessionState(val talker: String, var title: String) {
        val timeline = mutableListOf<TimelineItem>()
        var memorySummary: String = ""
        var compactCount: Int = 0
        var lastTotalTokens: Int = 0
        var lastPromptTokens: Int = 0
        var dbSeeded: Boolean = false
        val idSeq = AtomicInteger(0)

        fun nextId(): String = "m_${idSeq.incrementAndGet()}_${System.nanoTime() and 0xFFFF}"
    }

    private val sessions = ConcurrentHashMap<String, ChatSessionState>()

    fun getOrLoad(talker: String, titleHint: String? = null): ChatSessionState {
        val existing = sessions[talker]
        if (existing != null) {
            if (!titleHint.isNullOrBlank()) existing.title = titleHint
            seedFromDbIfNeeded(existing)
            return existing
        }

        val resolvedTitle = titleHint?.takeIf { it.isNotBlank() }
            ?: if (talker == GLOBAL_TALKER) "全局助手" else ChatRepository.resolveChatTitle(talker)
        val loaded = AgentSessionDisk.load(talker, resolvedTitle) ?: ChatSessionState(talker, resolvedTitle)
        val target = sessions.putIfAbsent(talker, loaded) ?: loaded
        if (!titleHint.isNullOrBlank()) target.title = titleHint
        seedFromDbIfNeeded(target)
        return target
    }

    private fun seedFromDbIfNeeded(session: ChatSessionState) {
        if (session.talker == GLOBAL_TALKER) return
        val added = synchronized(session) {
            if (session.dbSeeded || session.timeline.isNotEmpty()) return@synchronized false
            session.dbSeeded = true
            val recent = ChatQueryRepository.queryMessages(session.talker, limit = INITIAL_DB_SEED_COUNT, offset = 0)
            for (m in recent) {
                val body = m.content.trim()
                if (body.isEmpty()) continue
                session.timeline += TimelineItem(
                    id = session.nextId(),
                    kind = if (m.isSend) "master" else "peer",
                    senderName = if (m.isSend) "master" else m.senderName,
                    timestampMs = m.createTimeMs,
                    timeText = formatBubbleTime(m.createTimeMs),
                    text = body,
                    sourceMsgId = m.msgId,
                    sourceMsgSvrId = m.msgSvrId,
                )
            }
            recent.isNotEmpty()
        }
        if (added) persist(session)
    }

    fun hasExistingSession(talker: String): Boolean =
        sessions.containsKey(talker) || AgentSessionDisk.hasFile(talker)

    fun ingestLiveMessageIfSessionActive(
        talker: String,
        msgId: Long,
        msgSvrId: Long,
        isSend: Boolean,
        senderName: String,
        body: String,
        timestampMs: Long,
    ): ChatSessionState? {
        if (body.isBlank() || !hasExistingSession(talker)) return null
        val session = getOrLoad(talker)
        if (isSend && AgentSentTracker.consumeIfSentByAgent(talker, body)) {
            return session
        }
        val changed = synchronized(session) {
            val dup = session.timeline.indexOfLast {
                (msgId > 0L && it.sourceMsgId == msgId) || (msgSvrId > 0L && it.sourceMsgSvrId == msgSvrId)
            }
            if (dup >= 0) {
                if (session.timeline[dup].text != body) {
                    session.timeline[dup].text = body
                    true
                } else {
                    false
                }
            } else {
                session.timeline += TimelineItem(
                    id = session.nextId(),
                    kind = if (isSend) "master" else "peer",
                    senderName = if (isSend) "master" else senderName,
                    timestampMs = timestampMs,
                    timeText = formatBubbleTime(timestampMs),
                    text = body,
                    sourceMsgId = msgId,
                    sourceMsgSvrId = msgSvrId,
                )
                true
            }
        }
        if (changed) persist(session)
        return session
    }

    fun updateVoiceTranscript(talker: String, msgId: Long, transcript: String): Boolean {
        val clean = transcript.trim()
        if (msgId <= 0L || clean.isEmpty()) return false
        return updateMediaItemText(talker, msgId, 0L, "[语音: $clean]") { cur ->
            cur == "[语音]" || !cur.startsWith("[语音:")
        }
    }

    fun updateImagePath(talker: String, msgId: Long, msgSvrId: Long, imagePath: String): Boolean {
        val clean = imagePath.trim()
        if (clean.isEmpty()) return false
        val formatted = "[图片: $clean]"
        return updateMediaItemText(talker, msgId, msgSvrId, formatted) { cur ->
            (cur == "[图片]" || cur.startsWith("[图片: ")) && cur != formatted
        }
    }

    private inline fun updateMediaItemText(
        talker: String,
        msgId: Long,
        msgSvrId: Long,
        nextText: String,
        shouldReplace: (String) -> Boolean,
    ): Boolean {
        if (talker.isBlank() || (msgId <= 0L && msgSvrId <= 0L) || !hasExistingSession(talker)) return false
        val session = getOrLoad(talker)
        val changed = synchronized(session) {
            var updated = false
            for (it in session.timeline) {
                val match = (msgId > 0L && it.sourceMsgId == msgId) || (msgSvrId > 0L && it.sourceMsgSvrId == msgSvrId)
                if (match && shouldReplace(it.text)) {
                    it.text = nextText
                    updated = true
                }
            }
            updated
        }
        if (changed) persist(session)
        return changed
    }

    private fun refreshRecentMediaPlaceholders(session: ChatSessionState) {
        val updated = synchronized(session) {
            var changed = false
            for (it in session.timeline.takeLast(25)) {
                val refreshed = resolveUpdatedMediaText(it)
                if (refreshed != null && refreshed != it.text) {
                    it.text = refreshed
                    changed = true
                }
            }
            changed
        }
        if (updated) persist(session)
    }

    private fun resolveUpdatedMediaText(it: TimelineItem): String? = when {
        it.sourceMsgId > 0L && it.text == "[语音]" ->
            ChatRepository.queryVoiceTranscript(it.sourceMsgId, "")?.trim()?.takeIf { t -> t.isNotEmpty() }?.let { "[语音: $it]" }
        (it.sourceMsgId > 0L || it.sourceMsgSvrId > 0L) && (it.text == "[图片]" || it.text.startsWith("[图片: ")) ->
            ChatImageRepository.resolveMessageImagePath(it.sourceMsgId, it.sourceMsgSvrId, "")?.let { "[图片: $it]" }
        else -> null
    }

    fun snapshotWindow(talker: String, startIndexHint: Int? = null, titleHint: String? = null): SessionPage {
        val session = getOrLoad(talker, titleHint)
        refreshRecentMediaPlaceholders(session)
        synchronized(session) {
            val total = session.timeline.size
            val defaultStart = (total - PAGE_SIZE).coerceAtLeast(0)
            val start = (startIndexHint ?: defaultStart).coerceIn(0, defaultStart.coerceAtLeast(0))
            val slice = if (start < total) session.timeline.subList(start, total).map { it.copy() } else emptyList()
            return SessionPage(
                talker = session.talker,
                title = session.title,
                items = slice,
                startIndex = start,
                totalCount = total,
                hasMore = start > 0,
                compactCount = session.compactCount,
                memorySummary = session.memorySummary,
            )
        }
    }

    fun loadOlderPage(talker: String, beforeIndex: Int, limit: Int = PAGE_SIZE): SessionPage {
        val session = getOrLoad(talker)
        synchronized(session) {
            val total = session.timeline.size
            val end = beforeIndex.coerceIn(0, total)
            val start = (end - limit.coerceAtLeast(1)).coerceAtLeast(0)
            val slice = if (start < end) session.timeline.subList(start, end).map { it.copy() } else emptyList()
            return SessionPage(
                talker = session.talker,
                title = session.title,
                items = slice,
                startIndex = start,
                totalCount = total,
                hasMore = start > 0,
                compactCount = session.compactCount,
                memorySummary = session.memorySummary,
            )
        }
    }

    fun snapshotForPrompt(talker: String): Triple<String, String, List<TimelineItem>> {
        val session = getOrLoad(talker)
        refreshRecentMediaPlaceholders(session)
        synchronized(session) {
            return Triple(session.title, session.memorySummary, session.timeline.map { it.copy() })
        }
    }

    fun shouldAutoCompact(talker: String): Boolean {
        val session = sessions[talker] ?: return false
        synchronized(session) {
            val activeTurns = session.timeline.count { !it.compacted && it.isConversationalBubble() }
            if (session.lastPromptTokens >= AgentMemoryCompactor.AUTO_COMPACT_PROMPT_TOKENS) {
                return activeTurns > KEEP_RECENT_ON_COMPACT
            }
            return activeTurns >= AgentMemoryCompactor.AUTO_COMPACT_UNCOMPACTED_TURNS
        }
    }

    fun compactSession(talker: String, cfg: AgentConfigStore.ModelConfig): AgentMemoryCompactor.CompactOutcome {
        val session = getOrLoad(talker)
        val (priorSummary, candidateItems, cutoffPos) = synchronized(session) {
            val chatIndices = session.timeline.indices.filter { session.timeline[it].isConversationalBubble() }
            if (chatIndices.size <= KEEP_RECENT_ON_COMPACT) {
                return@synchronized Triple(session.memorySummary, emptyList<TimelineItem>(), -1)
            }
            val boundaryIdx = chatIndices[chatIndices.size - KEEP_RECENT_ON_COMPACT]
            val toCompact = session.timeline.subList(0, boundaryIdx)
                .filter { !it.compacted && (it.isConversationalBubble() || it.kind == "tool") }
                .map { it.copy() }
            Triple(session.memorySummary, toCompact, boundaryIdx)
        }

        val outcome = AgentMemoryCompactor.compact(cfg, priorSummary, candidateItems)
        if (!outcome.ok) return outcome

        synchronized(session) {
            val boundary = cutoffPos.coerceIn(0, session.timeline.size)
            for (i in 0 until boundary) {
                val it = session.timeline[i]
                if (it.kind != "memory") it.compacted = true
            }
            session.timeline.removeAll { it.kind == "memory" }
            val insertAt = session.timeline.indexOfFirst { !it.compacted && it.isConversationalBubble() }
                .let { if (it >= 0) it else session.timeline.size }
            session.compactCount += 1
            session.memorySummary = outcome.summary
            val now = System.currentTimeMillis()
            session.timeline.add(
                insertAt,
                TimelineItem(
                    id = session.nextId(),
                    kind = "memory",
                    senderName = "长期记忆快照 · 第 ${session.compactCount} 次压缩",
                    timestampMs = now,
                    timeText = formatBubbleTime(now),
                    text = outcome.summary,
                    compacted = true,
                ),
            )
            session.lastPromptTokens = 0
        }
        persist(session)
        return outcome
    }

    fun clearSession(talker: String): ChatSessionState {
        val session = getOrLoad(talker)
        synchronized(session) {
            session.timeline.clear()
            session.memorySummary = ""
            session.compactCount = 0
            session.lastTotalTokens = 0
            session.lastPromptTokens = 0
            session.dbSeeded = true
        }
        AgentSessionDisk.delete(talker)
        return session
    }

    fun persist(session: ChatSessionState) = AgentSessionDisk.persist(session)
}
