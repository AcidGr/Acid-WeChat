package com.acid.wechat.agent

import com.acid.wechat.agent.AcidAgentLoop.RunState
import com.acid.wechat.agent.AgentContextBuilder.InvocationMode
import com.acid.wechat.agent.AgentSessionModels.TimelineItem
import com.acid.wechat.agent.AgentSessionModels.formatBubbleTime
import com.acid.wechat.agent.AgentSessionStore.ChatSessionState
import com.acid.wechat.core.AcidLogger
import com.acid.wechat.wechat.WeChatSender
import org.json.JSONArray
import org.json.JSONObject

/**
 * Executes a single ReAct turn chain for one `talker`, streaming reasoning/reply chunks
 * and invoking tools under the caller's [ToolPolicy].
 */
internal object AgentLoopRunner {
    private const val TAG = "AgentLoopRunner"

    fun execute(
        talker: String,
        session: ChatSessionState,
        rs: RunState,
        cfg: AgentConfigStore.ModelConfig,
        mode: InvocationMode,
        sendReplyToWeChat: Boolean,
        gen: Int,
        notifyTimeline: (String) -> Unit,
        notifyState: (String) -> Unit,
    ) {
        val isAdmin = mode != InvocationMode.CHAT_TRIGGER_GUEST
        val (baseTitle, baseMemSummary, baseTimeline) = AgentSessionStore.snapshotForPrompt(talker)
        val allowedImages = if (isAdmin) emptySet() else collectTimelineImagePaths(baseTimeline)
        val policy = ToolPolicy(
            isAdmin = isAdmin,
            currentTalker = talker,
            allowedImagePaths = allowedImages,
            enabledTools = cfg.enabledTools,
        )
        val toolsSchema = AgentToolRegistry.buildToolsSchema(policy)
        val inFlightToolMsgs = mutableListOf<JSONObject>()
        var finalReplyText = ""

        try {
            for (step in 1..cfg.maxSteps) {
                if (isCancelled(rs, gen)) break
                rs.currentStep = step
                notifyState(talker)

                val reqMessages = AgentContextBuilder.buildMessages(
                    talker = talker,
                    title = baseTitle,
                    mode = mode,
                    memorySummary = baseMemSummary,
                    timeline = baseTimeline,
                    inFlightToolMessages = inFlightToolMsgs,
                    cfg = cfg,
                )

                var thinkItem: TimelineItem? = null
                var replyItem: TimelineItem? = null
                var lastPushMs = 0L

                val assistantMsg = AcidLlmClient.streamChatCompletion(
                    cfg = cfg,
                    messages = reqMessages,
                    toolsSchema = toolsSchema,
                    step = step,
                    cb = AcidLlmClient.StreamCallbacks(
                        isCancelled = { isCancelled(rs, gen) },
                        onConnectionOpened = { rs.activeConn = it },
                        onReasoningDelta = { full ->
                            val (item, pushedAt) = upsertStreamItem(
                                talker, session, thinkItem, "thinking", full, lastPushMs, notifyTimeline,
                            )
                            thinkItem = item
                            lastPushMs = pushedAt
                        },
                        onContentDelta = { full ->
                            thinkItem?.takeIf { it.running }?.let { it.running = false }
                            finalReplyText = full
                            val (item, pushedAt) = upsertStreamItem(
                                talker, session, replyItem, "agent", full, lastPushMs, notifyTimeline,
                            )
                            replyItem = item
                            lastPushMs = pushedAt
                        },
                        onUsage = { pt, _, tt ->
                            synchronized(session) {
                                if (pt > 0) session.lastPromptTokens = pt
                                if (tt > 0) session.lastTotalTokens = tt
                            }
                            notifyState(talker)
                        },
                    ),
                ) ?: break

                thinkItem?.running = false
                replyItem?.running = false
                val msgText = assistantMsg.optString("content", "").let { if (it == "null") "" else it }
                if (msgText.isNotBlank()) finalReplyText = msgText
                notifyTimeline(talker)

                val toolCalls = assistantMsg.optJSONArray("tool_calls")
                if (toolCalls == null || toolCalls.length() == 0) {
                    if (sendReplyToWeChat && replyItem != null && !isCancelled(rs, gen)) {
                        val cleaned = AgentContextBuilder.cleanForWeChat(finalReplyText)
                        if (cleaned.isNotEmpty()) {
                            replyItem!!.text = cleaned
                            AgentSentTracker.markSent(talker, cleaned)
                            val sendRes = WeChatSender.sendText(talker, cleaned)
                            if (!sendRes.ok) {
                                AgentSentTracker.consumeIfSentByAgent(talker, cleaned)
                                appendError(session, "发送微信回复失败: ${sendRes.detail}")
                            }
                        }
                    }
                    break
                }

                inFlightToolMsgs += assistantMsg
                executeToolBatch(talker, session, rs, policy, toolCalls, step, gen, inFlightToolMsgs, notifyTimeline)
            }
        } catch (t: Throwable) {
            if (!isCancelled(rs, gen)) {
                AcidLogger.w(TAG, "agent loop failed ($talker)", t)
                appendError(session, "请求异常: ${t.message}")
            }
        } finally {
            rs.activeConn = null
            if (rs.runGen.get() == gen) {
                rs.isRunning = false
                synchronized(session) {
                    session.timeline.forEach { if (it.running) it.running = false }
                }
                AgentSessionStore.persist(session)
                notifyTimeline(talker)
                notifyState(talker)
                if (AgentSessionStore.shouldAutoCompact(talker)) {
                    runCatching {
                        val cRes = AgentSessionStore.compactSession(talker, cfg)
                        if (cRes.ok) {
                            notifyTimeline(talker)
                            notifyState(talker)
                        }
                    }
                }
            }
        }
    }

    private fun executeToolBatch(
        talker: String,
        session: ChatSessionState,
        rs: RunState,
        policy: ToolPolicy,
        toolCalls: JSONArray,
        step: Int,
        gen: Int,
        inFlightToolMsgs: MutableList<JSONObject>,
        notifyTimeline: (String) -> Unit,
    ) {
        val pendingImages = mutableListOf<ImageAttachment>()
        for (i in 0 until toolCalls.length()) {
            if (isCancelled(rs, gen)) break
            val tc = toolCalls.optJSONObject(i) ?: continue
            val callId = tc.optString("id", "call_${step}_$i")
            val fn = tc.optJSONObject("function")
            val toolName = fn?.optString("name", "") ?: ""
            val rawArgs = fn?.optString("arguments", "{}") ?: "{}"

            val parsedArgs = runCatching { if (rawArgs.isBlank()) JSONObject() else JSONObject(rawArgs) }
            val summary = parsedArgs.getOrNull()?.let { summarizeTool(toolName, it) } ?: "$toolName · invalid JSON"
            val now = System.currentTimeMillis()
            val toolItem = TimelineItem(
                id = session.nextId(),
                kind = "tool",
                senderName = "AI",
                timestampMs = now,
                timeText = formatBubbleTime(now),
                toolName = toolName,
                toolSummary = summary,
                toolArgs = parsedArgs.getOrNull()?.toString(2) ?: rawArgs,
                toolResult = "执行中...",
                running = true,
            )
            synchronized(session) { session.timeline += toolItem }
            notifyTimeline(talker)

            val result = parsedArgs.getOrNull()?.let { AgentToolRegistry.execute(toolName, it, policy) }
                ?: ToolResult("Error: Malformed JSON arguments: ${parsedArgs.exceptionOrNull()?.message}", true)
            if (isCancelled(rs, gen)) break

            toolItem.running = false
            toolItem.toolResult = result.output
            toolItem.isError = result.isError
            notifyTimeline(talker)

            inFlightToolMsgs += JSONObject().apply {
                put("role", "tool")
                put("tool_call_id", callId)
                put("name", toolName)
                put("content", result.output)
            }
            result.imageAttachment?.let { pendingImages += it }
        }
        if (pendingImages.isNotEmpty() && !isCancelled(rs, gen)) {
            val parts = JSONArray().apply {
                val names = pendingImages.joinToString(", ") { it.displayPath.substringAfterLast('/') }
                put(JSONObject().put("type", "text").put("text", "[readimage 视觉输入: $names]"))
                for (img in pendingImages) {
                    put(JSONObject().apply {
                        put("type", "image_url")
                        put("image_url", JSONObject().put("url", img.dataUrl))
                    })
                }
            }
            inFlightToolMsgs += JSONObject().put("role", "user").put("content", parts)
        }
    }

    private fun collectTimelineImagePaths(timeline: List<TimelineItem>): Set<String> {
        val out = LinkedHashSet<String>()
        val imgTagRegex = Regex("\\[图片:\\s*([^\\]\n]+)\\]")
        for (it in timeline) {
            for (m in imgTagRegex.findAll(it.text)) {
                val raw = m.groupValues.getOrNull(1)?.trim().orEmpty()
                if (raw.isNotEmpty()) {
                    runCatching { java.io.File(raw).canonicalPath }.getOrNull()?.let { out += it }
                }
            }
        }
        return out
    }

    fun appendError(session: ChatSessionState, msg: String) {
        val now = System.currentTimeMillis()
        synchronized(session) {
            session.timeline += TimelineItem(
                id = session.nextId(),
                kind = "error",
                senderName = "系统",
                timestampMs = now,
                timeText = formatBubbleTime(now),
                text = msg,
                isError = true,
            )
        }
    }

    private fun summarizeTool(name: String, args: JSONObject): String = when (name) {
        "wechat" -> {
            val act = args.optString("action", "?")
            val target = args.optString("talker", args.optString("query", args.optString("sns_id", "")))
            if (target.isNotEmpty()) "$act · $target" else act
        }
        "read", "write", "edit" -> args.optString("file_path", name)
        "readimage" -> args.optString("file_path", "readimage").substringAfterLast('/')
        "bash" -> args.optString("command", "").replace('\n', ' ').take(48)
        else -> name
    }

    private fun upsertStreamItem(
        talker: String,
        session: ChatSessionState,
        existing: TimelineItem?,
        kind: String,
        fullText: String,
        lastPushMs: Long,
        notifyTimeline: (String) -> Unit,
    ): Pair<TimelineItem, Long> {
        if (existing == null) {
            val now = System.currentTimeMillis()
            val item = TimelineItem(
                id = session.nextId(),
                kind = kind,
                senderName = "AI",
                timestampMs = now,
                timeText = formatBubbleTime(now),
                text = fullText,
                running = true,
            )
            synchronized(session) { session.timeline += item }
            notifyTimeline(talker)
            return item to now
        }
        existing.text = fullText
        val now = System.currentTimeMillis()
        if (now - lastPushMs >= 70L) {
            notifyTimeline(talker)
            return existing to now
        }
        return existing to lastPushMs
    }

    private fun isCancelled(rs: RunState, gen: Int): Boolean = rs.stopRequested || rs.runGen.get() != gen
}
