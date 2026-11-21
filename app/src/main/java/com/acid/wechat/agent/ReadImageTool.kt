package com.acid.wechat.agent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.acid.wechat.agent.AgentToolRegistry.makeTool
import com.acid.wechat.agent.AgentToolRegistry.prop
import com.acid.wechat.core.HostContext
import com.acid.wechat.wechat.ChatImageRepository
import com.acid.wechat.wechat.WeChatImageDownloader
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Vision inspection tool: loads a local image file, resolves same-hash quality variants,
 * optionally taps WeChat's native downloader (`upgrade=true`) to fetch the standard mid-res
 * image (never original HD), and attaches a scaled JPEG data URL for the LLM step.
 */
object ReadImageTool : AgentTool {
    override val name = "readimage"
    override val title = "视觉识图 (readimage)"
    override val summary = "读取微信聊天图片或工作区图片内容"
    private const val MAX_DIMENSION_PX = 1024
    private const val MAX_FILE_BYTES = 25L * 1024L * 1024L
    private const val JPEG_QUALITY = 82
    private const val WXGF_CLASS = "com.tencent.mm.plugin.gif.MMWXGFJNI"

    override fun buildOpenAiSchema(): JSONObject = buildOpenAiSchema(ToolPolicy())

    override fun buildOpenAiSchema(policy: ToolPolicy): JSONObject {
        val baseDesc = if (policy.isAdmin) {
            "Read and visually inspect a local image file (WeChat chat image path from `[图片: /path/...]` or workspace image)."
        } else {
            "Read and visually inspect an image from the CURRENT chat conversation (`[图片: /path/...]` in this chat) or your sandbox."
        }
        val pathDesc = if (policy.isAdmin) {
            "Exact image file path from `[图片: /path/...]` or path relative to workspace/. Never guess a path for bare `[图片]`."
        } else {
            "Exact image path from `[图片: /path/...]` or sandbox. Never guess a path for bare `[图片]`."
        }
        return makeTool(
            name = name,
            description = baseDesc,
            properties = JSONObject().apply {
                put("file_path", prop("string", pathDesc))
                put(
                    "upgrade",
                    prop(
                        "boolean",
                        "Optional (default false). Set true ONLY if the image is a blurry thumbnail (`th_...`) and you need WeChat to load the standard mid-resolution image (never downloads original HD).",
                    ),
                )
            },
            required = listOf("file_path"),
        )
    }

    override fun execute(args: JSONObject): ToolResult = execute(args, ToolPolicy())

    override fun execute(args: JSONObject, policy: ToolPolicy): ToolResult {
        val rawPath = args.optString("file_path", "").trim()
        if (rawPath.isEmpty()) return ToolResult("Error: Missing 'file_path' for readimage.", true)
        val upgrade = args.optBoolean("upgrade", false)

        return encodeImageFile(rawPath, policy, upgrade).fold(
            onSuccess = { loaded ->
                val note = buildString {
                    if (loaded.upgradeDetail.isNotEmpty()) append(" ").append(loaded.upgradeDetail)
                    if (loaded.isThumbnail) {
                        append(" Note: Current file is a WeChat thumbnail (${loaded.origW}x${loaded.origH}); if small text or details are blurry, call readimage again with `upgrade=true` to load WeChat's mid-resolution image (original HD is never downloaded).")
                    }
                }
                ToolResult(
                    output = "Loaded image `${loaded.file.name}` (${loaded.origW}x${loaded.origH} -> ${loaded.finalW}x${loaded.finalH}, ${loaded.byteSize} bytes JPEG). Attached as image input for inspection.$note",
                    isError = false,
                    imageAttachment = ImageAttachment(
                        dataUrl = loaded.dataUrl,
                        displayPath = loaded.file.absolutePath,
                        mimeType = "image/jpeg",
                    ),
                )
            },
            onFailure = { err ->
                ToolResult("Error: ${err.message ?: "readimage failed"}", true)
            },
        )
    }

    internal data class EncodedImageResult(
        val file: File,
        val dataUrl: String,
        val origW: Int,
        val origH: Int,
        val finalW: Int,
        val finalH: Int,
        val byteSize: Int,
        val isThumbnail: Boolean,
        val upgradeDetail: String = "",
    )

    internal fun encodeImageFile(
        rawPath: String,
        policy: ToolPolicy,
        upgrade: Boolean = false,
    ): Result<EncodedImageResult> {
        var resolvedFile = resolvePermittedFile(rawPath, policy)
            ?: return Result.failure(IllegalArgumentException("Access denied for image path '$rawPath'."))

        var upgradeDetail = ""
        if (upgrade && ChatImageRepository.isThumbnailPath(resolvedFile.name)) {
            val upRes = WeChatImageDownloader.upgradeToMidImage(resolvedFile.absolutePath)
            if (upRes.ok && upRes.file != null) {
                resolvedFile = upRes.file
                upRes.record?.takeIf { it.talker.isNotBlank() }?.let { rec ->
                    if (AgentSessionStore.updateImagePath(rec.talker, rec.msgLocalId, rec.msgSvrId, resolvedFile.absolutePath)) {
                        AcidAgentLoop.notifyExternalTimelineUpdate(rec.talker)
                    }
                }
                if (upRes.upgraded) {
                    upgradeDetail = "Upgraded from thumbnail to WeChat mid-resolution image."
                }
            } else if (upRes.detail.isNotBlank()) {
                upgradeDetail = "Mid-res upgrade skipped (${upRes.detail}); falling back to thumbnail."
            }
        }

        if (!resolvedFile.exists() || !resolvedFile.isFile || !resolvedFile.canRead()) {
            return Result.failure(IllegalArgumentException("Image file not found or unreadable: ${resolvedFile.absolutePath}"))
        }
        if (resolvedFile.length() <= 0L || resolvedFile.length() > MAX_FILE_BYTES) {
            return Result.failure(IllegalArgumentException("Invalid or oversized image file (${resolvedFile.length()} bytes)."))
        }

        var decoded: Bitmap? = null
        var scaled: Bitmap? = null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(resolvedFile.absolutePath, bounds)
            var origW = bounds.outWidth
            var origH = bounds.outHeight

            if (origW > 0 && origH > 0) {
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = computeSampleSize(origW, origH, MAX_DIMENSION_PX)
                }
                decoded = BitmapFactory.decodeFile(resolvedFile.absolutePath, opts)
            } else {
                val wxgfBitmap = decodeWxgfBitmap(resolvedFile)
                if (wxgfBitmap != null) {
                    decoded = wxgfBitmap
                    origW = wxgfBitmap.width
                    origH = wxgfBitmap.height
                }
            }

            val baseBitmap = decoded
                ?: return Result.failure(IllegalArgumentException("File is not a supported decoded image format: ${resolvedFile.name}"))

            val bitmapToEncode = scaleIfNeeded(baseBitmap, MAX_DIMENSION_PX).also {
                if (it !== baseBitmap) scaled = it
            }
            val finalW = bitmapToEncode.width
            val finalH = bitmapToEncode.height

            val baos = ByteArrayOutputStream()
            bitmapToEncode.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos)
            val jpegBytes = baos.toByteArray()
            val b64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
            val isThumb = ChatImageRepository.isThumbnailPath(resolvedFile.name) ||
                (ChatImageRepository.isInsideWeChatMediaRoots(resolvedFile) && maxOf(origW, origH) <= 240)
            Result.success(
                EncodedImageResult(
                    file = resolvedFile,
                    dataUrl = "data:image/jpeg;base64,$b64",
                    origW = origW,
                    origH = origH,
                    finalW = finalW,
                    finalH = finalH,
                    byteSize = jpegBytes.size,
                    isThumbnail = isThumb,
                    upgradeDetail = upgradeDetail,
                ),
            )
        } catch (t: Throwable) {
            Result.failure(t)
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

        val inputHash = ChatImageRepository.extractImageHash(rawPath)
        if (policy.isAdmin) {
            val path = candidate.absolutePath
            val ws = runCatching { AgentConfigStore.workspaceDir().canonicalFile.absolutePath }.getOrNull()
            val data = runCatching { HostContext.getDataDir().canonicalFile.absolutePath }.getOrNull()
            val inWeChat = ChatImageRepository.isInsideWeChatMediaRoots(candidate)
            val allowed = (ws != null && path.startsWith(ws)) ||
                (data != null && path.startsWith(data)) ||
                path.startsWith("/storage/") ||
                path.startsWith("/sdcard/") ||
                inWeChat
            if (!allowed) return null
            if (inWeChat || (!candidate.exists() && inputHash != null)) {
                ChatImageRepository.resolveBestFileForInput(rawPath)?.let { return it }
            }
            return candidate
        }

        // Guest sandbox: allow timeline exact paths, timeline image hashes (across thumb/mid variants), or sandbox files.
        val sandboxRoot = runCatching {
            AgentConfigStore.guestSandboxDir(policy.currentTalker ?: "guest").canonicalFile.absolutePath
        }.getOrNull()
        val inSandbox = sandboxRoot != null && candidate.absolutePath.startsWith("$sandboxRoot/")
        if (inSandbox) return candidate
        if (candidate.absolutePath in policy.allowedImagePaths ||
            (inputHash != null && inputHash in policy.allowedImageHashes)
        ) {
            return ChatImageRepository.resolveBestFileForInput(rawPath) ?: candidate
        }
        return null
    }

    private fun decodeWxgfBitmap(file: File): Bitmap? = runCatching {
        val bytes = file.readBytes()
        if (bytes.isEmpty()) return null
        val jniCls = Class.forName(WXGF_CLASS, false, HostContext.classLoader)
        val wxam2Pic = jniCls.methods.firstOrNull {
            it.name == "wxam2PicBuf" && it.parameterTypes.size == 3
        } ?: return null
        val decoded = wxam2Pic.invoke(null, bytes, 0, 0) as? ByteArray ?: return null
        if (decoded.isEmpty()) null else BitmapFactory.decodeByteArray(decoded, 0, decoded.size)
    }.getOrNull()

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
