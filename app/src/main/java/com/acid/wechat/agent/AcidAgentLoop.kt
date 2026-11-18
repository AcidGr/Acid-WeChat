package com.acid.wechat.agent

import com.acid.wechat.core.AcidLogger
import com.acid.wechat.core.ConfigSchema
import com.acid.wechat.core.ConfigStore
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Ephemeral single-session Agent ReAct loop living inside Acid-WeChat.
 */
object AcidAgentLoop {
    private const val TAG = "AcidAgentLoop"

    data class UiItem(
        val id: String,
        val type: String,
        var text: String = "",
        var toolName: String = "",
        var toolSummary: String = "",
        var toolArgs: String = "",
        var toolResult: String = "",
        var running: Boolean = false,
        var isError: Boolean = false,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("type", type)
            put("text", text)
            put("toolName", toolName)
            put("toolSummary", toolSummary)
            put("toolArgs", toolArgs)
            put("toolResult", toolResult)
            put("running", running)
            put("isError", isError)
        }
    }

    interface Listener {
        fun onStateChanged(running: Boolean, step: Int, totalTokens: Int)
        fun onItemsChanged(items: List<UiItem>)
    }

    private val uiItems = Collections.synchronizedList(mutableListOf<UiItem>())
    private val apiMessages = Collections.synchronizedList(mutableListOf<JSONObject>())
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val runGen = AtomicInteger(0)
    private val idSeq = AtomicInteger(0)

    @Volatile var isRunning = false
        private set
    @Volatile var currentStep = 0
        private set
    @Volatile var lastTotalTokens = 0
        private set
    @Volatile private var stopRequested = false
    @Volatile private var activeConn: HttpURLConnection? = null
    @Volatile private var workerThread: Thread? = null

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    fun snapshotUiItems(): List<UiItem> = synchronized(uiItems) { uiItems.map { it.copy() } }

    fun clearSession() {
        stop()
        synchronized(uiItems) { uiItems.clear() }
        synchronized(apiMessages) { apiMessages.clear() }
        currentStep = 0
        lastTotalTokens = 0
        notifyItems()
        notifyState()
    }

    fun stop() {
        stopRequested = true
        runGen.incrementAndGet()
        runCatching { activeConn?.disconnect() }
        activeConn = null
        isRunning = false
        synchronized(uiItems) {
            for (it in uiItems) {
                if (it.running) {
                    it.running = false
                    if (it.type == "tool" && it.toolResult == "执行中...") {
                        it.toolResult = "[已停止]"
                    }
                }
            }
        }
        notifyItems()
        notifyState()
    }

    fun submitPrompt(prompt: String) {
        val text = prompt.trim()
        if (text.isEmpty() || isRunning) return
        AgentConfigStore.ensureInitialized()
        val cfg = AgentConfigStore.getConfig()
        if (!cfg.hasApiKey) {
            appendItem(UiItem(nextId(), "user", text = text))
            appendItem(
                UiItem(
                    nextId(),
                    "error",
                    text = "未配置 API Key：请在右上角快速填入，或直接编辑 ${AgentConfigStore.modelsFile().absolutePath}",
                    isError = true,
                ),
            )
            return
        }

        val gen = runGen.incrementAndGet()
        stopRequested = false
        isRunning = true
        currentStep = 0
        appendItem(UiItem(nextId(), "user", text = text))
        synchronized(apiMessages) {
            apiMessages.add(JSONObject().put("role", "user").put("content", text))
        }
        notifyState()

        workerThread = Thread({
            runLoop(cfg, gen)
        }, "AcidWeChatAgent").apply { isDaemon = true; start() }
    }

    private fun runLoop(cfg: AgentConfigStore.ModelConfig, gen: Int) {
        val toolsSchema = AgentToolRegistry.buildToolsSchema()
        try {
            for (step in 1..cfg.maxSteps) {
                if (isCancelled(gen)) break
                currentStep = step
                notifyState()

                val reqMessages = buildApiRequestMessages()
                var thinkItem: UiItem? = null
                var replyItem: UiItem? = null
                var lastPushMs = 0L

                val assistantMsg = AcidLlmClient.streamChatCompletion(
                    cfg = cfg,
                    messages = reqMessages,
                    toolsSchema = toolsSchema,
                    step = step,
                    cb = AcidLlmClient.StreamCallbacks(
                        isCancelled = { isCancelled(gen) },
                        onConnectionOpened = { activeConn = it },
                        onReasoningDelta = { full ->
                            if (thinkItem == null) {
                                thinkItem = UiItem(nextId(), "thinking", text = full, running = true)
                                appendItem(thinkItem!!)
                            } else {
                                thinkItem!!.text = full
                                val now = System.currentTimeMillis()
                                if (now - lastPushMs >= 60L) {
                                    lastPushMs = now
                                    notifyItems()
                                }
                            }
                        },
                        onContentDelta = { full ->
                            thinkItem?.takeIf { it.running }?.let { it.running = false }
                            if (replyItem == null) {
                                replyItem = UiItem(nextId(), "assistant", text = full, running = true)
                                appendItem(replyItem!!)
                            } else {
                                replyItem!!.text = full
                                val now = System.currentTimeMillis()
                                if (now - lastPushMs >= 60L) {
                                    lastPushMs = now
                                    notifyItems()
                                }
                            }
                        },
                        onUsage = { _, _, tt ->
                            lastTotalTokens = tt
                            notifyState()
                        },
                    ),
                ) ?: break

                thinkItem?.let { it.running = false }
                replyItem?.let { it.running = false }
                notifyItems()

                synchronized(apiMessages) { apiMessages.add(assistantMsg) }

                val toolCalls = assistantMsg.optJSONArray("tool_calls")
                if (toolCalls == null || toolCalls.length() == 0) break

                executeToolBatch(toolCalls, step, gen)
            }
        } catch (t: Throwable) {
            if (!isCancelled(gen)) {
                AcidLogger.w(TAG, "agent loop failed", t)
                appendItem(UiItem(nextId(), "error", text = "请求异常: ${t.message}", isError = true))
            }
        } finally {
            activeConn = null
            if (runGen.get() == gen) {
                isRunning = false
                synchronized(uiItems) {
                    uiItems.forEach { if (it.running) it.running = false }
                }
                notifyItems()
                notifyState()
            }
        }
    }

    private fun executeToolBatch(toolCalls: JSONArray, step: Int, gen: Int) {
        var executed = 0
        try {
            for (i in 0 until toolCalls.length()) {
                if (isCancelled(gen)) break
                val tc = toolCalls.optJSONObject(i) ?: continue
                val callId = tc.optString("id", "call_${step}_$i")
                val fn = tc.optJSONObject("function")
                val toolName = fn?.optString("name", "") ?: ""
                val rawArgs = fn?.optString("arguments", "{}") ?: "{}"

                val parsedArgs = runCatching {
                    if (rawArgs.isBlank()) JSONObject() else JSONObject(rawArgs)
                }
                val summary = parsedArgs.getOrNull()?.let { summarizeTool(toolName, it) } ?: "$toolName · invalid JSON"
                val uiItem = UiItem(
                    id = nextId(),
                    type = "tool",
                    toolName = toolName,
                    toolSummary = summary,
                    toolArgs = parsedArgs.getOrNull()?.toString(2) ?: rawArgs,
                    toolResult = "执行中...",
                    running = true,
                )
                appendItem(uiItem)

                val result = parsedArgs.getOrNull()?.let { AgentToolRegistry.execute(toolName, it) }
                    ?: ToolResult("Error: Malformed JSON arguments: ${parsedArgs.exceptionOrNull()?.message}", true)
                if (isCancelled(gen)) break

                uiItem.running = false
                uiItem.toolResult = result.output
                uiItem.isError = result.isError
                notifyItems()

                synchronized(apiMessages) {
                    apiMessages.add(JSONObject().apply {
                        put("role", "tool")
                        put("tool_call_id", callId)
                        put("name", toolName)
                        put("content", result.output)
                    })
                }
                executed++
            }
        } finally {
            if (executed < toolCalls.length()) {
                synchronized(apiMessages) {
                    for (j in executed until toolCalls.length()) {
                        val tc = toolCalls.optJSONObject(j) ?: continue
                        val callId = tc.optString("id", "call_${step}_$j")
                        val tName = tc.optJSONObject("function")?.optString("name", "tool") ?: "tool"
                        apiMessages.add(JSONObject().apply {
                            put("role", "tool")
                            put("tool_call_id", callId)
                            put("name", tName)
                            put("content", "[Execution stopped]")
                        })
                    }
                }
            }
        }
    }

    private fun buildApiRequestMessages(): JSONArray {
        val sys = JSONObject().put("role", "system").put("content", buildSystemPrompt())
        return JSONArray().apply {
            put(sys)
            synchronized(apiMessages) {
                for (m in apiMessages) put(m)
            }
        }
    }

    private fun buildSystemPrompt(): String {
        val wsPath = AgentConfigStore.workspaceDir().absolutePath
        val cfgSchema = ConfigSchema.describeJsonSchemaForPrompt(ConfigStore.configFile().absolutePath)
        return """
You are Acid Agent, an autonomous AI assistant embedded inside Acid-WeChat on Android.
You have access to 5 core tools:
1. `wechat`: Inspect module status, search contacts/chatrooms, read chat history, send real WeChat text messages, apply fake likes/comments (by explicit sns_id), or patch `config.json`. (Moments browsing is disabled.)
2. `read`: Read UTF-8 files in your dedicated workspace (`$wsPath`) as well as `config.json`, `config.schema.json`, and `acid.log`.
3. `write`: Create or overwrite UTF-8 files inside `$wsPath` or update `config.json`.
4. `edit`: Perform exact literal replacements in workspace files or `config.json`.
5. `bash`: Run `/system/bin/sh -c` commands inside `$wsPath`.

Rules:
- Dedicated workspace: All scratch scripts, notes, reports, and data files belong in `$wsPath`.
- Sending WeChat messages: Before calling `wechat(action='send_message', ...)`, resolve the recipient cleanly via `search_contacts` / `list_chats` / `read_messages` if the contact name could match multiple people. Verify intent carefully for external actions.
- Settings SSOT: $cfgSchema Changes via `write`/`edit`/`wechat(action='config_patch')` hot-reload live into running hooks.
- Respond in Simplified Chinese (简体中文) by default with crisp Markdown formatting unless the user asks in another language.
        """.trimIndent()
    }

    private fun summarizeTool(name: String, args: JSONObject): String = when (name) {
        "wechat" -> {
            val act = args.optString("action", "?")
            val target = args.optString("talker", args.optString("query", args.optString("sns_id", "")))
            if (target.isNotEmpty()) "$act · $target" else act
        }
        "read", "write", "edit" -> args.optString("file_path", name)
        "bash" -> args.optString("command", "").replace('\n', ' ').take(48)
        else -> name
    }

    private fun isCancelled(gen: Int): Boolean = stopRequested || runGen.get() != gen
    private fun nextId(): String = "m_${idSeq.incrementAndGet()}"

    private fun appendItem(item: UiItem) {
        synchronized(uiItems) { uiItems.add(item) }
        notifyItems()
    }

    private fun notifyItems() {
        val snap = snapshotUiItems()
        for (l in listeners) runCatching { l.onItemsChanged(snap) }
    }

    private fun notifyState() {
        for (l in listeners) runCatching { l.onStateChanged(isRunning, currentStep, lastTotalTokens) }
    }
}
