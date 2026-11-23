package com.acid.wechat.wechat

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Process
import com.acid.wechat.core.AcidLogger
import com.acid.wechat.core.HostContext
import me.yun.silk.SilkCodec
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipFile

/**
 * Loads `libsilk_codec.so` from the Xposed module APK and converts MP3/common audio files
 * into WeChat-compatible 24kHz SILK v3 voice files (with Android `MediaCodec` PCM fallback).
 */
object SilkAudioConverter {
    private const val TAG = "SilkAudioConverter"
    private const val SILK_SO_NAME = "libsilk_codec.so"
    private const val SILK_SAMPLE_RATE = 24_000
    private const val TYPE_UNKNOWN = 0
    private const val TYPE_SILK = 1
    private const val TYPE_MP3 = 2
    private const val TYPE_WAV = 3
    private const val TYPE_FLAC = 4
    private const val TYPE_OGG = 5

    @Volatile private var nativeLoaded = false
    private val loadLock = Any()

    data class AudioInfo(
        val fileType: Int,
        val isSilk: Boolean,
        val isAmr: Boolean,
        val durationMs: Int,
    )

    data class PreparedSilk(
        val silkFile: File,
        val tempFile: File?,
        val durationMs: Int,
        val isAmr: Boolean,
    ) {
        fun cleanup() {
            tempFile?.takeIf { it.exists() }?.let { f ->
                runCatching { f.delete() }
            }
        }
    }

    fun ensureNativeLoaded(): Result<Unit> {
        if (nativeLoaded) return Result.success(Unit)
        synchronized(loadLock) {
            if (nativeLoaded) return Result.success(Unit)
            if (!Process.is64Bit()) {
                return Result.failure(IllegalStateException("仅支持 ARM64 微信进程加载语音编码器"))
            }
            return runCatching {
                val apkPath = resolveModuleApkPath()
                if (!apkPath.isNullOrBlank()) {
                    loadFromModuleApk(apkPath)
                } else {
                    System.loadLibrary("silk_codec")
                }
                nativeLoaded = true
            }.onFailure { err ->
                AcidLogger.w(TAG, "load libsilk_codec.so failed", err)
            }
        }
    }

    fun inspectAudio(file: File): AudioInfo {
        val loaded = ensureNativeLoaded().isSuccess
        val path = file.absolutePath
        val type = if (loaded) runCatching { SilkCodec().getFileType(path) }.getOrDefault(TYPE_UNKNOWN) else TYPE_UNKNOWN
        val isSilk = type == TYPE_SILK || hasSilkHeader(file)
        val isAmr = !isSilk && hasAmrHeader(file)
        val durationMs = detectDurationMs(path, isSilk, loaded)
        return AudioInfo(
            fileType = type,
            isSilk = isSilk,
            isAmr = isAmr,
            durationMs = durationMs,
        )
    }

    fun prepareSilk(source: File): Result<PreparedSilk> {
        if (!source.exists() || !source.isFile || source.length() <= 0L) {
            return Result.failure(IllegalArgumentException("音频文件不存在或为空"))
        }
        ensureNativeLoaded().onFailure { return Result.failure(it) }

        val info = inspectAudio(source)
        if (info.isSilk || info.isAmr) {
            return Result.success(
                PreparedSilk(
                    silkFile = source,
                    tempFile = null,
                    durationMs = info.durationMs,
                    isAmr = info.isAmr,
                ),
            )
        }

        val cacheDir = File(HostContext.app.cacheDir, "acid_voice_tmp").apply { mkdirs() }
        val outSilk = File(cacheDir, "voice_${System.currentTimeMillis()}_${System.nanoTime() and 0xFFFF}.silk")
        val codec = SilkCodec()

        var code = when (info.fileType) {
            TYPE_MP3 -> runCatching { codec.mp3ToSilk(source.absolutePath, outSilk.absolutePath, SILK_SAMPLE_RATE) }.getOrDefault(-301)
            TYPE_WAV, TYPE_FLAC, TYPE_OGG -> runCatching { codec.autoToSilk(source.absolutePath, outSilk.absolutePath, SILK_SAMPLE_RATE) }.getOrDefault(-2)
            else -> -1
        }

        if (code != 0 || !outSilk.isFile || outSilk.length() <= 0L) {
            runCatching { outSilk.delete() }
            val fallbackRes = convertViaMediaCodecPcm(source, outSilk, codec)
            if (fallbackRes.isFailure) {
                runCatching { outSilk.delete() }
                val detail = fallbackRes.exceptionOrNull()?.message ?: "code=$code"
                return Result.failure(IllegalStateException("音频转码 SILK 失败: $detail"))
            }
            code = 0
        }

        if (code != 0 || !outSilk.isFile || outSilk.length() <= 0L) {
            runCatching { outSilk.delete() }
            return Result.failure(IllegalStateException("音频转码 SILK 失败 (code=$code)"))
        }

        val silkDuration = detectDurationMs(outSilk.absolutePath, isSilk = true, nativeReady = true)
            .takeIf { it > 0 } ?: info.durationMs
        return Result.success(
            PreparedSilk(
                silkFile = outSilk,
                tempFile = outSilk,
                durationMs = silkDuration.coerceAtLeast(1000),
                isAmr = false,
            ),
        )
    }

    private fun convertViaMediaCodecPcm(source: File, outSilk: File, codec: SilkCodec): Result<Unit> {
        val pcmFile = File(outSilk.parentFile, "${outSilk.nameWithoutExtension}.pcm")
        return runCatching {
            val (sampleRate, channels) = decodeAudioToPcm(source, pcmFile)
            if (!pcmFile.isFile || pcmFile.length() <= 0L) {
                error("MediaCodec 未解码出有效 PCM 数据")
            }
            val code = codec.pcmToSilk(pcmFile.absolutePath, outSilk.absolutePath, SILK_SAMPLE_RATE, sampleRate, channels)
            if (code != 0 || !outSilk.isFile || outSilk.length() <= 0L) {
                error("pcmToSilk 返回错误码 $code")
            }
        }.also {
            runCatching { pcmFile.delete() }
        }
    }

    private fun decodeAudioToPcm(source: File, outPcm: File): Pair<Int, Int> {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(source.absolutePath)
            var audioTrack = -1
            var trackFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    audioTrack = i
                    trackFormat = fmt
                    break
                }
            }
            if (audioTrack < 0 || trackFormat == null) error("文件不包含音频轨")
            val mime = trackFormat.getString(MediaFormat.KEY_MIME).orEmpty()
            var sampleRate = trackFormat.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, 44_100)
            var channels = trackFormat.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 1).coerceIn(1, 2)

            extractor.selectTrack(audioTrack)
            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(trackFormat, null, null, 0)
                start()
            }

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            val deadlineMs = System.currentTimeMillis() + 30_000L

            FileOutputStream(outPcm).use { fos ->
                while (!outputDone) {
                    if (System.currentTimeMillis() > deadlineMs) error("音频解码超时")
                    if (!inputDone) {
                        val inIdx = codec.dequeueInputBuffer(10_000L)
                        if (inIdx >= 0) {
                            val buf = codec.getInputBuffer(inIdx)!!
                            val sampleSize = extractor.readSampleData(buf, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val outIdx = codec.dequeueOutputBuffer(info, 10_000L)
                    when {
                        outIdx >= 0 -> {
                            if (info.size > 0) {
                                val outBuf = codec.getOutputBuffer(outIdx)
                                if (outBuf != null) {
                                    outBuf.position(info.offset)
                                    outBuf.limit(info.offset + info.size)
                                    val bytes = ByteArray(info.size)
                                    outBuf.get(bytes)
                                    fos.write(bytes)
                                }
                            }
                            codec.releaseOutputBuffer(outIdx, false)
                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                outputDone = true
                            }
                        }
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val outFmt = codec.outputFormat
                            sampleRate = outFmt.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                            channels = outFmt.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, channels).coerceIn(1, 2)
                        }
                    }
                }
                fos.flush()
            }
            return sampleRate to channels
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun MediaFormat.getIntegerOrDefault(key: String, default: Int): Int =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(default) else default

    private fun detectDurationMs(path: String, isSilk: Boolean, nativeReady: Boolean): Int {
        if (isSilk && nativeReady) {
            val silkDur = runCatching { SilkCodec().getDuration(path) }.getOrDefault(0L)
            if (silkDur > 0L) return silkDur.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            val raw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            raw?.toLongOrNull()?.takeIf { it > 0L }?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: 1000
        } catch (_: Throwable) {
            if (nativeReady) {
                runCatching { SilkCodec().getDuration(path) }.getOrDefault(0L)
                    .takeIf { it > 0L }?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: 1000
            } else {
                1000
            }
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun hasSilkHeader(file: File): Boolean = runCatching {
        FileInputStream(file).use { ins ->
            val buf = ByteArray(10)
            val n = ins.read(buf)
            if (n < 9) return@runCatching false
            val s = String(buf, 0, n, Charsets.US_ASCII)
            s.startsWith("#!SILK_V3") || s.startsWith("\u0002#!SILK_V3")
        }
    }.getOrDefault(false)

    private fun hasAmrHeader(file: File): Boolean = runCatching {
        FileInputStream(file).use { ins ->
            val buf = ByteArray(6)
            val n = ins.read(buf)
            n >= 6 && String(buf, 0, n, Charsets.US_ASCII).startsWith("#!AMR")
        }
    }.getOrDefault(false)

    private fun resolveModuleApkPath(): String? {
        HostContext.modulePath.takeIf { it.isNotBlank() && File(it).isFile }?.let { return it }
        val clText = SilkCodec::class.java.classLoader?.toString().orEmpty()
        val idx = clText.indexOf("module=")
        if (idx >= 0) {
            val start = idx + 7
            val end = listOf(clText.indexOf(',', start), clText.indexOf(']', start))
                .filter { it > start }.minOrNull() ?: clText.length
            val candidate = clText.substring(start, end).trim()
            if (File(candidate).isFile) return candidate
        }
        return null
    }

    private fun loadFromModuleApk(apkPath: String) {
        val entryName = "lib/arm64-v8a/$SILK_SO_NAME"
        ZipFile(apkPath).use { apk ->
            val entry = apk.getEntry(entryName) ?: run {
                System.loadLibrary("silk_codec")
                return
            }
            val cacheDir = File(HostContext.app.cacheDir, "acid_native/arm64-v8a").apply { mkdirs() }
            val dest = File(cacheDir, "libsilk_codec-${java.lang.Long.toHexString(entry.crc)}.so")
            if (!dest.isFile || dest.length() != entry.size || dest.length() <= 0L) {
                val tmp = File(cacheDir, "${dest.name}.tmp.${System.nanoTime()}")
                try {
                    apk.getInputStream(entry).use { ins ->
                        FileOutputStream(tmp).use { fos -> ins.copyTo(fos) }
                    }
                    if (!tmp.renameTo(dest)) {
                        tmp.copyTo(dest, overwrite = true)
                        tmp.delete()
                    }
                } catch (t: Throwable) {
                    runCatching { tmp.delete() }
                    throw t
                }
            }
            cacheDir.listFiles()?.forEach { f ->
                if (f.name.startsWith("libsilk_codec-") && f.name.endsWith(".so") && f != dest) {
                    runCatching { f.delete() }
                }
            }
            System.load(dest.absolutePath)
        }
    }
}
