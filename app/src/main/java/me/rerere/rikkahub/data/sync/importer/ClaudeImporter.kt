package me.rerere.rikkahub.data.sync.importer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.ProviderSetting
import java.io.File
import java.time.Instant
import java.time.OffsetDateTime
import java.util.zip.ZipFile

/**
 * Claude 官方数据导出解析器（`conversations.json`）。
 *
 * 结构上比 ChatGPT 简单：`chat_messages` 已经是按顺序排好的扁平数组，不需要拆树。
 * 但有一个必须绕开的陷阱：每条消息的 `text` 字段是「渲染后的文本」，
 * 遇到 thinking / tool_use / tool_result 块时会插入
 * 「This block is not supported on your current device yet」占位串，
 * 直接读它会把这段废话混进正文。正确做法是读结构化的 `content` 块数组。
 */
internal object ClaudeImporter : ConversationImporter {
    override fun supports(source: ImportSource) = source == ImportSource.CLAUDE

    override suspend fun import(
        file: File,
        source: ImportSource,
        existingProviders: List<ProviderSetting>,
        shouldImportConversation: suspend (String) -> Boolean,
        onConversation: suspend (ImportedConversation) -> Unit,
        onProviders: suspend (List<ProviderSetting>) -> Unit,
        onNote: (String) -> Unit,
    ): ImportSummary {
        onNote("Claude 官方导出不含 API 配置，只导入聊天记录")

        var conversations = 0
        var messages = 0
        var skipped = 0

        for (array in readArrays(file)) {
            for (element in array) {
                val conversation = element as? JsonObject ?: continue
                val parsed = parseConversation(conversation) ?: continue
                if (parsed.messages.isEmpty()) {
                    skipped++
                    continue
                }
                if (!shouldImportConversation(parsed.externalId)) {
                    skipped++
                    continue
                }
                conversations++
                messages += parsed.messages.size
                onConversation(parsed)
            }
        }

        return ImportSummary(
            source = ImportSource.CLAUDE,
            fileName = file.name,
            conversations = conversations,
            messages = messages,
            skipped = skipped,
            notes = listOf("Claude 官方导出不含 API 配置，只导入聊天记录"),
        )
    }

    private fun readArrays(file: File): List<JsonArray> {
        val isZip = runCatching { ZipFile(file).use { true } }.getOrDefault(false)
        if (!isZip) {
            return listOfNotNull(parseArray(file.readText())).filter { looksLikeClaude(it) }
        }
        val result = mutableListOf<JsonArray>()
        ZipFile(file).use { zip ->
            for (entry in zip.entries().toList()) {
                if (entry.isDirectory || !entry.name.endsWith(".json", ignoreCase = true)) continue
                if (entry.size > MAX_ENTRY_SIZE) continue
                val text = runCatching {
                    zip.getInputStream(entry).bufferedReader().readText()
                }.getOrNull() ?: continue
                val array = parseArray(text) ?: continue
                if (looksLikeClaude(array)) result.add(array)
            }
        }
        return result
    }

    private fun looksLikeClaude(array: JsonArray): Boolean = array.any {
        (it as? JsonObject)?.containsKey("chat_messages") == true
    }

    private fun parseArray(text: String): JsonArray? = runCatching {
        Json.parseToJsonElement(text) as? JsonArray
    }.getOrNull()

    private fun parseConversation(conversation: JsonObject): ImportedConversation? {
        val chatMessages = conversation["chat_messages"] as? JsonArray ?: return null
        val messages = chatMessages.mapNotNull { (it as? JsonObject)?.toImportedMessage() }
        if (messages.isEmpty()) return null

        val id = conversation["uuid"]?.jsonPrimitive?.contentOrNull
            ?: messages.first().createdAt.toString()
        val createdAt = conversation["created_at"].isoMillis() ?: messages.first().createdAt
        val updatedAt = conversation["updated_at"].isoMillis() ?: messages.last().createdAt

        return ImportedConversation(
            externalId = id,
            title = conversation["name"]?.jsonPrimitive?.contentOrNull?.ifBlank { null } ?: "Claude 对话",
            createdAt = createdAt,
            updatedAt = updatedAt,
            messages = messages,
            modelId = conversation["model"]?.jsonPrimitive?.contentOrNull,
        )
    }

    private fun JsonObject.toImportedMessage(): ImportedMessage? {
        val sender = this["sender"]?.jsonPrimitive?.contentOrNull ?: return null
        if (sender != "human" && sender != "assistant") return null

        val blocks = this["content"] as? JsonArray
        val text = blocks?.mapNotNull { block ->
            (block as? JsonObject)?.takeIf { it.type() == "text" }
                ?.get("text")?.jsonPrimitive?.contentOrNull
        }?.filter { it.isNotBlank() }?.joinToString("\n\n").orEmpty()

        // 思考过程单独取出，不混进正文
        val reasoning = blocks?.mapNotNull { block ->
            (block as? JsonObject)?.takeIf { it.type() == "thinking" }
                ?.let { it["thinking"]?.jsonPrimitive?.contentOrNull ?: it["text"]?.jsonPrimitive?.contentOrNull }
        }?.filter { it.isNotBlank() }?.joinToString("\n\n")?.ifBlank { null }

        // 结构块缺失时才退回 text 字段，并清掉那句占位语
        val fallback = this["text"]?.jsonPrimitive?.contentOrNull
            ?.replace(UNSUPPORTED_BLOCK, "")
            ?.trim()
            .orEmpty()
        val finalText = text.ifBlank { fallback }
        if (finalText.isBlank() && reasoning.isNullOrBlank()) return null

        return ImportedMessage(
            role = if (sender == "human") ImportedRole.USER else ImportedRole.ASSISTANT,
            text = finalText,
            createdAt = this["created_at"].isoMillis() ?: 0L,
            reasoning = reasoning,
        )
    }

    private fun JsonObject.type(): String? = this["type"]?.jsonPrimitive?.contentOrNull

    /** Claude 的时间是 ISO-8601 字符串；解析失败返回 null，由调用方用消息时间兜底。 */
    private fun kotlinx.serialization.json.JsonElement?.isoMillis(): Long? {
        val raw = (this as? JsonPrimitive)?.contentOrNull ?: return null
        return runCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }
            .recoverCatching { Instant.parse(raw).toEpochMilli() }
            .getOrNull()
    }

    /** text 字段里代表「这块内容不支持」的占位语，出现时应当丢弃。 */
    private const val UNSUPPORTED_BLOCK = "This block is not supported on your current device yet."
    private const val MAX_ENTRY_SIZE = 512L * 1024 * 1024
}
