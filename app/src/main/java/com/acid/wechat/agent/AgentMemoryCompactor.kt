package com.acid.wechat.agent

import com.acid.wechat.agent.AgentSessionModels.TimelineItem
import com.acid.wechat.agent.AgentSessionModels.formatContextTime
import org.json.JSONArray
import org.json.JSONObject

/**
 * Compresses older per-chat turns into `[长期记忆与系统状态快照]` while keeping the newest
 * window verbatim, mirroring the Huawei Cloud memory-compaction design locally on-device.
 */
object AgentMemoryCompactor {
    const val AUTO_COMPACT_PROMPT_TOKENS = 32_000
    const val AUTO_COMPACT_UNCOMPACTED_TURNS = 36

    data class CompactOutcome(
        val ok: Boolean,
        val summary: String = "",
        val error: String = "",
    )

    fun compact(
        cfg: AgentConfigStore.ModelConfig,
        priorSummary: String,
        itemsToCompact: List<TimelineItem>,
    ): CompactOutcome {
        if (!cfg.hasApiKey) {
            return CompactOutcome(false, error = "未配置 API Key，无法调用模型压缩会话")
        }
        if (itemsToCompact.isEmpty()) {
            return CompactOutcome(false, error = "暂无足够历史记录可压缩（保留最近 ${AgentSessionModels.KEEP_RECENT_ON_COMPACT} 条活跃消息）")
        }

        val transcript = buildTranscript(itemsToCompact)
        val systemPrompt = "你是一个精准的会话记忆压缩引擎，请把聊天记录和 AI 动作整理为简洁、客观的中文状态快照。"
        val userPrompt = buildString {
            append("请将以下历史消息整理为「长期记忆与系统状态快照」，重点提取：\n")
            append("1. 【时间范围】：概括这批记录发生的时间跨度；\n")
            append("2. 【讨论要点与事实】：提炼对方诉求、关键信息、共识与待办；\n")
            append("3. 【已发生事件与动作】：客观列出发送了什么回复、执行过什么关键查询/工具操作（明确区分 master、对方与 AI）；\n")
            append("禁止空泛套话或杜撰，总字数控制在 380 字以内。\n\n")
            if (priorSummary.isNotBlank()) {
                append("--- 既有记忆快照（请合并保留关键信息）---\n")
                append(priorSummary.trim()).append("\n\n")
            }
            append("--- 新增待压缩历史记录 ---\n")
            append(transcript)
        }

        val msgs = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", systemPrompt))
            put(JSONObject().put("role", "user").put("content", userPrompt))
        }
        val compactCfg = cfg.copy(temperature = 0.2, maxSteps = 1)
        val reply = runCatching {
            AcidLlmClient.streamChatCompletion(
                cfg = compactCfg,
                messages = msgs,
                toolsSchema = JSONArray(),
                step = 1,
                cb = AcidLlmClient.StreamCallbacks(
                    isCancelled = { false },
                    onConnectionOpened = {},
                    onReasoningDelta = {},
                    onContentDelta = {},
                    onUsage = { _, _, _ -> },
                ),
            )
        }.getOrElse { err ->
            return CompactOutcome(false, error = "压缩请求失败: ${err.message}")
        }

        val content = reply?.optString("content", "")?.trim().orEmpty()
        if (content.isEmpty() || content == "null") {
            return CompactOutcome(false, error = "模型未返回有效压缩内容")
        }
        val formatted = if (content.startsWith("[长期记忆与系统状态快照")) {
            content
        } else {
            "[长期记忆与系统状态快照 (已压缩)]\n$content"
        }
        return CompactOutcome(ok = true, summary = formatted)
    }

    private fun buildTranscript(items: List<TimelineItem>): String = buildString {
        for (it in items) {
            val t = formatContextTime(it.timestampMs)
            val cleanText = AgentSessionModels.stripAtAiForModel(it.text).ifEmpty { "(呼叫了 AI)" }
            when (it.kind) {
                "master" -> append("[master ").append(t).append("]: ").append(cleanText).append('\n')
                "peer" -> append('[').append(it.senderName.ifBlank { "对方" }).append(' ').append(t).append("]: ").append(cleanText).append('\n')
                "agent" -> append("[agent ").append(t).append("]: ").append(it.text).append('\n')
                "tool" -> append("[tool ").append(it.toolName).append(" · ").append(it.toolSummary).append("] -> ")
                    .append(it.toolResult.replace('\n', ' ').take(160)).append('\n')
            }
        }
    }
}
