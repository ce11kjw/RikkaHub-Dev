package me.rerere.rikkahub.data.sync.importer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.ProviderSetting
import java.io.File
import java.util.zip.ZipFile

/**
 * ChatGPT 官方数据导出的解析器（`conversations.json`）。
 *
 * 两个必须做对的地方：
 * 1. 会话不是消息列表，而是一棵 `mapping` 节点树。编辑提示词、重新生成都会分出新的分支，
 *    直接按 key 遍历会把「你放弃的那些草稿」当成真实对话导进来。必须从 `current_node`
 *    沿 `parent` 回溯到根，再反转，才是你界面上看到的那条链路。
 * 2. 大账号的导出会分片成 `conversations-000.json`…，不能只认 `conversations.json` 一个名字。
 */
internal object ChatGptImporter : ConversationImporter {
    override fun supports(source: ImportSource) = source == ImportSource.CHATGPT

    override suspend fun import(
        file: File,
        source: ImportSource,
        existingProviders: List<ProviderSetting>,
        shouldImportConversation: suspend (String) -> Boolean,
        onConversation: suspend (ImportedConversation) -> Unit,
        onProviders: suspend (List<ProviderSetting>) -> Unit,
        onNote: (String) -> Unit,
    ): ImportSummary {
        // 官方导出不包含 API 配置，只能导会话
        onNote("ChatGPT 官方导出不含 API 配置，只导入聊天记录")

        var conversations = 0
        var messages = 0
        var skipped = 0

        for (payload in readConversationPayloads(file)) {
            for (element in payload) {
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
            source = ImportSource.CHATGPT,
            fileName = file.name,
            conversations = conversations,
            messages = messages,
            skipped = skipped,
            notes = listOf("ChatGPT 官方导出不含 API 配置，只导入聊天记录"),
        )
    }

    /** 直接从文件读，或从导出 zip 里找出所有会话分片。 */
    private fun readConversationPayloads(file: File): List<JsonArray> {
        if (!isZip(file)) {
            return listOf(parseArray(file.readText())).filterNotNull()
        }
        val payloads = mutableListOf<JsonArray>()
        ZipFile(file).use { zip ->
            val entries = zip.entries().toList()
                .filter { !it.isDirectory && it.name.endsWith(".json", ignoreCase = true) }
            for (entry in entries) {
                if (entry.size > MAX_ENTRY_SIZE) continue
                val text = runCatching {
                    zip.getInputStream(entry).bufferedReader().readText()
                }.getOrNull() ?: continue
                // 不靠文件名认定，逐个探测结构；这样分片命名变了也能读到
                val array = parseArray(text) ?: continue
                val looksLikeConversations = array.any { item ->
                    (item as? JsonObject)?.containsKey("mapping") == true
                }
                if (looksLikeConversations) payloads.add(array)
            }
        }
        return payloads
    }

    private fun parseArray(text: String): JsonArray? = runCatching {
        Json.parseToJsonElement(text) as? JsonArray
    }.getOrNull()

    private fun isZip(file: File): Boolean = runCatching {
        ZipFile(file).use { true }
    }.getOrDefault(false)

    private fun parseConversation(conversation: JsonObject): ImportedConversation? {
        val mapping = conversation["mapping"] as? JsonObject ?: return null
        val chain = visibleChain(mapping, conversation["current_node"]?.jsonPrimitive?.contentOrNull)
            ?: return null

        val messages = chain.mapNotNull { node -> node.toImportedMessage() }
        if (messages.isEmpty()) return null

        val id = conversation["id"]?.jsonPrimitive?.contentOrNull
            ?: conversation["conversation_id"]?.jsonPrimitive?.contentOrNull
            ?: messages.first().createdAt.toString()

        // 时间缺失是常态（真实导出里 null 很常见），用消息时间兜底
        val createdAt = conversation["create_time"].epochMillis() ?: messages.first().createdAt
        val updatedAt = conversation["update_time"].epochMillis() ?: messages.last().createdAt

        return ImportedConversation(
            externalId = id,
            title = conversation["title"]?.jsonPrimitive?.contentOrNull?.ifBlank { null }
                ?: "ChatGPT 对话",
            createdAt = createdAt,
            updatedAt = updatedAt,
            messages = messages,
            modelId = conversation["default_model_slug"]?.jsonPrimitive?.contentOrNull,
        )
    }

    /**
     * 从 current_node 沿 parent 回溯出「界面上真正显示的那条链路」。
     * 找不到 current_node 时退回「唯一的根节点向下走」，仍失败就放弃这个会话。
     */
    private fun visibleChain(mapping: JsonObject, currentNode: String?): List<JsonObject>? {
        val chain = mutableListOf<JsonObject>()
        var cursor = currentNode
        val guard = mutableSetOf<String>()
        while (!cursor.isNullOrBlank() && guard.add(cursor)) {
            val node = mapping[cursor] as? JsonObject ?: break
            chain.add(node)
            cursor = node["parent"]?.jsonPrimitive?.contentOrNull
        }
        if (chain.isNotEmpty()) return chain.reversed()

        // 没有 current_node：从根节点开始沿唯一子节点走
        val nodes = mapping.values.mapNotNull { it as? JsonObject }
        val childIds = nodes.flatMap { node ->
            (node["children"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        }.toSet()
        val roots = nodes.filter { node ->
            val id = node["id"]?.jsonPrimitive?.contentOrNull
            id != null && id !in childIds
        }
        val root = roots.singleOrNull() ?: return null
        var node: JsonObject? = root
        while (node != null) {
            chain.add(node)
            val nextId = (node["children"] as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?.firstOrNull()
            node = nextId?.let { mapping[it] as? JsonObject }
        }
        return chain.ifEmpty { null }
    }

    private fun JsonObject.toImportedMessage(): ImportedMessage? {
        val message = this["message"] as? JsonObject ?: return null
        val role = (message["author"] as? JsonObject)
            ?.get("role")?.jsonPrimitive?.contentOrNull
            ?: return null
        // system / tool 是内部消息，界面上不显示，导入也没有意义
        if (role != "user" && role != "assistant") return null

        val metadata = message["metadata"] as? JsonObject
        // 界面上被隐藏的消息（系统引导语等）不导入
        if (metadata?.get("is_visually_hidden_from_conversation")?.jsonPrimitive?.contentOrNull == "true") {
            return null
        }

        val content = message["content"] as? JsonObject ?: return null
        val text = extractText(content)
        val reasoning = extractReasoning(content)
        if (text.isBlank() && reasoning.isNullOrBlank()) return null

        return ImportedMessage(
            role = if (role == "user") ImportedRole.USER else ImportedRole.ASSISTANT,
            text = text,
            createdAt = message["create_time"].epochMillis() ?: 0L,
            reasoning = reasoning,
            modelId = metadata?.get("model_slug")?.jsonPrimitive?.contentOrNull,
        )
    }

    /** parts 里既有纯字符串，也有 {content_type: image_asset_pointer} 之类的对象，只取文本。 */
    private fun extractText(content: JsonObject): String {
        val parts = content["parts"] as? JsonArray ?: return ""
        return parts.mapNotNull { part ->
            when (part) {
                is JsonPrimitive -> part.contentOrNull
                is JsonObject -> part["text"]?.jsonPrimitive?.contentOrNull
                else -> null
            }
        }.filter { it.isNotBlank() }.joinToString("\n\n")
    }

    /** 推理模型的思考过程放在 thoughts 里，单独作为 reasoning 保留。 */
    private fun extractReasoning(content: JsonObject): String? {
        val thoughts = content["thoughts"] as? JsonArray ?: return null
        val text = thoughts.mapNotNull { item ->
            (item as? JsonObject)?.get("content")?.jsonPrimitive?.contentOrNull
        }.filter { it.isNotBlank() }.joinToString("\n\n")
        return text.ifBlank { null }
    }

    /** create_time 是「带小数的 Unix 秒」，不是毫秒。 */
    private fun kotlinx.serialization.json.JsonElement?.epochMillis(): Long? {
        val raw = (this as? JsonPrimitive)?.contentOrNull ?: return null
        val seconds = raw.toDoubleOrNull() ?: return null
        if (seconds <= 0) return null
        return (seconds * 1000).toLong()
    }

    private const val MAX_ENTRY_SIZE = 512L * 1024 * 1024
}
