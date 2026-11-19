package com.acid.wechat.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.TreeMap

/**
 * OpenAI-compatible SSE streaming Chat Completions client supporting
 * `reasoning_content`, inline `<think>...</think>`, and incremental `tool_calls`.
 */
object AcidLlmClient {
    data class StreamCallbacks(
        val isCancelled: () -> Boolean,
        val onConnectionOpened: (HttpURLConnection) -> Unit,
        val onReasoningDelta: (String) -> Unit,
        val onContentDelta: (String) -> Unit,
        val onUsage: (promptTokens: Int, completionTokens: Int, totalTokens: Int) -> Unit,
    )

    private class StreamingToolCall(val index: Int) {
        var id: String = ""
        var type: String = "function"
        var name: String = ""
        val arguments = StringBuilder()
    }

    private class ThinkStreamParser {
        private val pending = StringBuilder()
        var insideThink = false
        val reasoningSb = StringBuilder()
        val contentSb = StringBuilder()

        fun feedContent(chunk: String): Pair<String, String> {
            if (chunk.isEmpty()) return "" to ""
            val rBefore = reasoningSb.length
            val cBefore = contentSb.length
            pending.append(chunk)
            drain(flushAll = false)
            return reasoningSb.substring(rBefore) to contentSb.substring(cBefore)
        }

        fun finish(): Pair<String, String> {
            val rBefore = reasoningSb.length
            val cBefore = contentSb.length
            drain(flushAll = true)
            return reasoningSb.substring(rBefore) to contentSb.substring(cBefore)
        }

        private fun drain(flushAll: Boolean) {
            val openTag = "<think>"
            val closeTag = "</think>"
            while (pending.isNotEmpty()) {
                val tag = if (insideThink) closeTag else openTag
                val target = if (insideThink) reasoningSb else contentSb
                val idx = pending.indexOf(tag)
                if (idx >= 0) {
                    if (idx > 0) target.append(pending, 0, idx)
                    pending.delete(0, idx + tag.length)
                    insideThink = !insideThink
                } else {
                    val hold = if (flushAll) 0 else partialSuffixLen(pending, tag)
                    val flush = pending.length - hold
                    if (flush > 0) {
                        target.append(pending, 0, flush)
                        pending.delete(0, flush)
                    }
                    break
                }
            }
        }

        private fun partialSuffixLen(sb: StringBuilder, tag: String): Int {
            val max = minOf(sb.length, tag.length - 1)
            for (len in max downTo 1) {
                if (sb.endsWith(tag.substring(0, len))) return len
            }
            return 0
        }
    }

    fun streamChatCompletion(
        cfg: AgentConfigStore.ModelConfig,
        messages: JSONArray,
        toolsSchema: JSONArray,
        step: Int,
        cb: StreamCallbacks,
    ): JSONObject? {
        if (cb.isCancelled()) return null
        val base = cfg.baseUrl.trim().trimEnd('/')
        val endpoint = if (base.endsWith("/chat/completions")) base else "$base/chat/completions"

        val reqBody = JSONObject().apply {
            put("model", cfg.model)
            put("messages", messages)
            if (toolsSchema.length() > 0) {
                put("tools", toolsSchema)
            }
            put("temperature", cfg.temperature)
            put("stream", true)
            put("stream_options", JSONObject().put("include_usage", true))
            val eff = AgentConfigStore.normalizeReasoningEffort(cfg.reasoningEffort)
            if (eff in setOf("low", "medium", "high")) {
                put("reasoning_effort", eff)
            }
        }

        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 90_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "text/event-stream")
            if (cfg.apiKey.isNotEmpty()) {
                setRequestProperty("Authorization", "Bearer ${cfg.apiKey}")
            }
        }
        cb.onConnectionOpened(conn)

        val parser = ThinkStreamParser()
        val toolCallsMap = TreeMap<Int, StreamingToolCall>()

        try {
            conn.outputStream.use { os ->
                os.write(reqBody.toString().toByteArray(Charsets.UTF_8))
                os.flush()
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                val errBody = runCatching { conn.errorStream?.bufferedReader()?.readText() }.getOrNull().orEmpty()
                throw IOException("HTTP $code: ${errBody.take(320)}")
            }

            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                while (true) {
                    if (cb.isCancelled()) {
                        runCatching { conn.disconnect() }
                        return null
                    }
                    val line = reader.readLine()?.trim() ?: break
                    if (line.isEmpty() || line.startsWith(":") || !line.startsWith("data:")) continue
                    val data = line.substring(5).trim()
                    if (data == "[DONE]") break

                    val chunk = runCatching { JSONObject(data) }.getOrNull() ?: continue
                    extractSseError(chunk)?.let { throw IOException(it) }

                    chunk.optJSONObject("usage")?.let { u ->
                        val pt = u.optInt("prompt_tokens", 0)
                        val ct = u.optInt("completion_tokens", 0)
                        val tt = u.optInt("total_tokens", pt + ct)
                        cb.onUsage(pt, ct, tt)
                    }

                    val delta = chunk.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta") ?: continue
                    val rChunk = if (delta.isNull("reasoning_content")) "" else delta.optString("reasoning_content", "")
                    if (rChunk.isNotEmpty()) {
                        parser.reasoningSb.append(rChunk)
                        cb.onReasoningDelta(parser.reasoningSb.toString())
                    }
                    val cChunk = if (delta.isNull("content")) "" else delta.optString("content", "")
                    if (cChunk.isNotEmpty()) {
                        val (rAdd, cAdd) = parser.feedContent(cChunk)
                        if (rAdd.isNotEmpty()) cb.onReasoningDelta(parser.reasoningSb.toString())
                        if (cAdd.isNotEmpty()) cb.onContentDelta(parser.contentSb.toString())
                    }
                    delta.optJSONArray("tool_calls")?.let { accumulateToolCalls(toolCallsMap, it) }
                }
            }
            val (rTail, cTail) = parser.finish()
            if (rTail.isNotEmpty()) cb.onReasoningDelta(parser.reasoningSb.toString())
            if (cTail.isNotEmpty()) cb.onContentDelta(parser.contentSb.toString())
        } finally {
            runCatching { conn.disconnect() }
        }

        if (cb.isCancelled()) return null

        return JSONObject().apply {
            put("role", "assistant")
            val content = parser.contentSb.toString()
            put("content", if (content.isNotEmpty()) content else JSONObject.NULL)
            val reasoning = parser.reasoningSb.toString().trim()
            if (reasoning.isNotEmpty()) put("reasoning_content", reasoning)
            if (toolCallsMap.isNotEmpty()) {
                val arr = JSONArray()
                for (tc in toolCallsMap.values) {
                    arr.put(JSONObject().apply {
                        put("id", tc.id.ifEmpty { "call_${step}_${tc.index}" })
                        put("type", tc.type.ifEmpty { "function" })
                        put("function", JSONObject().apply {
                            put("name", tc.name)
                            put("arguments", tc.arguments.toString())
                        })
                    })
                }
                put("tool_calls", arr)
            }
        }
    }

    private fun accumulateToolCalls(map: TreeMap<Int, StreamingToolCall>, deltas: JSONArray) {
        for (i in 0 until deltas.length()) {
            val tc = deltas.optJSONObject(i) ?: continue
            val idx = if (!tc.isNull("index") && tc.has("index")) tc.optInt("index", 0) else 0
            val call = map.getOrPut(idx) { StreamingToolCall(idx) }
            if (tc.has("id") && !tc.isNull("id")) {
                tc.optString("id", "").takeIf { it.isNotEmpty() && it != "null" }?.let { call.id = it }
            }
            if (tc.has("type") && !tc.isNull("type")) {
                tc.optString("type", "").takeIf { it.isNotEmpty() && it != "null" }?.let { call.type = it }
            }
            val fn = tc.optJSONObject("function") ?: continue
            if (fn.has("name") && !fn.isNull("name")) {
                val name = fn.optString("name", "")
                if (name.isNotEmpty() && name != "null") {
                    if (call.name.isEmpty()) call.name = name else if (call.name != name) call.name += name
                }
            }
            if (fn.has("arguments") && !fn.isNull("arguments")) {
                val args = fn.optString("arguments", "")
                if (args.isNotEmpty()) call.arguments.append(args)
            }
        }
    }

    private fun extractSseError(chunk: JSONObject): String? {
        if (!chunk.has("error") || chunk.isNull("error")) return null
        val err = chunk.opt("error")
        return if (err is JSONObject) {
            err.optString("message").ifBlank { err.optString("detail") }.ifBlank { err.toString() }
        } else {
            err?.toString() ?: "Unknown stream error"
        }
    }
}
