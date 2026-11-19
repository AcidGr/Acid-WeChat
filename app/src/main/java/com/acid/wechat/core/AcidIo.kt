package com.acid.wechat.core

import android.os.FileObserver
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Shared atomic UTF-8 file writer, directory stats/cleanup, and FileObserver helper.
 */
object AcidIo {
    data class DirStats(val fileCount: Int, val totalBytes: Long)
    data class CleanResult(val deletedFiles: Int, val freedBytes: Long)

    fun computeDirStats(dir: File): DirStats {
        if (!dir.exists() || !dir.isDirectory) return DirStats(0, 0L)
        var count = 0
        var bytes = 0L
        dir.walkTopDown().forEach { f ->
            if (f.isFile) {
                count++
                bytes += f.length().coerceAtLeast(0L)
            }
        }
        return DirStats(count, bytes)
    }

    fun cleanDirContents(dir: File): CleanResult {
        if (!dir.exists() || !dir.isDirectory) return CleanResult(0, 0L)
        var deletedFiles = 0
        var freedBytes = 0L
        val children = dir.listFiles() ?: return CleanResult(0, 0L)
        for (child in children) {
            child.walkBottomUp().forEach { f ->
                val isFile = f.isFile
                val len = if (isFile) f.length().coerceAtLeast(0L) else 0L
                if (runCatching { f.delete() }.getOrDefault(false) && isFile) {
                    deletedFiles++
                    freedBytes += len
                }
            }
        }
        return CleanResult(deletedFiles, freedBytes)
    }

    fun formatBytes(bytes: Long): String = when {
        bytes <= 0L -> "0 B"
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
    }
    fun writeAtomicUtf8(target: File, text: String) {
        val parent = target.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        val bytes = text.toByteArray(Charsets.UTF_8)
        val tmp = File(parent ?: File("."), "${target.name}.tmp.${System.nanoTime()}")
        try {
            FileOutputStream(tmp).use { fos ->
                fos.write(bytes)
                fos.flush()
                runCatching { fos.fd.sync() }
            }
            if (!tmp.renameTo(target)) {
                target.writeBytes(bytes)
                tmp.delete()
            }
        } catch (t: Throwable) {
            runCatching { tmp.delete() }
            throw t
        }
    }

    @Suppress("DEPRECATION")
    fun watchFile(dir: File, fileName: String, onChanged: () -> Unit): FileObserver? =
        runCatching {
            val mask = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or FileObserver.CREATE
            object : FileObserver(dir.absolutePath, mask) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == fileName) onChanged()
                }
            }.apply { startWatching() }
        }.getOrNull()
}
