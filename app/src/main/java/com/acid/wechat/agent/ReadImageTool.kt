package com.acid.wechat.agent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.acid.wechat.agent.AgentToolRegistry.makeTool
import com.acid.wechat.agent.AgentToolRegistry.prop
import com.acid.wechat.core.HostContext
import com.acid.wechat.wechat.ChatImageRepository
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Vision inspection tool: loads a local image file, scales it cleanly to a lightweight JPEG
 * data URL, and attaches it for the next LLM step without bloating long-term session history.
 */
object ReadImageTool : AgentTool {
    override val name = "readimage"
    private const val MAX_DIMENSION_PX = 1024
    private const val MAX_FILE_BYTES = 25L * 1024L * 1024L
    private const val JPEG_QUALITY = 82

    override fun buildOpenAiSchema(): JSONObject = makeTool(
        name = name,
        description = "Read and visually inspect a local image file (such as a WeChat chat image path from `[图片: /path/...]`).",
        properties = JSONObject().apply {
            put("file_path", prop("string", "Absolute image file path or path relative to workspace/."))
        },
        required = listOf("file_path"),
    )

    override fun buildOpenAiSchema(policy: ToolPolicy): JSONObject = makeTool(
        name = name,
        description = if (policy.isAdmin) {
            "Read and visually inspect a local image file (WeChat chat image path or workspace image)."
        } else {
            "Read and visually inspect an image from the CURRENT chat conversation (`[图片: /path/...]` in this chat) or your sandbox."
        },
        properties = JSONObject().apply {
            put("file_path", prop("string", "Image path from `[图片: /path/...]` or workspace."))
        },
        required = listOf("file_path"),
    )

    override fun execute(args: JSONObject): ToolResult = execute(args, ToolPolicy())

    override fun execute(args: JSONObject, policy: ToolPolicy): ToolResult {
        val rawPath = args.optString("file_path", "").trim()
        if (rawPath.isEmpty()) return ToolResult("Error: Missing 'file_path' for readimage.", true)

        val resolvedFile = resolvePermittedFile(rawPath, policy)
            ?: return ToolResult("Error: Access denied for image path '$rawPath'.", true)

        if (!resolvedFile.exists() || !resolvedFile.isFile || !resolvedFile.canRead()) {
            return ToolResult("Error: Image file not found or unreadable: ${resolvedFile.absolutePath}", true)
        }
        if (resolvedFile.length() <= 0L || resolvedFile.length() > MAX_FILE_BYTES) {
            return ToolResult("Error: Invalid or oversized image file (${resolvedFile.length()} bytes).", true)
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(resolvedFile.absolutePath, bounds)
        val origW = bounds.outWidth
        val origH = bounds.outHeight
        if (origW <= 0 || origH <= 0) {
            return ToolResult("Error: File is not a supported decoded image format: ${resolvedFile.name}", true)
        }

        var decoded: Bitmap? = null
        var scaled: Bitmap? = null
        return try {
            val opts = BitmapFactory.Options().apply {
                inSampleSize = computeSampleSize(origW, origH, MAX_DIMENSION_PX)
            }
            decoded = BitmapFactory.decodeFile(resolvedFile.absolutePath, opts)
                ?: return ToolResult("Error: Failed decoding bitmap: ${resolvedFile.name}", true)

            val bitmapToEncode = scaleIfNeeded(decoded, MAX_DIMENSION_PX).also {
                if (it !== decoded) scaled = it
            }
            val finalW = bitmapToEncode.width
            val finalH = bitmapToEncode.height

            val baos = ByteArrayOutputStream()
            bitmapToEncode.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos)
            val jpegBytes = baos.toByteArray()
            val b64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
            val dataUrl = "data:image/jpeg;base64,$b64"

            ToolResult(
                output = "Loaded image `${resolvedFile.name}` (${origW}x${origH} -> ${finalW}x${finalH}, ${jpegBytes.size} bytes JPEG). Attached as image input for inspection.",
                isError = false,
                imageAttachment = ImageAttachment(
                    dataUrl = dataUrl,
                    displayPath = resolvedFile.absolutePath,
                    mimeType = "image/jpeg",
                ),
            )
        } catch (t: Throwable) {
            ToolResult("Error: readimage failed: ${t.message ?: t.javaClass.simpleName}", true)
        } finally {
            runCatching { scaled?.recycle() }
            runCatching { decoded?.recycle() }
        }
    }

    private fun resolvePermittedFile(rawPath: String, policy: ToolPolicy): File? {
        val candidate = runCatching {
            val f = File(rawPath)
            val base = if (policy.isAdmin) {
                AgentConfigStore.workspaceDir()
            } else {
                AgentConfigStore.guestSandboxDir(policy.currentTalker ?: "guest")
            }
            (if (f.isAbsolute) f else File(base, rawPath)).canonicalFile
        }.getOrNull() ?: return null

        if (policy.isAdmin) {
            val path = candidate.absolutePath
            val ws = runCatching { AgentConfigStore.workspaceDir().canonicalFile.absolutePath }.getOrNull()
            val data = runCatching { HostContext.getDataDir().canonicalFile.absolutePath }.getOrNull()
            val allowed = (ws != null && path.startsWith(ws)) ||
                (data != null && path.startsWith(data)) ||
                path.startsWith("/storage/") ||
                path.startsWith("/sdcard/") ||
                ChatImageRepository.isInsideWeChatMediaRoots(candidate)
            return if (allowed) candidate else null
        }

        // Guest sandbox: allow only images explicitly present in this chat's timeline OR inside this chat's sandbox.
        val sandboxRoot = runCatching {
            AgentConfigStore.guestSandboxDir(policy.currentTalker ?: "guest").canonicalFile.absolutePath
        }.getOrNull()
        val inSandbox = sandboxRoot != null && candidate.absolutePath.startsWith("$sandboxRoot/")
        if (inSandbox || candidate.absolutePath in policy.allowedImagePaths) {
            return candidate
        }
        return null
    }

    private fun computeSampleSize(w: Int, h: Int, maxDim: Int): Int {
        var sample = 1
        var curW = w
        var curH = h
        while (curW > maxDim * 2 || curH > maxDim * 2) {
            curW /= 2
            curH /= 2
            sample *= 2
        }
        return sample
    }

    private fun scaleIfNeeded(src: Bitmap, maxDim: Int): Bitmap {
        val maxSide = maxOf(src.width, src.height)
        if (maxSide <= maxDim) return src
        val ratio = maxDim.toFloat() / maxSide.toFloat()
        val dstW = (src.width * ratio).toInt().coerceAtLeast(1)
        val dstH = (src.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, dstW, dstH, true)
    }
}
