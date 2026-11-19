package com.acid.wechat.agent

import com.acid.wechat.agent.AgentToolRegistry.makeTool
import com.acid.wechat.agent.AgentToolRegistry.prop
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.nio.charset.Charset

/**
 * Web page / article reader supporting DeepSeek `POST /v1/tools/fetch` and custom reader proxies
 * (e.g. `https://r.jina.ai/` or `{url}` template), with SSRF protection on target URLs.
 */
object WebFetchTool : AgentTool {
    override val name = "webfetch"
    override val title = "网页链接读取 (webfetch)"
    override val summary = "抓取网页正文为 Markdown"

    private const val MAX_DOWNLOAD_BYTES = 512 * 1024
    private const val MAX_PROXY_REDIRECTS = 3

    private val scriptStyleRegex = Regex("<(script|style|noscript|svg)\\b[^>]*>[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE)
    private val blockBreakRegex = Regex("</?(p|div|br|li|tr|h[1-6]|blockquote|section|article)\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val htmlTagRegex = Regex("<[^>]+>")
    private val numericEntityRegex = Regex("&#(\\d+);")
    private val privateIpLiteralRegex = Regex(
        "^(?:127\\.|10\\.|192\\.168\\.|169\\.254\\.|0\\.|172\\.(?:1[6-9]|2\\d|3[0-1])\\.|::1$|fe80:|fc00:|fd)",
        RegexOption.IGNORE_CASE,
    )

    override fun buildOpenAiSchema(): JSONObject = makeTool(
        name = name,
        description = "Fetch and extract readable plain text or Markdown from a public HTTP/HTTPS webpage or shared WeChat link card URL.",
        properties = JSONObject().apply {
            put("url", prop("string", "HTTP or HTTPS URL to fetch."))
            put("max_chars", prop("integer", "Maximum characters to return (default 6000, max 20000)."))
        },
        required = listOf("url"),
    )

    override fun execute(args: JSONObject): ToolResult = execute(args, ToolPolicy())

    override fun execute(args: JSONObject, policy: ToolPolicy): ToolResult {
        val rawUrl = args.optString("url", "").trim()
        if (rawUrl.isEmpty()) return ToolResult("Error: Missing 'url' for webfetch.", true)
        val maxChars = args.optInt("max_chars", 6000).coerceIn(500, 20_000)

        validatePublicHttpUrl(rawUrl)?.let { err ->
            return ToolResult("Error: $err", true)
        }

        val cfg = AgentConfigStore.getConfig()
        val fetchBase = cfg.webFetchBaseUrl.trim()
        val fetchKey = cfg.webFetchApiKey.trim()
        if (fetchBase.isEmpty()) {
            return ToolResult("Error: 未配置网页抓取接口地址，请先在设置中填写抓取接口。", true)
        }
        val lowerBase = fetchBase.lowercase()
        if (!lowerBase.startsWith("http://") && !lowerBase.startsWith("https://")) {
            return ToolResult("Error: 网页抓取接口地址必须以 http:// 或 https:// 开头。", true)
        }

        return runCatching {
            val isGetProxy = fetchBase.contains("{url}") ||
                lowerBase.contains("jina.ai") ||
                lowerBase.endsWith("?url=") ||
                lowerBase.endsWith("&url=")

            val resultText = if (isGetProxy) {
                fetchViaGetProxy(fetchBase, fetchKey, rawUrl)
            } else {
                fetchViaPostApi(fetchBase, fetchKey, rawUrl)
            }
            val cleaned = if (looksLikeHtml(resultText)) htmlToReadableText(resultText) else normalizeText(resultText)
            if (cleaned.isEmpty()) {
                ToolResult("网页返回为空或需要登录验证（URL: $rawUrl）。")
            } else if (cleaned.length > maxChars) {
                ToolResult(cleaned.take(maxChars) + "\n...[内容已截断，共 ${cleaned.length} 字符]...")
            } else {
                ToolResult(cleaned)
            }
        }.getOrElse { err ->
            ToolResult("Error: webfetch failed: ${err.message ?: err.javaClass.simpleName}", true)
        }
    }

    private fun fetchViaGetProxy(proxyBase: String, proxyKey: String, targetUrl: String): String {
        val initialEndpoint = if (proxyBase.contains("{url}")) {
            proxyBase.replace("{url}", targetUrl)
        } else {
            "${proxyBase.trimEnd('/')}/$targetUrl"
        }
        val initialHost = runCatching { URI(initialEndpoint).host?.lowercase() }.getOrNull()
        var current = initialEndpoint
        for (hop in 0..MAX_PROXY_REDIRECTS) {
            val curHost = runCatching { URI(current).host?.lowercase() }.getOrNull()
            val headers = if (proxyKey.isNotEmpty() && curHost != null && curHost == initialHost) {
                mapOf("Authorization" to "Bearer $proxyKey")
            } else {
                emptyMap()
            }
            val (code, bodyOrLoc) = executeGet(current, headers)
            if (code in setOf(301, 302, 303, 307, 308)) {
                if (bodyOrLoc.isBlank()) throw IllegalStateException("Redirect HTTP $code missing Location")
                val resolved = URI(current).resolve(bodyOrLoc).toString()
                val lowerResolved = resolved.lowercase()
                if (!lowerResolved.startsWith("http://") && !lowerResolved.startsWith("https://")) {
                    throw SecurityException("Redirect blocked: invalid scheme in '$resolved'")
                }
                current = resolved
                continue
            }
            return bodyOrLoc
        }
        throw IllegalStateException("Too many redirects (> $MAX_PROXY_REDIRECTS)")
    }

    private fun fetchViaPostApi(fetchBase: String, fetchKey: String, targetUrl: String): String {
        val trimmed = fetchBase.trimEnd('/')
        if (trimmed.contains("deepseek.com", ignoreCase = true) && fetchKey.isEmpty()) {
            throw IllegalStateException("未配置网页抓取 API Key，请先在设置中填写。")
        }
        val endpoint = if (trimmed.endsWith("/fetch", ignoreCase = true) || trimmed.endsWith("/extract", ignoreCase = true)) {
            trimmed
        } else {
            AcidLlmClient.resolveVersionedToolEndpoint(trimmed, "/tools/fetch")
        }
        val payload = JSONObject().put("url", targetUrl)
        val resp = AcidLlmClient.postJson(endpoint, fetchKey, payload, readTimeoutMs = 45_000)
        if (resp.code !in 200..299) {
            throw IllegalStateException("HTTP ${resp.code}: ${resp.body.take(260)}")
        }
        val json = runCatching { JSONObject(resp.body) }.getOrNull() ?: return resp.body
        val node = json.optJSONObject("data") ?: json
        val title = node.optString("title", "").trim()
        val content = node.optString("content", "").trim()
            .ifBlank { node.optString("markdown", "").trim() }
            .ifBlank { node.optString("text", "").trim() }
            .ifBlank { resp.body }
        return buildString {
            if (title.isNotEmpty()) append("# ").append(title).append("\n\n")
            append("URL: ").append(targetUrl).append("\n\n")
            append(content)
        }.trim()
    }

    private fun executeGet(urlStr: String, extraHeaders: Map<String, String>): Pair<Int, String> {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36",
            )
            setRequestProperty("Accept", "text/html,application/xhtml+xml,text/plain,application/json;q=0.9,*/*;q=0.8")
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            for ((k, v) in extraHeaders) setRequestProperty(k, v)
        }
        return try {
            val code = conn.responseCode
            if (code in setOf(301, 302, 303, 307, 308)) {
                return code to conn.getHeaderField("Location").orEmpty()
            }
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val bytes = stream?.use { ins ->
                val baos = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0
                while (total < MAX_DOWNLOAD_BYTES) {
                    val n = ins.read(buf, 0, minOf(buf.size, MAX_DOWNLOAD_BYTES - total))
                    if (n <= 0) break
                    baos.write(buf, 0, n)
                    total += n
                }
                baos.toByteArray()
            } ?: ByteArray(0)
            val charset = parseCharset(conn.contentType) ?: Charsets.UTF_8
            val text = String(bytes, charset)
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code: ${text.take(220)}")
            }
            code to text
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun validatePublicHttpUrl(rawUrl: String): String? {
        val lower = rawUrl.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return "Only http:// and https:// URLs are supported."
        }
        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return "Malformed URL."
        val host = uri.host?.trim()?.lowercase()?.removeSurrounding("[", "]").orEmpty()
        if (host.isEmpty()) return "Missing URL host."
        if (host == "localhost" || host.endsWith(".local") || privateIpLiteralRegex.containsMatchIn(host)) {
            return "Access denied: loopback or private network host '$host' is blocked."
        }
        val addrs = runCatching { InetAddress.getAllByName(host) }.getOrNull()
        if (addrs != null && addrs.any {
                it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress ||
                    it.isSiteLocalAddress || it.isMulticastAddress
            }
        ) {
            return "Access denied: '$host' resolves to a non-public address."
        }
        return null
    }

    private fun parseCharset(contentType: String?): Charset? {
        if (contentType.isNullOrBlank()) return null
        val part = contentType.split(';')
            .firstOrNull { it.trim().startsWith("charset=", ignoreCase = true) }
            ?: return null
        val name = part.substringAfter('=').trim().trim('"')
        return runCatching { Charset.forName(name) }.getOrNull()
    }

    private fun looksLikeHtml(text: String): Boolean {
        val head = text.trimStart().take(300).lowercase()
        return head.startsWith("<!doctype html") || head.startsWith("<html") ||
            head.contains("<body") || head.contains("<article")
    }

    private fun htmlToReadableText(rawHtml: String): String {
        var s = scriptStyleRegex.replace(rawHtml, " ")
        s = blockBreakRegex.replace(s, "\n")
        s = htmlTagRegex.replace(s, " ")
        s = s.replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
        s = numericEntityRegex.replace(s) { m ->
            m.groupValues[1].toIntOrNull()?.takeIf { it in 32..65535 }?.toChar()?.toString() ?: " "
        }
        return normalizeText(s)
    }

    private fun normalizeText(text: String): String = text
        .replace("\r\n", "\n")
        .replace(Regex("[ \\t\\x0B\\f]+"), " ")
        .replace(Regex(" *\n *"), "\n")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}
