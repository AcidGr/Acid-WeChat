package com.acid.wechat.debug

import com.acid.wechat.core.AcidLogger
import com.acid.wechat.core.ConfigSchema
import com.acid.wechat.core.ConfigStore
import com.acid.wechat.core.DexKitLocator
import com.acid.wechat.core.FeatureRegistry
import com.acid.wechat.core.HostContext
import com.acid.wechat.core.WcdbBridge
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Loopback-only (127.0.0.1) HTTP debug server for AI/CLI inspection and live configuration.
 */
object DebugServer {
    private const val TAG = "DebugServer"
    private const val MAX_BODY_BYTES = 64 * 1024

    private val running = AtomicBoolean(false)
    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "AcidDebugHttp").apply { isDaemon = true }
    }

    @Volatile
    var boundPort: Int = -1
        private set

    private data class HttpRequest(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val body: String,
    )

    fun startIfEnabled() {
        if (!ConfigStore.getBoolean(ConfigSchema.DEBUG_SERVER)) {
            AcidLogger.i(TAG, "DebugServer disabled in config")
            return
        }
        if (!running.compareAndSet(false, true)) return

        val port = ConfigStore.getInt(ConfigSchema.DEBUG_PORT)
        executor.execute {
            runCatching {
                val loopback = InetAddress.getByName("127.0.0.1")
                val server = ServerSocket(port, 16, loopback)
                boundPort = server.localPort
                AcidLogger.i(TAG, "DebugServer listening on http://127.0.0.1:$boundPort")
                while (running.get()) {
                    val client = server.accept()
                    executor.execute { handleClient(client) }
                }
            }.onFailure {
                running.set(false)
                AcidLogger.w(TAG, "DebugServer failed on port $port: ${it.message}")
            }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { s ->
            runCatching {
                s.soTimeout = 4000
                val req = readHttpRequest(s.getInputStream()) ?: return
                val (statusCode, body) = route(req)
                writeJsonResponse(s, statusCode, body)
            }
        }
    }

    private fun readHttpRequest(rawIn: InputStream): HttpRequest? {
        val input = BufferedInputStream(rawIn)
        val requestLine = readAsciiLine(input) ?: return null
        var contentLength = 0
        while (true) {
            val line = readAsciiLine(input) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0 && line.substring(0, colon).trim().equals("Content-Length", ignoreCase = true)) {
                contentLength = line.substring(colon + 1).trim().toIntOrNull()?.coerceIn(0, MAX_BODY_BYTES) ?: 0
            }
        }

        val bodyText = if (contentLength > 0) {
            val buf = ByteArray(contentLength)
            var off = 0
            while (off < contentLength) {
                val n = input.read(buf, off, contentLength - off)
                if (n <= 0) break
                off += n
            }
            String(buf, 0, off, Charsets.UTF_8)
        } else {
            ""
        }

        val parts = requestLine.split(" ")
        val method = parts.getOrNull(0)?.uppercase() ?: "GET"
        val rawTarget = parts.getOrNull(1) ?: "/"
        val path = rawTarget.substringBefore("?")
        val query = parseQuery(rawTarget.substringAfter("?", ""))
        return HttpRequest(method, path, query, bodyText)
    }

    private fun readAsciiLine(input: InputStream): String? {
        val out = ByteArrayOutputStream(128)
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toString("ISO_8859_1")
            if (b == '\n'.code) break
            if (b != '\r'.code) out.write(b)
        }
        return out.toString("ISO_8859_1")
    }

    private fun route(req: HttpRequest): Pair<Int, String> {
        return when (req.path) {
            "/", "/health" -> 200 to JSONObject().apply {
                put("ok", true)
                put("moduleVersion", HostContext.MODULE_VERSION_NAME)
                put("moduleVersionCode", HostContext.MODULE_VERSION_CODE)
                put("hostPackage", HostContext.hostPackage)
                put("hostVersionName", HostContext.hostVersionName)
                put("hostVersionCode", HostContext.hostVersionCode)
                put("classLoader", HostContext.classLoader.javaClass.name)
                put("uptimeSec", (System.currentTimeMillis() - HostContext.startedAtMs) / 1000L)
                put("dataDir", HostContext.getDataDir().absolutePath)
                put("configFile", ConfigStore.configFile().absolutePath)
                put("debugPort", boundPort)
                put("wcdb", JSONObject().apply {
                    put("insertHooks", WcdbBridge.hookedInsertCount)
                    put("updateHooks", WcdbBridge.hookedUpdateCount)
                    put("hasDbInstance", WcdbBridge.lastDb != null)
                    put("hasMainDb", WcdbBridge.mainDb != null)
                })
            }.toString(2)

            "/dexkit" -> 200 to DexKitLocator.snapshotJson().toString(2)

            "/dexkit/rescan" -> {
                if (req.method != "POST" && req.query["confirm"] != "1") {
                    405 to """{"error":"Use POST /dexkit/rescan"}"""
                } else {
                    DexKitLocator.clearCache()
                    DexKitLocator.resolveAll(forceRescan = true)
                    200 to DexKitLocator.snapshotJson().toString(2)
                }
            }

            "/features" -> 200 to JSONObject().apply {
                put("features", FeatureRegistry.snapshotJson())
            }.toString(2)

            "/features/toggle" -> handleFeatureToggle(req.query)

            "/config" -> handleConfig(req)

            "/config/schema" -> 200 to ConfigSchema.schemaSnapshotJson(ConfigStore.configFile()).toString(2)

            "/config/reset" -> {
                if (req.method != "POST") {
                    405 to """{"error":"Use POST /config/reset"}"""
                } else {
                    200 to ConfigStore.resetToDefaults().toString(2)
                }
            }

            "/logs" -> {
                val limit = req.query["limit"]?.toIntOrNull()?.coerceIn(1, 250) ?: 100
                200 to JSONObject().apply {
                    put("logs", AcidLogger.recentJson(limit))
                }.toString(2)
            }

            else -> 404 to """{"error":"Not found","endpoints":["/health","/dexkit","/dexkit/rescan","/features","/features/toggle","/config","/config/schema","/config/reset","/logs"]}"""
        }
    }

    private fun handleFeatureToggle(query: Map<String, String>): Pair<Int, String> {
        val id = query["id"]
        val enabledStr = query["enabled"]
        if (id.isNullOrBlank() || enabledStr.isNullOrBlank()) {
            return 400 to """{"error":"Missing id or enabled query param"}"""
        }
        val enabled = enabledStr.equals("true", ignoreCase = true) || enabledStr == "1"
        val ok = FeatureRegistry.setEnabled(id, enabled)
        return if (ok) {
            200 to JSONObject().apply {
                put("ok", true)
                put("id", id)
                put("enabled", enabled)
            }.toString(2)
        } else {
            404 to """{"error":"Unknown feature id"}"""
        }
    }

    private fun handleConfig(req: HttpRequest): Pair<Int, String> {
        if (req.method == "POST" || req.method == "PUT") {
            if (req.body.isNotBlank()) {
                return runCatching {
                    val patch = JSONObject(req.body)
                    ConfigStore.applyPatch(patch)
                    200 to ConfigStore.snapshot().toString(2)
                }.getOrElse { err ->
                    400 to JSONObject().put("error", err.message ?: "Invalid config JSON").toString(2)
                }
            }
            val key = req.query["key"]
            val value = req.query["value"]
            if (!key.isNullOrBlank() && value != null) {
                if (!ConfigStore.setFromSchema(key, value)) {
                    return 400 to JSONObject().put("error", "Unknown key or invalid value for '$key'").toString(2)
                }
            }
        }
        return 200 to ConfigStore.snapshot().toString(2)
    }

    private fun parseQuery(qs: String): Map<String, String> {
        if (qs.isBlank()) return emptyMap()
        val map = LinkedHashMap<String, String>()
        for (pair in qs.split("&")) {
            val idx = pair.indexOf('=')
            if (idx <= 0) continue
            val k = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
            val v = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
            map[k] = v
        }
        return map
    }

    private fun writeJsonResponse(socket: Socket, statusCode: Int, jsonBody: String) {
        val statusText = when (statusCode) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            else -> "Error"
        }
        val bytes = jsonBody.toByteArray(Charsets.UTF_8)
        val headers = "HTTP/1.1 $statusCode $statusText\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        val out = socket.getOutputStream()
        out.write(headers.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }
}
