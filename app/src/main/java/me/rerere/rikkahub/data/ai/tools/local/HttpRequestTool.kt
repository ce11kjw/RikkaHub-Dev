package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.utils.JsonInstant
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** 响应正文最多回给模型的字符数，避免一个巨大页面把上下文撑爆。 */
private const val MAX_BODY_CHARS = 20_000

/**
 * 通用 HTTP 请求工具。
 *
 * 和 `search_web` 的区别：搜索只回摘要和链接，这个能直接调任意接口、拿完整响应，
 * 适合「帮我查一下这个 API 返回什么」「这个页面正文写了什么」这类需求。
 *
 * 只允许 http/https；拿到的正文超过上限会截断，并明确告诉模型被截断了多少。
 */
internal fun buildHttpRequestTool(client: OkHttpClient): Tool = Tool(
    name = "http_request",
    description = """
        Send an HTTP request and return the response body.
        Supports GET/POST/PUT/DELETE/PATCH with custom headers and a body.
        Use this to call web APIs or read a web page's raw content.
        Response bodies longer than $MAX_BODY_CHARS characters are truncated.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putJsonObject("url") {
                    put("type", "string")
                    put("description", "Absolute http(s) URL to request")
                }
                putJsonObject("method") {
                    put("type", "string")
                    put("description", "HTTP method, defaults to GET")
                    putJsonArray("enum") {
                        add("GET"); add("POST"); add("PUT"); add("DELETE"); add("PATCH")
                    }
                }
                putJsonObject("headers") {
                    put("type", "object")
                    put("description", "Optional request headers as a flat key-value object")
                }
                putJsonObject("body") {
                    put("type", "string")
                    put("description", "Optional request body (usually JSON)")
                }
                putJsonObject("content_type") {
                    put("type", "string")
                    put("description", "Content-Type for the body, defaults to application/json")
                }
            },
            required = listOf("url")
        )
    },
    needsApproval = { false },
    execute = { args ->
        val obj = args.jsonObject
        val url = obj["url"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        require(url.startsWith("http://") || url.startsWith("https://")) {
            "Only http and https URLs are supported"
        }
        val method = obj["method"]?.jsonPrimitive?.contentOrNull?.uppercase() ?: "GET"
        val body = obj["body"]?.jsonPrimitive?.contentOrNull
        val contentType = obj["content_type"]?.jsonPrimitive?.contentOrNull ?: "application/json"

        val headers = obj["headers"]?.jsonObject?.mapValues { (_, v) ->
            (v as? JsonPrimitive)?.contentOrNull.orEmpty()
        }.orEmpty()

        val result = withContext(Dispatchers.IO) {
            val requestBody = when {
                method == "GET" || method == "HEAD" -> null
                body != null -> body.toRequestBody(contentType.toMediaType())
                else -> "".toRequestBody(null)
            }
            val request = Request.Builder()
                .url(url)
                .method(method, requestBody)
                .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
                .build()

            // 单独的超时：有些接口很慢，但也不能无限等
            val scopedClient = client.newBuilder()
                .callTimeout(60, TimeUnit.SECONDS)
                .build()

            scopedClient.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                val truncated = raw.length > MAX_BODY_CHARS
                buildJsonObject {
                    put("status", response.code)
                    put("ok", response.isSuccessful)
                    put("url", response.request.url.toString())
                    put("content_type", response.header("Content-Type").orEmpty())
                    if (truncated) {
                        put("truncated", true)
                        put("original_length", raw.length)
                    }
                    put("body", raw.take(MAX_BODY_CHARS))
                }
            }
        }
        listOf(UIMessagePart.Text(result.toString()))
    }
)

/**
 * 抓网页并转成纯文本。
 *
 * 直接给模型 HTML 意义不大（标签会占掉大量 token），这里做一层粗糙但有效的清洗：
 * 去掉 script/style、剥标签、压缩空白。不追求完美排版，够模型读懂正文即可。
 */
internal fun buildFetchUrlTool(client: OkHttpClient): Tool = Tool(
    name = "fetch_url",
    description = """
        Fetch a web page and return its readable text content.
        HTML tags, scripts and styles are stripped so the result is plain text.
        Useful when you need the full content of a page rather than a search snippet.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putJsonObject("url") {
                    put("type", "string")
                    put("description", "Absolute http(s) URL of the page to read")
                }
                putJsonObject("max_chars") {
                    put("type", "integer")
                    put("description", "Maximum characters to return, defaults to 12000")
                }
            },
            required = listOf("url")
        )
    },
    needsApproval = { false },
    execute = { args ->
        val obj = args.jsonObject
        val url = obj["url"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        require(url.startsWith("http://") || url.startsWith("https://")) {
            "Only http and https URLs are supported"
        }
        val maxChars = obj["max_chars"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 12_000

        val result = withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                // 有些站点不给默认 UA 就直接 403
                .header("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 RikkaHub")
                .header("Accept", "text/html,application/xhtml+xml,text/plain")
                .build()
            val scopedClient = client.newBuilder()
                .callTimeout(45, TimeUnit.SECONDS)
                .build()

            scopedClient.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                val text = htmlToReadableText(raw)
                buildJsonObject {
                    put("status", response.code)
                    put("ok", response.isSuccessful)
                    put("url", response.request.url.toString())
                    put("title", extractTitle(raw))
                    put("truncated", text.length > maxChars)
                    put("content", text.take(maxChars))
                }
            }
        }
        listOf(UIMessagePart.Text(result.toString()))
    }
)

/** 粗暴但够用的 HTML 转文本：去掉脚本样式、剥标签、解几个常见实体、压空白。 */
internal fun htmlToReadableText(html: String): String {
    var text = html
        .replace(Regex("(?is)<script[^>]*>.*?</script>"), " ")
        .replace(Regex("(?is)<style[^>]*>.*?</style>"), " ")
        .replace(Regex("(?is)<noscript[^>]*>.*?</noscript>"), " ")
        .replace(Regex("(?is)<!--.*?-->"), " ")
    // 块级标签换行，避免整页文字连成一片
    text = text.replace(Regex("(?i)</(p|div|li|tr|h[1-6]|section|article|br)\\s*>"), "\n")
    text = text.replace(Regex("(?i)<br\\s*/?>"), "\n")
    text = text.replace(Regex("(?s)<[^>]+>"), " ")
    // 常见实体
    text = text
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace(Regex("&#(\\d+);")) { m ->
            m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: ""
        }
    // 压缩空白：行内多空格合一，最多保留一个空行
    text = text.replace(Regex("[ \\t\\u00A0]+"), " ")
    text = text.replace(Regex(" *\\n *"), "\n")
    text = text.replace(Regex("\\n{3,}"), "\n\n")
    return text.trim()
}

private fun extractTitle(html: String): String =
    Regex("(?is)<title[^>]*>(.*?)</title>").find(html)
        ?.groupValues?.get(1)
        ?.trim()
        .orEmpty()
