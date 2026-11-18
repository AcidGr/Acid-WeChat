package com.acid.wechat.debug

import com.acid.wechat.core.AcidLogger
import com.acid.wechat.core.ConfigStore
import com.acid.wechat.core.DexKitLocator
import com.acid.wechat.core.FeatureRegistry
import com.acid.wechat.core.HostContext
import com.acid.wechat.core.WcdbBridge
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Loopback-only (127.0.0.1) HTTP debug server for AI/CLI inspection and live toggling.
 */
object DebugServer {
    private const val TAG = "DebugServer"
    private val running = AtomicBoolean(false)
    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "AcidDebugHttp").apply { isDaemon = true }
    }

    @Volatile
    var boundPort: Int = -1
        private set

    fun startIfEnabled() {
        if (!ConfigStore.getBoolean(ConfigStore.KEY_DEBUG_SERVER, true)) {
            AcidLogger.i(TAG, "DebugServer disabled in config")
            return
        }
        if (!running.compareAndSet(false, true)) return

        val port = ConfigStore.getInt(ConfigStore.KEY_DEBUG_PORT, 19898)
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
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                val requestLine = reader.readLine() ?: return
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }

                val parts = requestLine.split(" ")
                val method = parts.getOrNull(0)?.uppercase() ?: "GET"
                val rawTarget = parts.getOrNull(1) ?: "/"
                val path = rawTarget.substringBefore("?")
                val query = parseQuery(rawTarget.substringAfter("?", ""))

                val (statusCode, body) = route(method, path, query)
                writeJsonResponse(s, statusCode, body)
            }
        }
    }

    private fun route(method: String, path: String, query: Map<String, String>): Pair<Int, String> {
        return when (path) {
            "/", "/health" -> 200 to JSONObject().apply {
                put("ok", true)
                put("moduleVersion", HostContext.MODULE_VERSION_NAME)
                put("moduleVersionCode", HostContext.MODULE_VERSION_CODE)
                put("hostPackage", HostContext.WECHAT_PACKAGE)
                put("hostVersionName", HostContext.hostVersionName)
                put("hostVersionCode", HostContext.hostVersionCode)
                put("classLoader", HostContext.classLoader.javaClass.name)
                put("uptimeSec", (System.currentTimeMillis() - HostContext.startedAtMs) / 1000L)
                put("dataDir", HostContext.getDataDir().absolutePath)
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
                if (method != "POST" && query["confirm"] != "1") {
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

            "/features/toggle" -> {
                val id = query["id"]
                val enabledStr = query["enabled"]
                if (id.isNullOrBlank() || enabledStr.isNullOrBlank()) {
                    400 to """{"error":"Missing id or enabled query param"}"""
                } else {
                    val enabled = enabledStr.equals("true", ignoreCase = true) || enabledStr == "1"
                    val ok = FeatureRegistry.setEnabled(id, enabled)
                    if (ok) {
                        200 to JSONObject().apply {
                            put("ok", true)
                            put("id", id)
                            put("enabled", enabled)
                        }.toString(2)
                    } else {
                        404 to """{"error":"Unknown feature id"}"""
                    }
                }
            }

            "/config" -> {
                val key = query["key"]
                val value = query["value"]
                if (method == "POST" && !key.isNullOrBlank() && value != null) {
                    if (value.equals("true", true) || value.equals("false", true)) {
                        ConfigStore.putBoolean(key, value.equals("true", true))
                    } else if (value.toIntOrNull() != null) {
                        ConfigStore.putInt(key, value.toInt())
                    } else {
                        ConfigStore.putString(key, value)
                    }
                }
                200 to ConfigStore.snapshot().toString(2)
            }

            "/logs" -> {
                val limit = query["limit"]?.toIntOrNull()?.coerceIn(1, 250) ?: 100
                200 to JSONObject().apply {
                    put("logs", AcidLogger.recentJson(limit))
                }.toString(2)
            }

            else -> 404 to """{"error":"Not found","endpoints":["/health","/dexkit","/dexkit/rescan","/features","/features/toggle","/config","/logs"]}"""
        }
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
