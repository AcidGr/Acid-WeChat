package com.acid.wechat.core

import de.robv.android.xposed.XposedBridge
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Dual logger: forwards to XposedBridge (LSPosed logs) and keeps a structured ring buffer + file log
 * accessible via DebugServer or directly from chroot.
 */
object AcidLogger {
    private const val MAX_RING_ENTRIES = 250
    private const val MAX_LOG_FILE_BYTES = 2L * 1024 * 1024

    data class LogEntry(
        val ts: String,
        val level: String,
        val tag: String,
        val msg: String,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("ts", ts)
            put("level", level)
            put("tag", tag)
            put("msg", msg)
        }
    }

    private val ring = ConcurrentLinkedDeque<LogEntry>()
    private val fileLock = Any()
    private val dateFmt = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }

    fun i(tag: String, msg: String) = log("INFO", tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = log("WARN", tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = log("ERROR", tag, msg, t)

    private fun log(level: String, tag: String, msg: String, t: Throwable?) {
        val fullMsg = if (t != null) "$msg | ${t.javaClass.simpleName}: ${t.message}" else msg
        val ts = dateFmt.get()?.format(Date()) ?: ""
        val line = "[$ts][$level][$tag] $fullMsg"

        runCatching {
            XposedBridge.log("[AcidWeChat][$level][$tag] $fullMsg")
            if (t != null && level == "ERROR") {
                XposedBridge.log(t)
            }
        }

        ring.addLast(LogEntry(ts, level, tag, fullMsg))
        while (ring.size > MAX_RING_ENTRIES) {
            ring.pollFirst()
        }

        if (HostContext.isInitialized) {
            appendToFile(line)
        }
    }

    private fun appendToFile(line: String) {
        synchronized(fileLock) {
            runCatching {
                val file = File(HostContext.getDataDir(), "acid.log")
                if (file.exists() && file.length() > MAX_LOG_FILE_BYTES) {
                    val bak = File(HostContext.getDataDir(), "acid.log.bak")
                    if (bak.exists()) bak.delete()
                    file.renameTo(bak)
                }
                FileWriter(file, true).use { fw ->
                    fw.write(line)
                    fw.write("\n")
                }
            }
        }
    }

    fun recentJson(limit: Int = 100): JSONArray {
        val arr = JSONArray()
        val list = ring.toList()
        val start = (list.size - limit.coerceAtLeast(1)).coerceAtLeast(0)
        for (i in start until list.size) {
            arr.put(list[i].toJson())
        }
        return arr
    }
}
