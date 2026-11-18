package com.acid.wechat.agent

import com.acid.wechat.agent.AgentContextBuilder.InvocationMode
import com.acid.wechat.agent.AgentSessionModels.TimelineItem
import com.acid.wechat.agent.AgentSessionModels.formatBubbleTime
import com.acid.wechat.agent.AgentSessionStore.ChatSessionState
import com.acid.wechat.core.AcidLogger
import com.acid.wechat.wechat.WeChatSender
import java.net.HttpURLConnection
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Public coordinator for per-chat Agent sessions (`talker`), worker threads, and listeners.
 */
object AcidAgentLoop {
    private const val TAG = "AcidAgentLoop"

    interface Listener {
        fun onStateChanged(talker: String, running: Boolean, step: Int, totalTokens: Int, compactCount: Int)
        fun onTimelineChanged(talker: String)
    }

    internal class RunState {
        val runGen = AtomicInteger(0)
        @Volatile var isRunning = false
        @Volatile var currentStep = 0
        @Volatile var stopRequested = false
        @Volatile var activeConn: HttpURLConnection? = null
    }

    private val runStates = ConcurrentHashMap<String, RunState>()
    private val listeners = CopyOnWriteArrayList<Listener>()

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    fun stateSnapshot(talker: String): Triple<Boolean, Int, Int> {
        val rs = runStates[talker]
        val session = AgentSessionStore.getOrLoad(talker)
        return Triple(rs?.isRunning ?: false, rs?.currentStep ?: 0, session.lastTotalTokens)
    }

    fun isRunning(talker: String): Boolean = runStates[talker]?.isRunning == true

    fun stop(talker: String) {
        val rs = runStates.getOrPut(talker) { RunState() }
        rs.stopRequested = true
        rs.runGen.incrementAndGet()
        runCatching { rs.activeConn?.disconnect() }
        rs.activeConn = null
        rs.isRunning = false
        val session = AgentSessionStore.getOrLoad(talker)
        synchronized(session) {
            for (it in session.timeline) {
                if (it.running) {
                    it.running = false
                    if (it.kind == "tool" && it.toolResult == "执行中...") it.toolResult = "[已停止]"
                }
            }
        }
        AgentSessionStore.persist(session)
        notifyTimeline(talker)
        notifyState(talker)
    }

    fun clearSession(talker: String) {
        stop(talker)
        AgentSessionStore.clearSession(talker)
        notifyTimeline(talker)
        notifyState(talker)
    }

    fun notifyExternalTimelineUpdate(talker: String) = notifyTimeline(talker)

    fun submitPanelPrompt(talker: String, titleHint: String?, prompt: String) {
        val text = prompt.trim()
        if (text.isEmpty() || isRunning(talker)) return
        AgentConfigStore.ensureInitialized()
        val cfg = AgentConfigStore.getConfig()
        val session = AgentSessionStore.getOrLoad(talker, titleHint)
        val now = System.currentTimeMillis()
        synchronized(session) {
            session.timeline += TimelineItem(
                id = session.nextId(),
                kind = "master",
                senderName = "master",
                timestampMs = now,
                timeText = formatBubbleTime(now),
                text = text,
            )
        }
        if (!cfg.hasApiKey) {
            AgentLoopRunner.appendError(
                session,
                "未配置 API Key：请在右上角快速填入，或编辑 ${AgentConfigStore.modelsFile().absolutePath}",
            )
            AgentSessionStore.persist(session)
            notifyTimeline(talker)
            return
        }
        startWorker(talker, session, cfg, InvocationMode.PANEL_MASTER, sendReplyToWeChat = false)
    }

    fun submitChatTrigger(talker: String, isMaster: Boolean) {
        AgentConfigStore.ensureInitialized()
        val cfg = AgentConfigStore.getConfig()
        if (!cfg.hasApiKey) {
            AcidLogger.w(TAG, "skipping @ trigger for $talker: API key not configured")
            return
        }
        val session = AgentSessionStore.getOrLoad(talker)
        val mode = if (isMaster) InvocationMode.CHAT_TRIGGER_MASTER else InvocationMode.CHAT_TRIGGER_GUEST
        startWorker(talker, session, cfg, mode, sendReplyToWeChat = true)
    }

    fun triggerManualCompact(talker: String): AgentMemoryCompactor.CompactOutcome {
        if (isRunning(talker)) return AgentMemoryCompactor.CompactOutcome(false, error = "Agent 正在运行中，请稍候再压缩")
        AgentConfigStore.ensureInitialized()
        val cfg = AgentConfigStore.getConfig()
        val outcome = AgentSessionStore.compactSession(talker, cfg)
        if (outcome.ok) {
            notifyTimeline(talker)
            notifyState(talker)
        }
        return outcome
    }

    fun sendTextToChat(talker: String, text: String): WeChatSender.SendResult {
        val clean = AgentContextBuilder.cleanForWeChat(text)
        if (clean.isEmpty()) return WeChatSender.SendResult(false, "内容为空")
        AgentSentTracker.markSent(talker, clean)
        val res = WeChatSender.sendText(talker, clean)
        if (!res.ok) AgentSentTracker.consumeIfSentByAgent(talker, clean)
        return res
    }

    private fun startWorker(
        talker: String,
        session: ChatSessionState,
        cfg: AgentConfigStore.ModelConfig,
        mode: InvocationMode,
        sendReplyToWeChat: Boolean,
    ) {
        val rs = runStates.getOrPut(talker) { RunState() }
        val gen = rs.runGen.incrementAndGet()
        rs.stopRequested = false
        rs.isRunning = true
        rs.currentStep = 0
        notifyTimeline(talker)
        notifyState(talker)

        Thread({
            AgentLoopRunner.execute(
                talker = talker,
                session = session,
                rs = rs,
                cfg = cfg,
                mode = mode,
                sendReplyToWeChat = sendReplyToWeChat,
                gen = gen,
                notifyTimeline = ::notifyTimeline,
                notifyState = ::notifyState,
            )
        }, "AcidAgent-${AgentConfigStore.sanitizeTalkerKey(talker).take(16)}").apply {
            isDaemon = true
            start()
        }
    }

    private fun notifyTimeline(talker: String) {
        for (l in listeners) runCatching { l.onTimelineChanged(talker) }
    }

    private fun notifyState(talker: String) {
        val rs = runStates[talker]
        val session = AgentSessionStore.getOrLoad(talker)
        val running = rs?.isRunning == true
        val step = rs?.currentStep ?: 0
        val tokens = session.lastTotalTokens
        val compactCount = session.compactCount
        for (l in listeners) {
            runCatching { l.onStateChanged(talker, running, step, tokens, compactCount) }
        }
    }
}
