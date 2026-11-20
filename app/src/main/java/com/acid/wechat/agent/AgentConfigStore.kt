package com.acid.wechat.agent

import com.acid.wechat.core.AcidIo
import com.acid.wechat.core.AcidLogger
import com.acid.wechat.core.ConfigStore
import com.acid.wechat.core.HostContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Dedicated Agent workspace (`AcidWeChat/workspace/`) and JSON SSOT model config (`AcidWeChat/agent/models.json`).
 */
object AgentConfigStore {
    private const val TAG = "AgentConfigStore"
    private const val WORKSPACE_DIR_NAME = "workspace"
    private const val AGENT_DIR_NAME = "agent"
    private const val MODELS_FILE_NAME = "models.json"

    const val DEFAULT_BASE_URL = "https://api.deepseek.com/v1"
    const val DEFAULT_MODEL = "deepseek-chat"
    const val DEFAULT_TEMP = 0.2
    const val DEFAULT_MAX_STEPS = 40
    const val DEFAULT_AUTO_COMPACT_TOKENS = 72_000
    const val DEFAULT_IMAGE_MODEL = "gemini-3.1-flash-image"
    const val DEFAULT_WAKE_WORD = "@ai"

    val ALL_TOOL_NAMES: List<String> get() = AgentToolRegistry.allToolNames()
    val DEFAULT_ENABLED_TOOLS: Set<String> = setOf("wechat", "sendfile", "readimage", "read", "write", "edit", "bash")
    val VALID_REASONING_EFFORTS: Set<String> = setOf("default", "low", "medium", "high")

    val DEFAULT_PANEL_SYSTEM_PROMPT: String = """
你是「Acid Agent」，运行在机主（master）手机微信内的本机 AI 助手。
请结合当前会话上下文与已开启工具，用简体中文清晰、准确地回答机主的问题。
    """.trimIndent()

    val DEFAULT_AUTO_REPLY_SYSTEM_PROMPT: String = """
你的后台角色是机主的助手「酸小BOT」，物理上与机主共用同一个微信号。
1. 发送出口强约定：你没有发消息工具，你这次回复的最终文本输出本身就会直接作为这条微信消息发到当前聊天中；严禁输出“我已帮你发过去了”“好的这就回他”“已发送给对方”等元话语，请直接说出要发给当前聊天的那句正文。
2. 自然融入：平时以机主视角或贴近机主口吻简要回应，不要主动自称机器人或 AI；只有在对方明确质疑“你是不是机器人 / 不是本人吧”时，才坦诚你是机主的助手，并说明机主本人稍后会看消息。
3. 严守物理边界：绝对禁止编造机主此刻的物理位置、行程或正在做什么（不知道的事绝不瞎编）；若被问到行踪、线下见/约时间、借钱/借物、或需要机主拍板的承诺/决定，统一用自然短句推脱或转告“这会儿走不开 / 晚点本人联系你”。
4. 回复格式强约束（微信实发文本）：必须像真人发微信一样简短克制，一般只回复 1～2 句；严禁使用任何 Markdown（禁止 **、#、代码块、表格、引用 >、有序/无序列表）以及 emoji 表情，也不要带唤醒词前缀。
    """.trimIndent()

    data class ModelConfig(
        val baseUrl: String = DEFAULT_BASE_URL,
        val apiKey: String = "",
        val model: String = DEFAULT_MODEL,
        val temperature: Double = DEFAULT_TEMP,
        val maxSteps: Int = DEFAULT_MAX_STEPS,
        val autoCompactTokens: Int = DEFAULT_AUTO_COMPACT_TOKENS,
        val reasoningEffort: String = "default",
        val panelSystemPrompt: String = DEFAULT_PANEL_SYSTEM_PROMPT,
        val autoReplySystemPrompt: String = DEFAULT_AUTO_REPLY_SYSTEM_PROMPT,
        val enabledTools: Set<String> = DEFAULT_ENABLED_TOOLS,
        val webSearchBaseUrl: String = "",
        val webSearchApiKey: String = "",
        val webSearchModel: String = "",
        val imageBaseUrl: String = "",
        val imageApiKey: String = "",
        val imageModel: String = DEFAULT_IMAGE_MODEL,
        val webFetchBaseUrl: String = "",
        val webFetchApiKey: String = "",
        val allowGuestDrawImage: Boolean = false,
        val wakeOnGroupAt: Boolean = true,
        val wakeWordEnabled: Boolean = true,
        val wakeWord: String = DEFAULT_WAKE_WORD,
        val takeoverTalkers: Set<String> = emptySet(),
    ) {
        val hasApiKey: Boolean get() = apiKey.isNotBlank() || isLoopbackBaseUrl(baseUrl)

        fun isToolConfigured(toolName: String): Boolean = when (toolName.trim().lowercase()) {
            "websearch" -> webSearchBaseUrl.isNotBlank()
            "drawimage" -> imageBaseUrl.isNotBlank()
            "webfetch" -> webFetchBaseUrl.isNotBlank()
            else -> true
        }
    }

    fun sanitizeAutoCompactTokens(raw: Int): Int =
        if (raw <= 0) 0 else raw.coerceIn(1_000, 2_000_000)

    fun sanitizeWakeWord(raw: String): String =
        raw.trim().take(64).ifEmpty { DEFAULT_WAKE_WORD }

    fun normalizeReasoningEffort(raw: String): String {
        val norm = raw.trim().lowercase()
        val mapped = if (norm == "max") "high" else norm
        return if (mapped in VALID_REASONING_EFFORTS) mapped else "default"
    }

    private fun canonicalTools(rawSet: Set<String>): Set<String> =
        ALL_TOOL_NAMES.filterTo(LinkedHashSet()) { it in rawSet }

    private fun parseEnabledTools(arr: JSONArray?): Set<String> {
        if (arr == null) return DEFAULT_ENABLED_TOOLS
        val picked = HashSet<String>(arr.length())
        for (i in 0 until arr.length()) {
            val name = arr.optString(i, "").trim()
            if (name.isNotEmpty()) picked += name
        }
        return canonicalTools(picked)
    }

    private fun parseTakeoverTalkers(arr: JSONArray?): Set<String> {
        if (arr == null) return emptySet()
        val picked = LinkedHashSet<String>(arr.length())
        for (i in 0 until arr.length()) {
            val t = arr.optString(i, "").trim()
            if (t.isNotEmpty() && t != AgentSessionModels.GLOBAL_TALKER) picked += t
        }
        return picked
    }

    private fun sanitizeConfig(cfg: ModelConfig): ModelConfig {
        val filtered = canonicalTools(cfg.enabledTools.filterTo(LinkedHashSet()) { cfg.isToolConfigured(it) })
        val cleanWake = sanitizeWakeWord(cfg.wakeWord)
        val cleanTakeovers = cfg.takeoverTalkers.filterTo(LinkedHashSet()) {
            it.isNotBlank() && it != AgentSessionModels.GLOBAL_TALKER
        }
        return if (filtered == cfg.enabledTools && cleanWake == cfg.wakeWord && cleanTakeovers == cfg.takeoverTalkers) {
            cfg
        } else {
            cfg.copy(enabledTools = filtered, wakeWord = cleanWake, takeoverTalkers = cleanTakeovers)
        }
    }

    fun isLoopbackBaseUrl(url: String): Boolean {
        val lower = url.trim().lowercase()
        return lower.startsWith("http://127.0.0.1") ||
            lower.startsWith("http://localhost") ||
            lower.startsWith("https://127.0.0.1") ||
            lower.startsWith("https://localhost")
    }

    enum class PathScope { WORKSPACE, CONFIG_JSON, MODELS_JSON, READONLY_META }

    sealed class PathResolution {
        data class Allowed(val file: File, val scope: PathScope) : PathResolution()
        data class Denied(val reason: String) : PathResolution()
    }

    private val lock = Any()
    @Volatile private var cached = ModelConfig()
    @Volatile private var lastMtime = 0L
    @Volatile private var lastLen = -1L

    fun workspaceDir(): File {
        val ws = File(HostContext.getDataDir(), WORKSPACE_DIR_NAME)
        if (!ws.exists()) runCatching { ws.mkdirs() }
        return ws
    }

    fun modelsFile(): File {
        val dir = File(HostContext.getDataDir(), AGENT_DIR_NAME)
        if (!dir.exists()) runCatching { dir.mkdirs() }
        return File(dir, MODELS_FILE_NAME)
    }

    fun sessionsDir(): File {
        val dir = File(File(HostContext.getDataDir(), AGENT_DIR_NAME), "sessions")
        if (!dir.exists()) runCatching { dir.mkdirs() }
        return dir
    }

    fun guestSandboxDir(talker: String): File {
        val safe = sanitizeTalkerKey(talker)
        val dir = File(File(workspaceDir(), "sandbox"), safe)
        if (!dir.exists()) runCatching { dir.mkdirs() }
        return dir
    }

    fun sanitizeTalkerKey(talker: String): String {
        val cleaned = talker.trim().replace(Regex("[^a-zA-Z0-9._@-]"), "_")
        return cleaned.ifEmpty { "default" }.take(80)
    }

    fun ensureInitialized() {
        workspaceDir()
        synchronized(lock) {
            val f = modelsFile()
            if (!f.exists() || f.length() == 0L) {
                saveLocked(ModelConfig())
            } else {
                reloadFromDiskLocked(f)
            }
        }
    }

    fun getConfig(forceReload: Boolean = false): ModelConfig {
        val f = modelsFile()
        if (!f.exists() || f.length() == 0L) {
            ensureInitialized()
            return cached
        }
        val m = f.lastModified()
        val len = f.length()
        if (forceReload || m != lastMtime || len != lastLen) {
            synchronized(lock) { reloadFromDiskLocked(f) }
        }
        return cached
    }

    fun updateConfig(baseUrl: String, apiKey: String, model: String): ModelConfig =
        updateFromPatch(
            JSONObject()
                .put("baseUrl", baseUrl)
                .put("apiKey", apiKey)
                .put("model", model),
        )

    fun updateFromPatch(patch: JSONObject): ModelConfig {
        synchronized(lock) {
            val f = modelsFile()
            if (f.exists() && f.length() > 0L) reloadFromDiskLocked(f)
            var next = cached
            if (patch.has("baseUrl")) {
                next = next.copy(baseUrl = patch.optString("baseUrl").trim().ifEmpty { DEFAULT_BASE_URL })
            }
            if (patch.has("apiKey")) {
                next = next.copy(apiKey = patch.optString("apiKey").trim())
            }
            if (patch.has("model")) {
                next = next.copy(model = patch.optString("model").trim().ifEmpty { DEFAULT_MODEL })
            }
            if (patch.has("reasoningEffort")) {
                next = next.copy(reasoningEffort = normalizeReasoningEffort(patch.optString("reasoningEffort", "default")))
            }
            if (patch.has("panelSystemPrompt")) {
                next = next.copy(
                    panelSystemPrompt = patch.optString("panelSystemPrompt", "").trim().ifEmpty { DEFAULT_PANEL_SYSTEM_PROMPT },
                )
            }
            if (patch.has("autoReplySystemPrompt")) {
                next = next.copy(
                    autoReplySystemPrompt = patch.optString("autoReplySystemPrompt", "").trim().ifEmpty { DEFAULT_AUTO_REPLY_SYSTEM_PROMPT },
                )
            }
            if (patch.has("autoCompactTokens")) {
                val parsed = patch.optString("autoCompactTokens", "").trim().toIntOrNull()
                    ?: patch.optInt("autoCompactTokens", DEFAULT_AUTO_COMPACT_TOKENS)
                next = next.copy(autoCompactTokens = sanitizeAutoCompactTokens(parsed))
            }
            if (patch.has("webSearchBaseUrl")) {
                next = next.copy(webSearchBaseUrl = patch.optString("webSearchBaseUrl", "").trim())
            }
            if (patch.has("webSearchApiKey")) {
                next = next.copy(webSearchApiKey = patch.optString("webSearchApiKey", "").trim())
            }
            if (patch.has("webSearchModel")) {
                next = next.copy(webSearchModel = patch.optString("webSearchModel", "").trim())
            }
            if (patch.has("imageBaseUrl")) {
                next = next.copy(imageBaseUrl = patch.optString("imageBaseUrl", "").trim())
            }
            if (patch.has("imageApiKey")) {
                next = next.copy(imageApiKey = patch.optString("imageApiKey", "").trim())
            }
            if (patch.has("imageModel")) {
                next = next.copy(imageModel = patch.optString("imageModel", "").trim().ifEmpty { DEFAULT_IMAGE_MODEL })
            }
            if (patch.has("webFetchBaseUrl")) {
                next = next.copy(webFetchBaseUrl = patch.optString("webFetchBaseUrl", "").trim())
            }
            if (patch.has("webFetchApiKey")) {
                next = next.copy(webFetchApiKey = patch.optString("webFetchApiKey", "").trim())
            }
            if (patch.has("allowGuestDrawImage")) {
                next = next.copy(allowGuestDrawImage = patch.optBoolean("allowGuestDrawImage", false))
            }
            if (patch.has("wakeOnGroupAt")) {
                next = next.copy(wakeOnGroupAt = patch.optBoolean("wakeOnGroupAt", true))
            }
            if (patch.has("wakeWordEnabled")) {
                next = next.copy(wakeWordEnabled = patch.optBoolean("wakeWordEnabled", true))
            }
            if (patch.has("wakeWord")) {
                next = next.copy(wakeWord = sanitizeWakeWord(patch.optString("wakeWord", "")))
            }
            patch.optJSONArray("enabledTools")?.let { arr ->
                next = next.copy(enabledTools = parseEnabledTools(arr))
            }
            if (patch.has("toggleTool")) {
                val tool = patch.optString("toggleTool", "").trim()
                val enabled = patch.optBoolean("enabled", true)
                if (tool in ALL_TOOL_NAMES) {
                    val updatedSet = next.enabledTools.toMutableSet()
                    if (enabled && next.isToolConfigured(tool)) updatedSet += tool else updatedSet -= tool
                    next = next.copy(enabledTools = canonicalTools(updatedSet))
                }
            }
            saveLocked(sanitizeConfig(next))
            return cached
        }
    }

    fun isTakeoverEnabled(talker: String): Boolean {
        val clean = talker.trim()
        if (clean.isEmpty() || clean == AgentSessionModels.GLOBAL_TALKER) return false
        return clean in getConfig().takeoverTalkers
    }

    fun setTakeoverEnabled(talker: String, enabled: Boolean): Boolean {
        val clean = talker.trim()
        if (clean.isEmpty() || clean == AgentSessionModels.GLOBAL_TALKER) return false
        synchronized(lock) {
            val f = modelsFile()
            if (f.exists() && f.length() > 0L) reloadFromDiskLocked(f)
            val updatedSet = cached.takeoverTalkers.toMutableSet()
            if (enabled) updatedSet += clean else updatedSet -= clean
            saveLocked(sanitizeConfig(cached.copy(takeoverTalkers = updatedSet)))
            return clean in cached.takeoverTalkers
        }
    }

    fun cleanWorkspace(): AcidIo.CleanResult = AcidIo.cleanDirContents(workspaceDir())

    fun toUiJson(cfg: ModelConfig = getConfig()): JSONObject = JSONObject().apply {
        val ws = workspaceDir()
        val wsStats = AcidIo.computeDirStats(ws)
        put("baseUrl", cfg.baseUrl)
        put("apiKey", cfg.apiKey)
        put("model", cfg.model)
        put("hasApiKey", cfg.hasApiKey)
        put("reasoningEffort", cfg.reasoningEffort)
        put("autoCompactTokens", cfg.autoCompactTokens)
        put("panelSystemPrompt", cfg.panelSystemPrompt.ifBlank { DEFAULT_PANEL_SYSTEM_PROMPT })
        put("defaultPanelSystemPrompt", DEFAULT_PANEL_SYSTEM_PROMPT)
        put("autoReplySystemPrompt", cfg.autoReplySystemPrompt.ifBlank { DEFAULT_AUTO_REPLY_SYSTEM_PROMPT })
        put("defaultAutoReplySystemPrompt", DEFAULT_AUTO_REPLY_SYSTEM_PROMPT)
        put("enabledTools", JSONArray(ALL_TOOL_NAMES.filter { it in cfg.enabledTools }))
        put("allowGuestDrawImage", cfg.allowGuestDrawImage)
        put("wakeOnGroupAt", cfg.wakeOnGroupAt)
        put("wakeWordEnabled", cfg.wakeWordEnabled)
        put("wakeWord", sanitizeWakeWord(cfg.wakeWord))
        put("defaultWakeWord", DEFAULT_WAKE_WORD)
        put("toolCatalog", AgentToolRegistry.catalogJson())
        put("webSearchBaseUrl", cfg.webSearchBaseUrl)
        put("webSearchApiKey", cfg.webSearchApiKey)
        put("webSearchModel", cfg.webSearchModel)
        put("imageBaseUrl", cfg.imageBaseUrl)
        put("imageApiKey", cfg.imageApiKey)
        put("imageModel", cfg.imageModel)
        put("webFetchBaseUrl", cfg.webFetchBaseUrl)
        put("webFetchApiKey", cfg.webFetchApiKey)
        put("workspace", ws.absolutePath)
        put("workspaceFiles", wsStats.fileCount)
        put("workspaceSize", AcidIo.formatBytes(wsStats.totalBytes))
        put("modelsFile", modelsFile().absolutePath)
    }

    fun resolvePath(rawPath: String, forWrite: Boolean, guestTalker: String? = null): PathResolution {
        val trimmed = rawPath.trim()
        if (trimmed.isEmpty()) return PathResolution.Denied("Path is empty")

        if (guestTalker != null) {
            val sandboxRoot = canonical(guestSandboxDir(guestTalker))
            val candidate = canonical(
                if (File(trimmed).isAbsolute) File(trimmed) else File(sandboxRoot, trimmed),
            )
            return if (isInsideDir(candidate, sandboxRoot)) {
                PathResolution.Allowed(candidate, PathScope.WORKSPACE)
            } else {
                PathResolution.Denied("Guest sandbox denies access outside ${sandboxRoot.absolutePath}")
            }
        }

        val dataRoot = canonical(HostContext.getDataDir())
        val wsRoot = canonical(workspaceDir())
        val configFile = canonical(ConfigStore.configFile())
        val schemaFile = canonical(ConfigStore.schemaFile())
        val logFile = canonical(File(dataRoot, "acid.log"))
        val models = canonical(modelsFile())

        val candidate = canonical(
            if (File(trimmed).isAbsolute) File(trimmed) else File(wsRoot, trimmed),
        )

        if (candidate == configFile || trimmed == "config.json" || trimmed == "../config.json") {
            return PathResolution.Allowed(configFile, PathScope.CONFIG_JSON)
        }
        if (candidate == models || trimmed == "../agent/models.json" || trimmed == "agent/models.json") {
            return PathResolution.Allowed(models, PathScope.MODELS_JSON)
        }
        if (candidate == schemaFile || trimmed == "config.schema.json" || trimmed == "../config.schema.json") {
            return if (forWrite) {
                PathResolution.Denied("config.schema.json is auto-generated read-only")
            } else {
                PathResolution.Allowed(schemaFile, PathScope.READONLY_META)
            }
        }
        if (candidate == logFile || trimmed == "acid.log" || trimmed == "../acid.log") {
            return if (forWrite) {
                PathResolution.Denied("acid.log is read-only")
            } else {
                PathResolution.Allowed(logFile, PathScope.READONLY_META)
            }
        }
        if (isInsideDir(candidate, wsRoot)) {
            return PathResolution.Allowed(candidate, PathScope.WORKSPACE)
        }
        return PathResolution.Denied(
            "Access denied: '$rawPath' escapes workspace (${wsRoot.absolutePath}) and allowed config files",
        )
    }

    private fun reloadFromDiskLocked(f: File) {
        runCatching {
            val raw = f.readText(Charsets.UTF_8)
            val obj = JSONObject(raw)
            val tools = if (obj.has("enabled_tools") && !obj.isNull("enabled_tools")) {
                parseEnabledTools(obj.optJSONArray("enabled_tools"))
            } else {
                DEFAULT_ENABLED_TOOLS
            }
            cached = sanitizeConfig(
                ModelConfig(
                    baseUrl = obj.optString("base_url", DEFAULT_BASE_URL).trim().ifEmpty { DEFAULT_BASE_URL },
                    apiKey = obj.optString("api_key", "").trim(),
                    model = obj.optString("model", DEFAULT_MODEL).trim().ifEmpty { DEFAULT_MODEL },
                    temperature = obj.optDouble("temperature", DEFAULT_TEMP).coerceIn(0.0, 2.0),
                    maxSteps = obj.optInt("max_steps", DEFAULT_MAX_STEPS).coerceIn(1, 200),
                    autoCompactTokens = sanitizeAutoCompactTokens(obj.optInt("auto_compact_tokens", DEFAULT_AUTO_COMPACT_TOKENS)),
                    reasoningEffort = normalizeReasoningEffort(obj.optString("reasoning_effort", "default")),
                    panelSystemPrompt = obj.optString("panel_system_prompt", DEFAULT_PANEL_SYSTEM_PROMPT).trim().ifEmpty { DEFAULT_PANEL_SYSTEM_PROMPT },
                    autoReplySystemPrompt = obj.optString("auto_reply_system_prompt", DEFAULT_AUTO_REPLY_SYSTEM_PROMPT).trim().ifEmpty { DEFAULT_AUTO_REPLY_SYSTEM_PROMPT },
                    enabledTools = tools,
                    webSearchBaseUrl = obj.optString("web_search_base_url", "").trim(),
                    webSearchApiKey = obj.optString("web_search_api_key", "").trim(),
                    webSearchModel = obj.optString("web_search_model", "").trim(),
                    imageBaseUrl = obj.optString("image_base_url", "").trim(),
                    imageApiKey = obj.optString("image_api_key", "").trim(),
                    imageModel = obj.optString("image_model", DEFAULT_IMAGE_MODEL).trim().ifEmpty { DEFAULT_IMAGE_MODEL },
                    webFetchBaseUrl = obj.optString("web_fetch_base_url", "").trim(),
                    webFetchApiKey = obj.optString("web_fetch_api_key", "").trim(),
                    allowGuestDrawImage = obj.optBoolean("allow_guest_draw_image", false),
                    wakeOnGroupAt = obj.optBoolean("wake_on_group_at", true),
                    wakeWordEnabled = obj.optBoolean("wake_word_enabled", true),
                    wakeWord = sanitizeWakeWord(obj.optString("wake_word", DEFAULT_WAKE_WORD)),
                    takeoverTalkers = parseTakeoverTalkers(obj.optJSONArray("takeover_talkers")),
                ),
            )
            lastMtime = f.lastModified()
            lastLen = f.length()
        }.onFailure {
            AcidLogger.w(TAG, "invalid agent/models.json ignored, keeping previous config", it)
        }
    }

    private fun saveLocked(cfg: ModelConfig) {
        runCatching {
            val cleanCfg = sanitizeConfig(cfg)
            val obj = JSONObject().apply {
                put("base_url", cleanCfg.baseUrl)
                put("api_key", cleanCfg.apiKey)
                put("model", cleanCfg.model)
                put("temperature", cleanCfg.temperature)
                put("max_steps", cleanCfg.maxSteps)
                put("auto_compact_tokens", cleanCfg.autoCompactTokens)
                put("reasoning_effort", cleanCfg.reasoningEffort)
                put("panel_system_prompt", cleanCfg.panelSystemPrompt)
                put("auto_reply_system_prompt", cleanCfg.autoReplySystemPrompt)
                put("enabled_tools", JSONArray(ALL_TOOL_NAMES.filter { it in cleanCfg.enabledTools }))
                put("web_search_base_url", cleanCfg.webSearchBaseUrl)
                put("web_search_api_key", cleanCfg.webSearchApiKey)
                put("web_search_model", cleanCfg.webSearchModel)
                put("image_base_url", cleanCfg.imageBaseUrl)
                put("image_api_key", cleanCfg.imageApiKey)
                put("image_model", cleanCfg.imageModel)
                put("web_fetch_base_url", cleanCfg.webFetchBaseUrl)
                put("web_fetch_api_key", cleanCfg.webFetchApiKey)
                put("allow_guest_draw_image", cleanCfg.allowGuestDrawImage)
                put("wake_on_group_at", cleanCfg.wakeOnGroupAt)
                put("wake_word_enabled", cleanCfg.wakeWordEnabled)
                put("wake_word", cleanCfg.wakeWord)
                put("takeover_talkers", JSONArray(cleanCfg.takeoverTalkers.toList()))
            }
            val f = modelsFile()
            AcidIo.writeAtomicUtf8(f, obj.toString(2))
            cached = cleanCfg
            lastMtime = f.lastModified()
            lastLen = f.length()
        }.onFailure {
            AcidLogger.w(TAG, "save agent/models.json failed", it)
        }
    }

    private fun canonical(f: File): File =
        runCatching { f.canonicalFile }.getOrElse { f.absoluteFile }

    private fun isInsideDir(target: File, dir: File): Boolean {
        val tPath = target.path
        val dPath = dir.path
        return tPath == dPath || tPath.startsWith(dPath + File.separator)
    }
}
