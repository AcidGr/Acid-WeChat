package com.acid.wechat.agent

import com.acid.wechat.core.AcidIo
import com.acid.wechat.core.AcidLogger
import com.acid.wechat.core.ConfigStore
import com.acid.wechat.core.HostContext
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

    data class ModelConfig(
        val baseUrl: String = DEFAULT_BASE_URL,
        val apiKey: String = "",
        val model: String = DEFAULT_MODEL,
        val temperature: Double = DEFAULT_TEMP,
        val maxSteps: Int = DEFAULT_MAX_STEPS,
        val reasoningEffort: String = "default",
    ) {
        val hasApiKey: Boolean get() = apiKey.isNotBlank() || isLoopbackBaseUrl(baseUrl)
    }

    private fun isLoopbackBaseUrl(url: String): Boolean {
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

    fun getConfig(): ModelConfig {
        val f = modelsFile()
        if (!f.exists() || f.length() == 0L) {
            ensureInitialized()
            return cached
        }
        val m = f.lastModified()
        val len = f.length()
        if (m != lastMtime || len != lastLen) {
            synchronized(lock) { reloadFromDiskLocked(f) }
        }
        return cached
    }

    fun updateConfig(baseUrl: String, apiKey: String, model: String): ModelConfig {
        synchronized(lock) {
            val f = modelsFile()
            if (f.exists() && f.length() > 0L) reloadFromDiskLocked(f)
            val next = cached.copy(
                baseUrl = baseUrl.trim().ifEmpty { DEFAULT_BASE_URL },
                apiKey = apiKey.trim(),
                model = model.trim().ifEmpty { DEFAULT_MODEL },
            )
            saveLocked(next)
            return cached
        }
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
            cached = ModelConfig(
                baseUrl = obj.optString("base_url", DEFAULT_BASE_URL).trim().ifEmpty { DEFAULT_BASE_URL },
                apiKey = obj.optString("api_key", "").trim(),
                model = obj.optString("model", DEFAULT_MODEL).trim().ifEmpty { DEFAULT_MODEL },
                temperature = obj.optDouble("temperature", DEFAULT_TEMP).coerceIn(0.0, 2.0),
                maxSteps = obj.optInt("max_steps", DEFAULT_MAX_STEPS).coerceIn(1, 200),
                reasoningEffort = obj.optString("reasoning_effort", "default").trim().ifEmpty { "default" },
            )
            lastMtime = f.lastModified()
            lastLen = f.length()
        }.onFailure {
            AcidLogger.w(TAG, "invalid agent/models.json ignored, keeping previous config", it)
        }
    }

    private fun saveLocked(cfg: ModelConfig) {
        runCatching {
            val obj = JSONObject().apply {
                put("base_url", cfg.baseUrl)
                put("api_key", cfg.apiKey)
                put("model", cfg.model)
                put("temperature", cfg.temperature)
                put("max_steps", cfg.maxSteps)
                put("reasoning_effort", cfg.reasoningEffort)
            }
            val f = modelsFile()
            AcidIo.writeAtomicUtf8(f, obj.toString(2))
            cached = cfg
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
