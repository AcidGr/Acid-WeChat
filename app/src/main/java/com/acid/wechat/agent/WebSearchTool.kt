package com.acid.wechat.agent

import com.acid.wechat.agent.AgentToolRegistry.makeTool
import com.acid.wechat.agent.AgentToolRegistry.prop
import org.json.JSONArray
import org.json.JSONObject

/**
 * Web search tool supporting DeepSeek `/v1/tools/search`, OpenAI/CPA `/v1/responses` (`web_search`),
 * search-enabled `/chat/completions`, Tavily (`/search`), and Serper (`google.serper.dev`).
 */
object WebSearchTool : AgentTool {
    override val name = "websearch"
    override val title = "联网搜索 (websearch)"
    override val summary = "联网搜索实时资讯"

    override fun buildOpenAiSchema(): JSONObject = makeTool(
        name = name,
        description = "Search the live internet for recent events, facts, prices, weather, news, or documentation. Returns a concise summary and source URLs.",
        properties = JSONObject().apply {
            put("query", prop("string", "Search query or question in natural language."))
            put("max_results", prop("integer", "Maximum number of source results to include (default 5)."))
        },
        required = listOf("query"),
    )

    override fun execute(args: JSONObject): ToolResult = execute(args, ToolPolicy())

    override fun execute(args: JSONObject, policy: ToolPolicy): ToolResult {
        val query = args.optString("query", "").trim()
        if (query.isEmpty()) return ToolResult("Error: Missing 'query' for websearch.", true)
        val maxResults = args.optInt("max_results", 5).coerceIn(1, 10)

        val cfg = AgentConfigStore.getConfig()
        val base = cfg.webSearchBaseUrl.trim().trimEnd('/')
        val apiKey = cfg.webSearchApiKey.trim()
        val model = cfg.webSearchModel.trim()

        if (base.isEmpty()) return ToolResult("Error: 未配置搜索接口地址，请先在设置中填写搜索 Base URL。", true)
        if (apiKey.isEmpty() && !AgentConfigStore.isLoopbackBaseUrl(base)) {
            return ToolResult("Error: 未配置搜索 API Key，请在主页设置中填写。", true)
        }

        return runCatching {
            when {
                base.contains("tavily.com", ignoreCase = true) ||
                    (base.endsWith("/search", ignoreCase = true) &&
                        !base.endsWith("/tools/search", ignoreCase = true) &&
                        !base.contains("serper.dev", ignoreCase = true)) ->
                    callTavily(base, apiKey, query, maxResults)
                base.contains("serper.dev", ignoreCase = true) ->
                    callSerper(base, apiKey, query, maxResults)
                base.contains("deepseek.com", ignoreCase = true) ||
                    base.endsWith("/tools/search", ignoreCase = true) ||
                    model.isBlank() ->
                    searchViaDeepSeekTools(base, apiKey, query, maxResults).second
                else ->
                    callOpenAiOrResponses(base, apiKey, model, query, maxResults)
            }
        }.getOrElse { err ->
            ToolResult("Error: websearch failed: ${err.message ?: err.javaClass.simpleName}", true)
        }
    }

    private fun callTavily(base: String, apiKey: String, query: String, maxResults: Int): ToolResult {
        val endpoint = if (base.endsWith("/search", ignoreCase = true)) base else "$base/search"
        val body = JSONObject().apply {
            put("api_key", apiKey)
            put("query", query)
            put("include_answer", true)
            put("max_results", maxResults)
        }
        val resp = AcidLlmClient.postJson(endpoint, apiKey = "", body = body, readTimeoutMs = 45_000)
        if (resp.code !in 200..299) {
            return ToolResult("Error: Tavily HTTP ${resp.code}: ${resp.body.take(280)}", true)
        }
        val json = JSONObject(resp.body)
        val sb = StringBuilder()
        val answer = json.optString("answer", "").trim()
        if (answer.isNotEmpty() && answer != "null") sb.append(answer).append("\n\n")
        val results = json.optJSONArray("results") ?: JSONArray()
        if (results.length() > 0) {
            sb.append("参考来源：\n")
            for (i in 0 until minOf(results.length(), maxResults)) {
                val item = results.optJSONObject(i) ?: continue
                val t = item.optString("title", "").trim()
                val u = item.optString("url", "").trim()
                val c = item.optString("content", "").replace('\n', ' ').trim().take(240)
                sb.append("${i + 1}. $t\n   URL: $u\n")
                if (c.isNotEmpty()) sb.append("   摘要: $c\n")
            }
        }
        val out = sb.toString().trim()
        return if (out.isEmpty()) ToolResult("未搜到相关结果。") else ToolResult(out)
    }

    private fun callSerper(base: String, apiKey: String, query: String, maxResults: Int): ToolResult {
        val endpoint = if (base.endsWith("/search", ignoreCase = true)) base else "$base/search"
        val body = JSONObject().apply {
            put("q", query)
            put("num", maxResults)
        }
        val resp = AcidLlmClient.postJson(
            endpoint = endpoint,
            apiKey = "",
            body = body,
            extraHeaders = if (apiKey.isNotEmpty()) mapOf("X-API-KEY" to apiKey) else emptyMap(),
            readTimeoutMs = 45_000,
        )
        if (resp.code !in 200..299) {
            return ToolResult("Error: Serper HTTP ${resp.code}: ${resp.body.take(280)}", true)
        }
        val json = JSONObject(resp.body)
        val sb = StringBuilder()
        json.optJSONObject("answerBox")?.let { ab ->
            val ans = ab.optString("answer").ifBlank { ab.optString("snippet") }.trim()
            if (ans.isNotEmpty()) sb.append("直接答案：$ans\n\n")
        }
        json.optJSONObject("knowledgeGraph")?.let { kg ->
            val desc = kg.optString("description", "").trim()
            if (desc.isNotEmpty()) sb.append("知识卡片：$desc\n\n")
        }
        val organic = json.optJSONArray("organic") ?: JSONArray()
        if (organic.length() > 0) {
            sb.append("搜索结果：\n")
            for (i in 0 until minOf(organic.length(), maxResults)) {
                val item = organic.optJSONObject(i) ?: continue
                val t = item.optString("title", "").trim()
                val link = item.optString("link", "").trim()
                val snippet = item.optString("snippet", "").replace('\n', ' ').trim().take(240)
                sb.append("${i + 1}. $t\n   URL: $link\n")
                if (snippet.isNotEmpty()) sb.append("   摘要: $snippet\n")
            }
        }
        val out = sb.toString().trim()
        return if (out.isEmpty()) ToolResult("未搜到相关结果。") else ToolResult(out)
    }

    private fun searchViaDeepSeekTools(
        base: String,
        apiKey: String,
        query: String,
        maxResults: Int,
    ): Pair<Int, ToolResult> {
        val endpoint = if (base.endsWith("/tools/search", ignoreCase = true)) {
            base
        } else {
            AcidLlmClient.resolveVersionedToolEndpoint(base, "/tools/search")
        }
        val body = JSONObject().apply {
            put("query", query.take(128))
            put("count", maxResults.coerceIn(1, 20))
        }
        val resp = AcidLlmClient.postJson(endpoint, apiKey, body, readTimeoutMs = 45_000)
        if (resp.code !in 200..299) {
            return resp.code to ToolResult("Error: Search API HTTP ${resp.code}: ${resp.body.take(280)}", true)
        }
        val parsed = parseDeepSeekSearchOutput(resp.body, maxResults)
        return resp.code to (parsed?.let { ToolResult(it) } ?: ToolResult("未搜到相关结果。"))
    }

    private fun parseDeepSeekSearchOutput(rawBody: String, maxResults: Int): String? {
        val root = runCatching { JSONObject(rawBody) }.getOrNull() ?: return null
        val results = root.optJSONArray("results")
            ?: root.optJSONObject("data")?.optJSONArray("results")
            ?: return null
        if (results.length() == 0) return null
        val sb = StringBuilder("搜索结果：\n")
        for (i in 0 until minOf(results.length(), maxResults)) {
            val item = results.optJSONObject(i) ?: continue
            val title = item.optString("title", "").trim()
            val url = item.optString("url", "").trim()
            val site = item.optString("site_name", "").trim()
            val summary = item.optString("summary", "").trim()
                .ifBlank { item.optString("snippet", "").trim() }
                .ifBlank { item.optString("content", "").trim().take(400) }
                .replace('\n', ' ')
            sb.append("${i + 1}. ")
            if (title.isNotEmpty()) sb.append(title) else sb.append(url)
            if (site.isNotEmpty()) sb.append(" ($site)")
            sb.append('\n')
            if (url.isNotEmpty()) sb.append("   URL: $url\n")
            if (summary.isNotEmpty()) sb.append("   摘要: $summary\n")
        }
        val out = sb.toString().trim()
        return out.takeIf { it.isNotEmpty() && it != "搜索结果：" }
    }

    private fun callOpenAiOrResponses(
        base: String,
        apiKey: String,
        model: String,
        query: String,
        maxResults: Int,
    ): ToolResult {
        val skipResponses = base.endsWith("/chat/completions")
        if (!skipResponses) {
            val responsesUrl = AcidLlmClient.resolveOpenAiEndpoint(base, "/responses")
            val req = JSONObject().apply {
                put("model", model)
                put("input", query)
                put("tools", JSONArray().put(JSONObject().put("type", "web_search")))
            }
            val resp = AcidLlmClient.postJson(responsesUrl, apiKey, req, readTimeoutMs = 60_000)
            if (resp.code in 200..299) {
                parseResponsesOutput(resp.body, maxResults)?.let { return ToolResult(it) }
            } else if (resp.code !in setOf(400, 404, 405)) {
                return ToolResult("Error: Search API HTTP ${resp.code}: ${resp.body.take(280)}", true)
            }

            // Fallback to DeepSeek/standard /tools/search endpoint if /responses is unsupported.
            val (toolCode, toolRes) = searchViaDeepSeekTools(base, apiKey, query, maxResults)
            if (toolCode in 200..299) return toolRes
            if (toolCode !in setOf(400, 404, 405)) return toolRes
        }

        // Fallback to /chat/completions for search-capable chat models (e.g. Perplexity sonar, :online routes).
        val chatUrl = if (base.endsWith("/chat/completions")) {
            base
        } else {
            AcidLlmClient.resolveOpenAiEndpoint(base, "/chat/completions")
        }
        val chatReq = JSONObject().apply {
            put("model", model)
            put("stream", false)
            put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", query)))
        }
        val chatResp = AcidLlmClient.postJson(chatUrl, apiKey, chatReq, readTimeoutMs = 60_000)
        if (chatResp.code !in 200..299) {
            return ToolResult("Error: Search chat fallback HTTP ${chatResp.code}: ${chatResp.body.take(280)}", true)
        }
        val text = runCatching {
            JSONObject(chatResp.body)
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content", "")
                ?.trim()
                .orEmpty()
        }.getOrDefault("")
        return if (text.isNotEmpty() && text != "null") {
            ToolResult(text)
        } else {
            ToolResult("Error: Search response contained no text output.", true)
        }
    }

    private fun parseResponsesOutput(rawBody: String, maxResults: Int): String? {
        val json = runCatching { JSONObject(rawBody) }.getOrNull() ?: return null
        val output = json.optJSONArray("output") ?: return null
        val textSb = StringBuilder()
        val queries = LinkedHashSet<String>()
        val sources = LinkedHashMap<String, String>()

        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            when (item.optString("type")) {
                "web_search_call" -> {
                    val action = item.optJSONObject("action") ?: continue
                    action.optJSONArray("queries")?.let { qArr ->
                        for (j in 0 until qArr.length()) {
                            qArr.optString(j, "").trim().takeIf { it.isNotEmpty() }?.let { queries += it }
                        }
                    }
                    action.optJSONArray("sources")?.let { sArr ->
                        for (j in 0 until sArr.length()) {
                            val s = sArr.optJSONObject(j) ?: continue
                            val url = s.optString("url", "").trim()
                            val title = s.optString("title", "").trim()
                            if (url.isNotEmpty()) sources.putIfAbsent(url, title)
                        }
                    }
                }
                "message" -> {
                    val contentArr = item.optJSONArray("content") ?: continue
                    for (j in 0 until contentArr.length()) {
                        val part = contentArr.optJSONObject(j) ?: continue
                        val txt = part.optString("text", "").trim()
                        if (txt.isNotEmpty()) {
                            if (textSb.isNotEmpty()) textSb.append('\n')
                            textSb.append(txt)
                        }
                        part.optJSONArray("annotations")?.let { anns ->
                            for (k in 0 until anns.length()) {
                                val ann = anns.optJSONObject(k) ?: continue
                                val url = ann.optString("url", "").trim()
                                val title = ann.optString("title", "").trim()
                                if (url.isNotEmpty()) sources[url] = title.ifEmpty { sources[url].orEmpty() }
                            }
                        }
                    }
                }
            }
        }
        val mainText = textSb.toString().trim()
        if (mainText.isEmpty() && sources.isEmpty()) return null
        return buildString {
            if (mainText.isNotEmpty()) append(mainText)
            if (queries.isNotEmpty()) {
                append("\n\n搜索关键词：").append(queries.joinToString(", "))
            }
            if (sources.isNotEmpty()) {
                append("\n参考来源：\n")
                sources.entries.take(maxResults).forEachIndexed { idx, (url, title) ->
                    append("${idx + 1}. ")
                    if (title.isNotEmpty()) append(title).append(" - ")
                    append(url).append('\n')
                }
            }
        }.trim()
    }
}
