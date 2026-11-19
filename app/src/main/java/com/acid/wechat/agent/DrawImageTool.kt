package com.acid.wechat.agent

import android.util.Base64
import com.acid.wechat.agent.AgentToolRegistry.makeTool
import com.acid.wechat.agent.AgentToolRegistry.prop
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Image generation / editing tool supporting configurable OpenAI-compatible
 * `/v1/chat/completions` image models (e.g. `gemini-3.1-flash-image`) and
 * `/v1/images/generations` models (e.g. `gpt-image-*`, `dall-e-*`, `hy-image-*`).
 */
object DrawImageTool : AgentTool {
    override val name = "drawimage"
    override val title = "AI 画图/改图 (drawimage)"
    override val summary = "文生图或基于原图改图"

    private const val MAX_IMAGE_DOWNLOAD_BYTES = 20 * 1024 * 1024
    private val inlineDataUrlRegex = Regex("""data:(image/[a-zA-Z0-9.+-]+);base64,([A-Za-z0-9+/=_\r\n-]+)""")

    override fun buildOpenAiSchema(): JSONObject = buildOpenAiSchema(ToolPolicy())

    override fun buildOpenAiSchema(policy: ToolPolicy): JSONObject = makeTool(
        name = name,
        description = if (policy.isAdmin) {
            "Generate a new image from text prompt or edit an existing local image (`reference_image` from chat `[图片: path]` or workspace), and save it locally. Strict limit: you may call drawimage at most ONCE per user turn (only 1 image per turn); never generate multiple images in the same turn."
        } else {
            "Generate a new image from text prompt or edit an image from the CURRENT chat (`[图片: path]`) using `reference_image`. Strict limit: you may call drawimage at most ONCE per user turn (only 1 image per turn); never generate multiple images in the same turn."
        },
        properties = JSONObject().apply {
            put("prompt", prop("string", "Detailed visual description or editing instruction for the image."))
            put("reference_image", prop("string", "Optional local image path (e.g. from `[图片: /path/...]`) to edit or transform."))
        },
        required = listOf("prompt"),
    )

    override fun execute(args: JSONObject): ToolResult = execute(args, ToolPolicy())

    override fun execute(args: JSONObject, policy: ToolPolicy): ToolResult {
        if (policy.drawImageUsedInTurn.get()) {
            return ToolResult("Error: 本轮对话已生成过一张图片（每轮限调用一次 drawimage），禁止重复生图，请直接发送或回复已生成的图片。", true)
        }
        val prompt = args.optString("prompt", "").trim()
        if (prompt.isEmpty()) return ToolResult("Error: Missing 'prompt' for drawimage.", true)
        val refPath = args.optString("reference_image", "").trim()

        val cfg = AgentConfigStore.getConfig()
        val base = cfg.imageBaseUrl.trim().trimEnd('/')
        val apiKey = cfg.imageApiKey.trim()
        val model = cfg.imageModel.trim().ifEmpty { AgentConfigStore.DEFAULT_IMAGE_MODEL }

        if (base.isEmpty()) return ToolResult("Error: 未配置画图接口地址，请先在设置中填写画图 Base URL。", true)
        if (apiKey.isEmpty() && !AgentConfigStore.isLoopbackBaseUrl(base)) {
            return ToolResult("Error: 未配置画图 API Key，请先在设置中填写。", true)
        }

        val refDataUrl = if (refPath.isNotEmpty()) {
            ReadImageTool.encodeImageFile(refPath, policy).getOrElse { err ->
                return ToolResult("Error: reference_image rejected: ${err.message}", true)
            }.dataUrl
        } else {
            null
        }

        return runCatching {
            val preferImagesApi = refDataUrl == null && (
                base.endsWith("/images/generations") ||
                    model.startsWith("gpt-image", ignoreCase = true) ||
                    model.startsWith("dall-e", ignoreCase = true) ||
                    model.startsWith("hy-image", ignoreCase = true) ||
                    model.startsWith("imagen", ignoreCase = true)
                )

            val rawImg = if (preferImagesApi) {
                callImagesGenerations(base, apiKey, model, prompt)
                    ?: callChatCompletionsImage(base, apiKey, model, prompt, null)
            } else {
                callChatCompletionsImage(base, apiKey, model, prompt, refDataUrl)
                    ?: if (refDataUrl == null) callImagesGenerations(base, apiKey, model, prompt) else null
            }

            if (rawImg == null) {
                return@runCatching ToolResult("Error: 画图接口未返回有效图片数据（请检查模型名称与接口地址是否匹配）。", true)
            }

            val outDir = if (policy.isAdmin) {
                File(AgentConfigStore.workspaceDir(), "images")
            } else {
                AgentConfigStore.guestSandboxDir(policy.currentTalker ?: "guest")
            }
            if (!outDir.exists()) outDir.mkdirs()

            val ext = when {
                rawImg.mime.contains("png", ignoreCase = true) -> "png"
                rawImg.mime.contains("webp", ignoreCase = true) -> "webp"
                else -> "jpg"
            }
            val outFile = File(outDir, "draw_${System.currentTimeMillis()}.$ext")
            outFile.writeBytes(rawImg.bytes)
            policy.drawImageUsedInTurn.set(true)
            val kb = (outFile.length() / 1024L).coerceAtLeast(1L)
            val sendHint = if ("sendfile" in policy.enabledTools) {
                "（如需将该图片发到微信聊天，请调用 sendfile 工具发送此路径）"
            } else {
                ""
            }
            ToolResult("已生成图片并保存: [图片: ${outFile.absolutePath}] (${kb} KB)$sendHint")
        }.getOrElse { err ->
            ToolResult("Error: drawimage failed: ${err.message ?: err.javaClass.simpleName}", true)
        }
    }

    private data class DecodedImageBytes(val mime: String, val bytes: ByteArray)

    private fun callChatCompletionsImage(
        base: String,
        apiKey: String,
        model: String,
        prompt: String,
        refDataUrl: String?,
    ): DecodedImageBytes? {
        val endpoint = AcidLlmClient.resolveOpenAiEndpoint(base, "/chat/completions")
        val content: Any = if (refDataUrl != null) {
            JSONArray().apply {
                put(JSONObject().put("type", "text").put("text", prompt))
                put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", refDataUrl)))
            }
        } else {
            prompt
        }
        val req = JSONObject().apply {
            put("model", model)
            put("stream", false)
            put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
        }
        val resp = AcidLlmClient.postJson(endpoint, apiKey, req, readTimeoutMs = 120_000)
        if (resp.code !in 200..299) {
            val lower = resp.body.lowercase()
            if (lower.contains("/v1/images/generations") || resp.code in setOf(404, 405)) {
                return null
            }
            throw IllegalStateException("HTTP ${resp.code}: ${resp.body.take(280)}")
        }
        val json = JSONObject(resp.body)
        val msg = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            ?: return null

        msg.optJSONArray("images")?.let { imgs ->
            for (i in 0 until imgs.length()) {
                val url = imgs.optJSONObject(i)?.optJSONObject("image_url")?.optString("url", "")?.trim().orEmpty()
                if (url.isNotEmpty()) decodeImageRef(url)?.let { return it }
            }
        }

        val rawContent = msg.opt("content")
        if (rawContent is JSONArray) {
            for (i in 0 until rawContent.length()) {
                val part = rawContent.optJSONObject(i) ?: continue
                val url = part.optJSONObject("image_url")?.optString("url", "")?.trim().orEmpty()
                if (url.isNotEmpty()) decodeImageRef(url)?.let { return it }
            }
        } else if (rawContent is String) {
            decodeImageRef(rawContent)?.let { return it }
        }
        return null
    }

    private fun callImagesGenerations(
        base: String,
        apiKey: String,
        model: String,
        prompt: String,
    ): DecodedImageBytes? {
        val endpoint = AcidLlmClient.resolveOpenAiEndpoint(base, "/images/generations")
        val req = JSONObject().apply {
            put("model", model)
            put("prompt", prompt)
            put("n", 1)
        }
        val resp = AcidLlmClient.postJson(endpoint, apiKey, req, readTimeoutMs = 120_000)
        if (resp.code !in 200..299) {
            if (resp.code in setOf(404, 405)) return null
            throw IllegalStateException("HTTP ${resp.code}: ${resp.body.take(280)}")
        }
        val dataArr = JSONObject(resp.body).optJSONArray("data") ?: return null
        val first = dataArr.optJSONObject(0) ?: return null
        val b64 = first.optString("b64_json", "").trim()
        if (b64.isNotEmpty()) {
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            if (bytes.isNotEmpty()) return DecodedImageBytes("image/png", bytes)
        }
        val url = first.optString("url", "").trim()
        if (url.isNotEmpty()) return decodeImageRef(url)
        return null
    }

    private fun decodeImageRef(ref: String): DecodedImageBytes? {
        val m = inlineDataUrlRegex.find(ref)
        if (m != null) {
            val mime = m.groupValues[1]
            val cleaned = m.groupValues[2].replace("\n", "").replace("\r", "")
            val bytes = Base64.decode(cleaned, Base64.DEFAULT)
            if (bytes.isNotEmpty()) return DecodedImageBytes(mime, bytes)
        }
        if (ref.startsWith("http://", ignoreCase = true) || ref.startsWith("https://", ignoreCase = true)) {
            return downloadBytes(ref)
        }
        return null
    }

    private fun downloadBytes(url: String): DecodedImageBytes? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 45_000
        }
        return try {
            if (conn.responseCode !in 200..299) return null
            val mime = conn.contentType ?: "image/jpeg"
            val bytes = conn.inputStream.use { ins ->
                val baos = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    total += n
                    if (total > MAX_IMAGE_DOWNLOAD_BYTES) {
                        throw IllegalStateException("Image exceeds ${MAX_IMAGE_DOWNLOAD_BYTES / (1024 * 1024)} MB limit")
                    }
                    baos.write(buf, 0, n)
                }
                baos.toByteArray()
            }
            if (bytes.isEmpty()) null else DecodedImageBytes(mime, bytes)
        } finally {
            runCatching { conn.disconnect() }
        }
    }
}
