package com.acid.wechat.agent

import com.acid.wechat.agent.AgentToolRegistry.makeTool
import com.acid.wechat.agent.AgentToolRegistry.prop
import com.acid.wechat.core.AcidIo
import com.acid.wechat.core.AcidLogger
import com.acid.wechat.core.ConfigStore
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.TimeUnit

/**
 * Sandboxed file (`read`, `write`, `edit`) and shell (`bash`) tools scoped to
 * `AcidWeChat/workspace/` and controlled config SSOT files.
 */
object SandboxFileTools {
    private const val TAG = "SandboxTools"
    private const val MAX_READ_BYTES = 1024L * 1024L
    private const val MAX_BASH_CAPTURE = 64 * 1024
    private const val MAX_BASH_RETURN = 16000

    object ReadTool : AgentTool {
        override val name = "read"
        override fun buildOpenAiSchema(): JSONObject = makeTool(
            name = name,
            description = "Read a UTF-8 text file in the agent workspace (or config.json / config.schema.json / acid.log) with line numbers.",
            properties = JSONObject().apply {
                put("file_path", prop("string", "Relative path in workspace/ (e.g. 'notes.txt') or 'config.json' / 'config.schema.json'."))
                put("offset", prop("integer", "1-based starting line number (default 1)."))
                put("limit", prop("integer", "Maximum lines to read (default 200)."))
            },
            required = listOf("file_path"),
        )

        override fun execute(args: JSONObject): ToolResult {
            val pathStr = args.optString("file_path", "")
            val resolved = AgentConfigStore.resolvePath(pathStr, forWrite = false)
            if (resolved is AgentConfigStore.PathResolution.Denied) return ToolResult("Error: ${resolved.reason}", true)
            val f = (resolved as AgentConfigStore.PathResolution.Allowed).file
            if (!f.exists() || !f.isFile) return ToolResult("Error: File not found: ${f.absolutePath}", true)
            if (f.length() > MAX_READ_BYTES) {
                return ToolResult("Error: File exceeds 1MB read limit (${f.length()} bytes). Inspect via bash.", true)
            }
            if (looksBinary(f)) {
                return ToolResult("Error: Binary file cannot be read as UTF-8 (${f.name}). Inspect via bash.", true)
            }
            val lines = f.readLines(Charsets.UTF_8)
            val offset = args.optInt("offset", 1).coerceAtLeast(1)
            val limit = args.optInt("limit", 200).coerceIn(1, 500)
            val startIdx = (offset - 1).coerceAtMost(lines.size)
            val endIdx = (startIdx + limit).coerceAtMost(lines.size)
            val out = buildString {
                append("File: ").append(f.absolutePath)
                append(" (lines ").append(startIdx + 1).append("..").append(endIdx).append(" of ").append(lines.size).append(")\n")
                for (i in startIdx until endIdx) {
                    append(i + 1).append(" | ").append(lines[i]).append('\n')
                }
            }
            return ToolResult(out)
        }
    }

    object WriteTool : AgentTool {
        override val name = "write"
        override fun buildOpenAiSchema(): JSONObject = makeTool(
            name = name,
            description = "Create or overwrite a UTF-8 text file inside workspace/ (or update config.json / agent/models.json).",
            properties = JSONObject().apply {
                put("file_path", prop("string", "Target path inside workspace/ or 'config.json'."))
                put("content", prop("string", "Full UTF-8 text content to write."))
            },
            required = listOf("file_path", "content"),
        )

        override fun execute(args: JSONObject): ToolResult {
            val pathStr = args.optString("file_path", "")
            if (!args.has("content")) return ToolResult("Error: Missing 'content' argument.", true)
            val content = args.optString("content", "")
            val resolved = AgentConfigStore.resolvePath(pathStr, forWrite = true)
            if (resolved is AgentConfigStore.PathResolution.Denied) return ToolResult("Error: ${resolved.reason}", true)
            val target = resolved as AgentConfigStore.PathResolution.Allowed
            return writeValidated(target.file, target.scope, content, "wrote")
        }
    }

    object EditTool : AgentTool {
        override val name = "edit"
        override fun buildOpenAiSchema(): JSONObject = makeTool(
            name = name,
            description = "Replace exact literal text in an existing UTF-8 file inside workspace/ or config.json.",
            properties = JSONObject().apply {
                put("file_path", prop("string", "Path inside workspace/ or 'config.json'."))
                put("old_string", prop("string", "Exact literal substring to replace."))
                put("new_string", prop("string", "Replacement substring."))
                put("replace_all", prop("boolean", "Replace all occurrences instead of requiring a single match (default false)."))
            },
            required = listOf("file_path", "old_string", "new_string"),
        )

        override fun execute(args: JSONObject): ToolResult {
            val pathStr = args.optString("file_path", "")
            val oldStr = args.optString("old_string", "")
            val newStr = args.optString("new_string", "")
            val replaceAll = args.optBoolean("replace_all", false)
            if (oldStr.isEmpty()) return ToolResult("Error: old_string cannot be empty.", true)

            val resolved = AgentConfigStore.resolvePath(pathStr, forWrite = true)
            if (resolved is AgentConfigStore.PathResolution.Denied) return ToolResult("Error: ${resolved.reason}", true)
            val target = resolved as AgentConfigStore.PathResolution.Allowed
            val f = target.file
            if (!f.exists() || !f.isFile) return ToolResult("Error: File not found: ${f.absolutePath}", true)
            if (looksBinary(f)) return ToolResult("Error: Binary file cannot be edited as text.", true)

            val content = f.readText(Charsets.UTF_8)
            val firstIdx = content.indexOf(oldStr)
            if (firstIdx < 0) return ToolResult("Error: old_string not found in ${f.name}", true)
            val updated = if (replaceAll) {
                content.replace(oldStr, newStr)
            } else {
                val secondIdx = content.indexOf(oldStr, firstIdx + 1)
                if (secondIdx >= 0) {
                    return ToolResult("Error: old_string appears multiple times in ${f.name}; set replace_all=true or provide more context.", true)
                }
                content.substring(0, firstIdx) + newStr + content.substring(firstIdx + oldStr.length)
            }
            return writeValidated(f, target.scope, updated, "edited")
        }
    }

    object BashTool : AgentTool {
        override val name = "bash"
        override fun buildOpenAiSchema(): JSONObject = makeTool(
            name = name,
            description = "Execute a `/system/bin/sh -c` command with working directory inside the dedicated Agent workspace (`AcidWeChat/workspace/`). Returns combined stdout/stderr and exit code.",
            properties = JSONObject().apply {
                put("command", prop("string", "Shell command to run inside AcidWeChat/workspace/."))
                put("timeout_ms", prop("integer", "Timeout in milliseconds (default 20000, max 120000)."))
            },
            required = listOf("command"),
        )

        override fun execute(args: JSONObject): ToolResult {
            val cmd = args.optString("command", "")
            if (cmd.isBlank()) return ToolResult("Error: command cannot be empty.", true)
            val timeoutMs = args.optLong("timeout_ms", 20000L).coerceIn(1000L, 120000L)
            val ws = AgentConfigStore.workspaceDir()
            var proc: Process? = null
            try {
                proc = ProcessBuilder("/system/bin/sh", "-c", cmd)
                    .directory(ws)
                    .redirectErrorStream(true)
                    .start()
                runCatching { proc.outputStream.close() }

                val baos = ByteArrayOutputStream()
                val reader = Thread {
                    runCatching {
                        val buf = ByteArray(4096)
                        proc.inputStream.use { ins ->
                            while (true) {
                                val n = ins.read(buf)
                                if (n <= 0) break
                                synchronized(baos) {
                                    val rem = MAX_BASH_CAPTURE - baos.size()
                                    if (rem > 0) baos.write(buf, 0, minOf(n, rem))
                                }
                            }
                        }
                    }
                }.apply { isDaemon = true; start() }

                val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                if (!finished) {
                    proc.destroy()
                    runCatching { reader.join(400) }
                    val partial = synchronized(baos) { String(baos.toByteArray(), Charsets.UTF_8) }
                    return ToolResult("Error: [timeout after ${timeoutMs}ms]\n$partial", true)
                }
                runCatching { reader.join(800) }
                val code = proc.exitValue()
                var out = synchronized(baos) { String(baos.toByteArray(), Charsets.UTF_8) }
                if (out.length > MAX_BASH_RETURN) {
                    out = out.take(MAX_BASH_RETURN) + "\n...[output truncated]..."
                }
                ConfigStore.reloadIfModified(force = true)
                return ToolResult("$out\n[exit code: $code]", isError = false)
            } catch (t: Throwable) {
                AcidLogger.w(TAG, "bash failed", t)
                return ToolResult("Error: bash execution failed: ${t.message}", true)
            } finally {
                runCatching { proc?.destroy() }
            }
        }
    }

    private fun writeValidated(
        file: File,
        scope: AgentConfigStore.PathScope,
        content: String,
        verb: String,
    ): ToolResult {
        if (scope == AgentConfigStore.PathScope.CONFIG_JSON || scope == AgentConfigStore.PathScope.MODELS_JSON) {
            val check = runCatching { JSONObject(content) }
            if (check.isFailure) {
                return ToolResult("Error: Invalid JSON for ${file.name}: ${check.exceptionOrNull()?.message}", true)
            }
        }
        AcidIo.writeAtomicUtf8(file, content)
        if (scope == AgentConfigStore.PathScope.CONFIG_JSON) {
            ConfigStore.reloadIfModified(force = true)
        } else if (scope == AgentConfigStore.PathScope.MODELS_JSON) {
            AgentConfigStore.getConfig()
        }
        return ToolResult("Successfully $verb ${file.absolutePath}")
    }

    private fun looksBinary(file: File): Boolean = runCatching {
        FileInputStream(file).use { ins ->
            val buf = ByteArray(1024)
            val n = ins.read(buf)
            for (i in 0 until n) {
                if (buf[i].toInt() == 0) return@runCatching true
            }
            false
        }
    }.getOrDefault(false)
}
