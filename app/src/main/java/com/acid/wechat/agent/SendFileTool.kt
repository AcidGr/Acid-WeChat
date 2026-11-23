package com.acid.wechat.agent

import android.os.Environment
import com.acid.wechat.agent.AgentToolRegistry.makeTool
import com.acid.wechat.agent.AgentToolRegistry.prop
import com.acid.wechat.agent.AgentToolRegistry.propEnum
import com.acid.wechat.core.AcidIo
import com.acid.wechat.core.ConfigStore
import com.acid.wechat.core.HostContext
import com.acid.wechat.wechat.ChatQueryRepository
import com.acid.wechat.wechat.ChatRepository
import com.acid.wechat.wechat.WeChatSender
import org.json.JSONObject
import java.io.File

/**
 * Tool `sendfile`: sends a local image (as a WeChat image bubble) or any document/file
 * (as a WeChat file attachment card) to a chat conversation.
 */
object SendFileTool : AgentTool {
    override val name = "sendfile"
    override val title = "发送图片/文件 (sendfile)"
    override val summary = "向微信聊天发送图片或文档文件"

    private const val MAX_SEND_BYTES = 100L * 1024L * 1024L
    private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "bmp")
    private val imageMarkerRegex = Regex("""^\[图片:\s*(.+?)\]$""")

    override fun buildOpenAiSchema(): JSONObject = buildOpenAiSchema(ToolPolicy())

    override fun buildOpenAiSchema(policy: ToolPolicy): JSONObject {
        val hasConcreteChat = !policy.currentTalker.isNullOrBlank() &&
            policy.currentTalker != AgentSessionModels.GLOBAL_TALKER
        val desc = when {
            !policy.isAdmin ->
                "Send an image (jpg/png/webp/bmp) or document file from the current sandbox workspace directly into the CURRENT WeChat chat."
            hasConcreteChat ->
                "Send a local image (from drawimage or workspace/storage) as an image bubble or a document/file as a file attachment into the current WeChat chat (omit 'talker' for current chat) or another specified chat."
            else ->
                "Send a local image (as a WeChat image bubble) or document/file (as a WeChat file attachment card) to a target WeChat contact or group."
        }
        val props = JSONObject().apply {
            put("path", prop("string", "Local image or file path (relative to workspace or absolute path)."))
            if (policy.isAdmin) {
                put(
                    "talker",
                    prop(
                        "string",
                        "Target contact/group username (`wxid_...`, `...@chatroom`, `filehelper`) or remark/nickname. Omit in per-chat sessions to send to current chat.",
                    ),
                )
            }
            put(
                "as_type",
                propEnum(
                    "string",
                    "'auto' sends jpg/jpeg/png/webp/bmp as image bubble and other files as file card; 'image' forces image bubble; 'file' forces file card; 'voice' converts audio (mp3/wav/m4a/ogg/flac/silk) to a WeChat voice bubble.",
                    listOf("auto", "image", "file", "voice"),
                ),
            )
            put("raw_image", prop("boolean", "Send original uncompressed image when sending an image bubble (default false)."))
            put("title", prop("string", "Optional custom filename displayed on a file attachment card."))
        }
        val required = if (policy.isAdmin && !hasConcreteChat) listOf("path", "talker") else listOf("path")
        return makeTool(
            name = name,
            description = desc,
            properties = props,
            required = required,
        )
    }

    override fun execute(args: JSONObject): ToolResult = execute(args, ToolPolicy())

    override fun execute(args: JSONObject, policy: ToolPolicy): ToolResult {
        val rawPathInput = args.optString("path", "").trim()
        if (rawPathInput.isEmpty()) return ToolResult("Error: Missing 'path' for sendfile.", true)
        val cleanPath = imageMarkerRegex.matchEntire(rawPathInput)?.groupValues?.get(1)?.trim() ?: rawPathInput

        val (targetTalker, targetDisplayName) = resolveTargetChat(args.optString("talker", "").trim(), policy)
            .getOrElse { err -> return ToolResult("Error: ${err.message}", true) }

        val file = resolveSourceFile(cleanPath, policy, targetTalker)
            .getOrElse { err -> return ToolResult("Error: ${err.message}", true) }

        if (!file.exists() || !file.isFile) {
            return ToolResult("Error: File not found: ${file.absolutePath}", true)
        }
        val len = file.length()
        if (len <= 0L) return ToolResult("Error: File is empty: ${file.name}", true)
        if (len > MAX_SEND_BYTES) {
            return ToolResult("Error: File too large (${AcidIo.formatBytes(len)}); limit is 100 MB.", true)
        }

        val asType = args.optString("as_type", "auto").trim().lowercase()
        val rawImage = args.optBoolean("raw_image", false)
        val customTitle = args.optString("title", "").trim().ifEmpty { null }
        val isImageExt = file.extension.lowercase() in IMAGE_EXTS
        val wantVoice = asType == "voice"
        val wantImage = !wantVoice && when (asType) {
            "image" -> true
            "file" -> false
            else -> isImageExt
        }

        val displayFileName = customTitle ?: file.name
        var actualMode = when {
            wantVoice -> "voice"
            wantImage -> "image"
            else -> "file"
        }
        var sendRes = when {
            wantVoice -> WeChatSender.sendVoice(targetTalker, file)
            wantImage -> {
                val token = AgentSentTracker.markSentMedia(targetTalker, AgentSentTracker.MediaKind.IMAGE, file.name)
                WeChatSender.sendImage(targetTalker, file, rawImage).also { res ->
                    if (!res.ok) AgentSentTracker.cancelSentMedia(targetTalker, token)
                }
            }
            else -> {
                val token = AgentSentTracker.markSentMedia(targetTalker, AgentSentTracker.MediaKind.FILE, displayFileName)
                WeChatSender.sendFile(targetTalker, file, customTitle).also { res ->
                    if (!res.ok) AgentSentTracker.cancelSentMedia(targetTalker, token)
                }
            }
        }

        if (!sendRes.ok && wantImage && asType == "auto") {
            val fallbackToken = AgentSentTracker.markSentMedia(targetTalker, AgentSentTracker.MediaKind.FILE, displayFileName)
            val fallback = WeChatSender.sendFile(targetTalker, file, customTitle)
            if (fallback.ok) {
                actualMode = "file"
                sendRes = fallback
            } else {
                AgentSentTracker.cancelSentMedia(targetTalker, fallbackToken)
                sendRes = WeChatSender.SendResult(false, "${sendRes.detail}; fallback sendFile failed: ${fallback.detail}")
            }
        }

        if (!sendRes.ok) {
            return ToolResult("Error: ${sendRes.detail}", true)
        }

        return ToolResult(
            JSONObject().apply {
                put("ok", true)
                put("mode", actualMode)
                put("talker", targetTalker)
                put("displayName", targetDisplayName)
                put("fileName", if (actualMode == "file") (customTitle ?: file.name) else file.name)
                put("size", AcidIo.formatBytes(len))
                put("path", file.absolutePath)
                put("detail", sendRes.detail)
            }.toString(2),
        )
    }

    private fun resolveTargetChat(talkerInput: String, policy: ToolPolicy): Result<Pair<String, String>> {
        if (!policy.isAdmin) {
            val current = policy.currentTalker?.trim().orEmpty()
            if (current.isEmpty() || current == AgentSessionModels.GLOBAL_TALKER) {
                return Result.failure(IllegalStateException("Missing current conversation scope in guest mode."))
            }
            val display = ChatRepository.resolveSenderDisplayName(current, null, null).ifBlank { current }
            return Result.success(current to display)
        }

        if (talkerInput.isNotEmpty() && talkerInput != AgentSessionModels.GLOBAL_TALKER) {
            return when (val res = ChatQueryRepository.resolveTalker(talkerInput)) {
                is ChatQueryRepository.TalkerResolution.Resolved -> Result.success(res.username to res.displayName)
                is ChatQueryRepository.TalkerResolution.Ambiguous -> {
                    val cand = res.candidates.joinToString("; ") { "${it.displayName} (${it.username})" }
                    Result.failure(IllegalArgumentException("Contact '$talkerInput' is ambiguous. Specify exact username from: $cand"))
                }
                else -> Result.failure(IllegalArgumentException("Contact '$talkerInput' not found."))
            }
        }

        val current = policy.currentTalker?.trim().orEmpty()
        if (current.isNotEmpty() && current != AgentSessionModels.GLOBAL_TALKER) {
            val display = ChatRepository.resolveSenderDisplayName(current, null, null).ifBlank { current }
            return Result.success(current to display)
        }
        return Result.failure(IllegalArgumentException("'talker' is required in Global Assistant mode."))
    }

    private fun resolveSourceFile(cleanPath: String, policy: ToolPolicy, targetTalker: String): Result<File> {
        if (!policy.isAdmin) {
            return when (val res = AgentConfigStore.resolvePath(cleanPath, forWrite = false, guestTalker = targetTalker)) {
                is AgentConfigStore.PathResolution.Allowed -> Result.success(res.file)
                is AgentConfigStore.PathResolution.Denied -> Result.failure(SecurityException(res.reason))
            }
        }

        val res = AgentConfigStore.resolvePath(cleanPath, forWrite = false, guestTalker = null)
        if (res is AgentConfigStore.PathResolution.Allowed) {
            return if (res.scope == AgentConfigStore.PathScope.WORKSPACE) {
                Result.success(res.file)
            } else {
                Result.failure(SecurityException("Refusing to send protected config/log file: ${res.file.name}"))
            }
        }

        val rawFile = File(cleanPath)
        val candidate = runCatching { rawFile.canonicalFile }.getOrElse { rawFile.absoluteFile }
        val protectedFiles = setOfNotNull(
            runCatching { AgentConfigStore.modelsFile().canonicalFile }.getOrNull(),
            runCatching { ConfigStore.configFile().canonicalFile }.getOrNull(),
            runCatching { File(HostContext.getDataDir(), "acid.log").canonicalFile }.getOrNull(),
        )
        if (candidate in protectedFiles) {
            return Result.failure(SecurityException("Refusing to send protected config/log file: ${candidate.name}"))
        }
        val extRoot = runCatching { Environment.getExternalStorageDirectory().canonicalFile }.getOrNull()
        val inExternal = rawFile.isAbsolute && (
            (extRoot != null && isInsideDir(candidate, extRoot)) ||
                candidate.path.startsWith("/storage/emulated/") ||
                candidate.path.startsWith("/sdcard/")
            )
        if (inExternal && candidate.exists() && candidate.isFile && candidate.canRead()) {
            return Result.success(candidate)
        }
        return Result.failure(
            SecurityException("Access denied or file not readable outside workspace/external storage: $cleanPath"),
        )
    }

    private fun isInsideDir(target: File, dir: File): Boolean {
        val tPath = target.path
        val dPath = dir.path
        return tPath == dPath || tPath.startsWith(dPath + File.separator)
    }
}
