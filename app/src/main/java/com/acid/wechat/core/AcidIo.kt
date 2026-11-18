package com.acid.wechat.core

import android.os.FileObserver
import java.io.File
import java.io.FileOutputStream

/**
 * Shared atomic UTF-8 file writer and directory FileObserver helper.
 */
object AcidIo {
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
