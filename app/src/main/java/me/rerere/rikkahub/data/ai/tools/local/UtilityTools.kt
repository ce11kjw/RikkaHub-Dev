package me.rerere.rikkahub.data.ai.tools.local

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.FilesManager
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * 生成二维码图片，存到聊天附件里回一张图。
 *
 * 纯本地计算，不联网。用中等纠错等级（M）：容错够用，码点也不至于太密。
 */
internal fun buildQrGenerateTool(filesManager: FilesManager): Tool = Tool(
    name = "qr_generate",
    description = """
        Generate a QR code image from text or a URL and attach it to the conversation.
        Works offline. Returns the saved file path.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putJsonObject("content") {
                    put("type", "string")
                    put("description", "Text or URL to encode")
                }
                putJsonObject("size") {
                    put("type", "integer")
                    put("description", "Image side length in pixels, defaults to 512")
                }
            },
            required = listOf("content")
        )
    },
    needsApproval = { false },
    execute = { args ->
        val obj = args.jsonObject
        val content = obj["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val size = (obj["size"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 512)
            .coerceIn(128, 2048)
        require(content.isNotBlank()) { "content is required" }

        val bytes = withContext(Dispatchers.IO) {
            val hints = mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 1,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            )
            val matrix: BitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
            val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
            for (x in 0 until matrix.width) {
                for (y in 0 until matrix.height) {
                    bitmap.setPixel(x, y, if (matrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                }
            }
            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                bitmap.recycle()
                out.toByteArray()
            }
        }

        val uri = filesManager.createChatFilesByByteArrays(
            byteArrays = listOf(bytes),
            displayName = "qrcode.png",
            mimeType = "image/png",
        ).first()
        listOf(
            UIMessagePart.Image(uri.toString()),
            UIMessagePart.Text(buildJsonObject {
                put("ok", true)
                put("size", size)
                put("path", uri.toString())
            }.toString()),
        )
    }
)

/**
 * 批量探测 URL 是否可达，顺带拿状态码和耗时。
 *
 * 用 HEAD 优先、失败再回退 GET：很多站点不实现 HEAD。
 * 并发跑，不然十几条串行会很慢。
 */
internal fun buildCheckUrlTool(client: OkHttpClient): Tool = Tool(
    name = "check_url",
    description = """
        Check whether one or more URLs are reachable and report status code and latency.
        Accepts up to 20 URLs at once. Useful for probing sites or endpoints.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putJsonObject("urls") {
                    put("type", "array")
                    put("description", "List of absolute http(s) URLs, at most 20")
                    putJsonObject("items") { put("type", "string") }
                }
                putJsonObject("timeout") {
                    put("type", "integer")
                    put("description", "Per-request timeout in seconds, defaults to 10")
                }
            },
            required = listOf("urls")
        )
    },
    needsApproval = { false },
    execute = { args ->
        val obj = args.jsonObject
        val urls = (obj["urls"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull {
            it.jsonPrimitive.contentOrNull?.trim()
        }.orEmpty().take(20)
        val timeoutSec = (obj["timeout"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 10)
            .coerceIn(3, 60)
        require(urls.isNotEmpty()) { "urls is required" }

        val results = withContext(Dispatchers.IO) {
            val scoped = client.newBuilder()
                .connectTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
                .readTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
                .callTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
                .build()

            urls.map { url ->
                buildJsonObject {
                    put("url", url)
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        put("ok", false)
                        put("error", "unsupported scheme")
                        return@buildJsonObject
                    }
                    val started = System.currentTimeMillis()
                    val outcome = runCatching {
                        val head = scoped.newCall(Request.Builder().url(url).head().build()).execute()
                        head.use {
                            // 405/501 说明不支持 HEAD，用 GET 再试一次
                            if (it.code == 405 || it.code == 501) {
                                scoped.newCall(Request.Builder().url(url).get().build()).execute().use { g -> g.code }
                            } else it.code
                        }
                    }
                    val elapsed = System.currentTimeMillis() - started
                    put("latency_ms", elapsed)
                    outcome.onSuccess { code ->
                        put("ok", code in 200..399)
                        put("status", code)
                    }.onFailure { e ->
                        put("ok", false)
                        put("error", e.message ?: e::class.java.simpleName)
                    }
                }
            }
        }
        listOf(UIMessagePart.Text(buildJsonObject {
            putJsonArray("results") { results.forEach { add(it) } }
        }.toString()))
    }
)

/**
 * 查天气。
 *
 * 用 wttr.in 的 JSON 接口：不需要 API key，覆盖全球，够日常用。
 * 只挑当前天气和未来几天的主要字段，原文返回会有一堆用不上的内容。
 */
internal fun buildWeatherTool(client: OkHttpClient): Tool = Tool(
    name = "get_weather",
    description = """
        Get current weather and a short forecast for a city.
        Accepts a city name in any language, or omit it to use the device's approximate IP location.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putJsonObject("city") {
                    put("type", "string")
                    put("description", "City name, e.g. Beijing or 南宁")
                }
                putJsonObject("days") {
                    put("type", "integer")
                    put("description", "Forecast days, 1-3, defaults to 3")
                }
            }
        )
    },
    needsApproval = { false },
    execute = { args ->
        val obj = args.jsonObject
        val city = obj["city"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val days = (obj["days"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 3).coerceIn(1, 3)

        val result = withContext(Dispatchers.IO) {
            val url = "https://wttr.in/${java.net.URLEncoder.encode(city, "UTF-8")}?format=j1&lang=zh"
            val scoped = client.newBuilder()
                .callTimeout(20, TimeUnit.SECONDS)
                .build()
            scoped.newCall(
                Request.Builder().url(url).header("User-Agent", "curl/8.0").build()
            ).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext buildJsonObject {
                        put("ok", false)
                        put("error", "weather service returned ${resp.code}")
                    }
                }
                val body = resp.body?.string().orEmpty()
                val root = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject
                val current = (root["current_condition"] as? kotlinx.serialization.json.JsonArray)
                    ?.firstOrNull()?.jsonObject
                val nearest = (root["nearest_area"] as? kotlinx.serialization.json.JsonArray)
                    ?.firstOrNull()?.jsonObject

                buildJsonObject {
                    put("ok", true)
                    put("city", city.ifBlank { "current location" })
                    nearest?.let { area ->
                        val name = (area["areaName"] as? kotlinx.serialization.json.JsonArray)
                            ?.firstOrNull()?.jsonObject?.get("value")?.jsonPrimitive?.contentOrNull
                        if (!name.isNullOrBlank()) put("resolved_city", name)
                    }
                    current?.let { c ->
                        putJsonObject("current") {
                            put("temp_c", c["temp_C"]?.jsonPrimitive?.contentOrNull)
                            put("feels_like_c", c["FeelsLikeC"]?.jsonPrimitive?.contentOrNull)
                            put("humidity", c["humidity"]?.jsonPrimitive?.contentOrNull)
                            put("wind_kmph", c["windspeedKmph"]?.jsonPrimitive?.contentOrNull)
                            put("desc", (c["lang_zh"] as? kotlinx.serialization.json.JsonArray)
                                ?.firstOrNull()?.jsonObject?.get("value")?.jsonPrimitive?.contentOrNull
                                ?: (c["weatherDesc"] as? kotlinx.serialization.json.JsonArray)
                                    ?.firstOrNull()?.jsonObject?.get("value")?.jsonPrimitive?.contentOrNull)
                        }
                    }
                    putJsonArray("forecast") {
                        (root["weather"] as? kotlinx.serialization.json.JsonArray)
                            ?.take(days)?.forEach { dayEl ->
                                val day = dayEl.jsonObject
                                add(buildJsonObject {
                                    put("date", day["date"]?.jsonPrimitive?.contentOrNull)
                                    put("max_c", day["maxtempC"]?.jsonPrimitive?.contentOrNull)
                                    put("min_c", day["mintempC"]?.jsonPrimitive?.contentOrNull)
                                    put("desc", (day["hourly"] as? kotlinx.serialization.json.JsonArray)
                                        ?.firstOrNull()?.jsonObject
                                        ?.get("lang_zh")?.let { it as? kotlinx.serialization.json.JsonArray }
                                        ?.firstOrNull()?.jsonObject?.get("value")?.jsonPrimitive?.contentOrNull)
                                })
                            }
                    }
                }
            }
        }
        listOf(UIMessagePart.Text(result.toString()))
    }
)
