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

/**
 * LobeChat / NextChat / Jan / Msty 这一类的通用解析器。
 *
 * 它们的共同点：导出一个 JSON，里面既可能是一个完整的状态对象（含 sessions/topics/messages…），
 * 也可能是若干数组并列。字段名各有差异，所以这里按「候选字段名」逐个尝试，
 * 命中哪个用哪个，而不是为每个工具写死一套死板路径。
 */
internal object JsonStateImporter : ConversationImporter {
    override fun supports(source: ImportSource) = source in SUPPORTED

    override suspend fun import(
        file: File,
        source: ImportSource,
        existingProviders: List<ProviderSetting>,
        shouldImportConversation: suspend (String) -> Boolean,
        onConversation: suspend (ImportedConversation) -> Unit,
        onProviders: suspend (List<ProviderSetting>) -> Unit,
        onNote: (String) -> Unit,
    ): ImportSummary {
        val root = runCatching {
            Json.parseToJsonElement(file.readText())
        }.getOrNull() ?: error("无法解析这个文件，不是有效的 JSON")

        // LobeChat 把数据包在 state 里，其他工具一般直接放在顶层
        val state = (root as? JsonObject)?.get("state") as? JsonObject ?: (root as? JsonObject)

        val conversations = when (source) {
            ImportSource.NEXTCHAT -> parseNextChat(state)
            ImportSource.LOBE_CHAT -> parseLobeChat(state)
            else -> parseGeneric(state)
        }

        val usable = conversations.filter { it.messages.isNotEmpty() }
        var imported = 0
        var messages = 0
        var skipped = conversations.size - usable.size

        for (conversation in usable) {
            if (!shouldImportConversation(conversation.externalId)) {
                skipped++
                continue
            }
            imported++
            messages += conversation.messages.size
            onConversation(conversation)
        }

        val notes = mutableListOf<String>()
        if (source == ImportSource.NEXTCHAT || source == ImportSource.LOBE_CHAT) {
            notes += "${source.displayName} 的 API 配置字段与 RikkaHub 不同，本次只导入会话"
        }

        return ImportSummary(
            source = source,
            fileName = file.name,
            conversations = imported,
            messages = messages,
            skipped = skipped,
            notes = notes,
        )
    }

    // ---------- NextChat ----------

    /**
     * NextChat 的结构是 `{ sessions: [...], messages: [...] }`：
     * 会话信息在 sessions 里，消息按 sessionId 散装在 messages 数组里。
     */
    private fun parseNextChat(state: JsonObject?): List<ImportedConversation> {
        if (state == null) return emptyList()
        val sessions = state.array("sessions")
        val allMessages = state.array("messages")

        val grouped = allMessages.groupBy { item ->
            (item as? JsonObject)?.string("sessionId").orEmpty()
        }

        return sessions.mapNotNull { sessionElement ->
            val session = sessionElement as? JsonObject ?: return@mapNotNull null
            val id = session.string("id") ?: return@mapNotNull null
            val raw = grouped[id].orEmpty()
            val messages = raw.mapNotNull { (it as? JsonObject)?.toMessage() }
                .sortedBy { it.createdAt }
            ImportedConversation(
                externalId = id,
                title = session.string("topic")?.ifBlank { null } ?: "NextChat 对话",
                createdAt = session.number("createdAt") ?: messages.firstOrNull()?.createdAt ?: 0L,
                updatedAt = session.number("lastUpdate") ?: messages.lastOrNull()?.createdAt ?: 0L,
                messages = messages,
                systemPrompt = (session["mask"] as? JsonObject)?.let { mask ->
                    mask.array("context").mapNotNull { item ->
                        (item as? JsonObject)?.string("content")
                    }.joinToString("\n\n").ifBlank { null }
                },
                modelId = (session["mask"] as? JsonObject)?.string("model"),
            )
        }
    }

    // ---------- LobeChat ----------

    /**
     * LobeChat 导出：`state.sessions` / `state.topics` / `state.messages`。
     * 一个 session 下可以有多个 topic，一个 topic 下才是消息；这里把 topic 当作一条会话导，
     * 标题用 topic 的 title，没有 topic 的会话退回 session 的 meta.title。
     */
    private fun parseLobeChat(state: JsonObject?): List<ImportedConversation> {
        if (state == null) return emptyList()
        val sessions = state.array("sessions")
        val topics = state.array("topics")
        val messagesRaw = state.array("messages")

        val sessionMeta = sessions.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            (obj.string("id") ?: return@mapNotNull null) to obj
        }.toMap()

        val messagesByTopic = messagesRaw.groupBy { item ->
            (item as? JsonObject)?.string("topicId")
                ?: (item as? JsonObject)?.string("sessionId").orEmpty()
        }

        // 有 topic 的按 topic 拆开
        val byTopic = topics.mapNotNull { element ->
            val topic = element as? JsonObject ?: return@mapNotNull null
            val id = topic.string("id") ?: return@mapNotNull null
            val sessionId = topic.string("sessionId").orEmpty()
            val messages = messagesByTopic[id].orEmpty().mapNotNull { (it as? JsonObject)?.toMessage() }
                .sortedBy { it.createdAt }
            ImportedConversation(
                externalId = id,
                title = topic.string("title")?.ifBlank { null } ?: "LobeChat 对话",
                createdAt = topic.number("createdAt") ?: messages.firstOrNull()?.createdAt ?: 0L,
                updatedAt = topic.number("updatedAt") ?: messages.lastOrNull()?.createdAt ?: 0L,
                messages = messages,
                systemPrompt = sessionMeta[sessionId]?.let { s ->
                    (s["config"] as? JsonObject)?.string("systemRole")?.ifBlank { null }
                },
            )
        }

        // 没有 topicId 的消息归到 session 下（老版本或「随便聊聊」）
        val topicIds = topics.mapNotNull { (it as? JsonObject)?.string("id") }.toSet()
        val orphanSessions = sessions.mapNotNull { element ->
            val session = element as? JsonObject ?: return@mapNotNull null
            val id = session.string("id") ?: return@mapNotNull null
            if (id in topicIds) return@mapNotNull null
            val messages = messagesByTopic[id].orEmpty().mapNotNull { (it as? JsonObject)?.toMessage() }
                .sortedBy { it.createdAt }
            if (messages.isEmpty()) return@mapNotNull null
            ImportedConversation(
                externalId = id,
                title = (session["meta"] as? JsonObject)?.string("title")?.ifBlank { null } ?: "LobeChat 对话",
                createdAt = session.number("createdAt") ?: messages.first().createdAt,
                updatedAt = session.number("updatedAt") ?: messages.last().createdAt,
                messages = messages,
                systemPrompt = (session["config"] as? JsonObject)?.string("systemRole")?.ifBlank { null },
            )
        }

        return byTopic + orphanSessions
    }

    // ---------- Jan / Msty / 其他 ----------

    /**
     * Jan 用 threads + messages；Msty 用 conversations + messages。
     * 统一按「找会话数组 + 找消息数组 + 按会话 id 分组」处理，字段名给一串候选。
     */
    private fun parseGeneric(state: JsonObject?): List<ImportedConversation> {
        if (state == null) return emptyList()
        val sessionArray = state.array("threads", "conversations", "sessions", "chats")
        val messageArray = state.array("messages", "chat_messages", "history")

        val grouped = messageArray.groupBy { item ->
            (item as? JsonObject)?.let {
                it.string("thread_id") ?: it.string("conversation_id")
                    ?: it.string("sessionId") ?: it.string("chat_id").orEmpty()
            }.orEmpty()
        }

        return sessionArray.mapNotNull { element ->
            val session = element as? JsonObject ?: return@mapNotNull null
            val id = session.string("id") ?: return@mapNotNull null
            val messages = grouped[id].orEmpty().mapNotNull { (it as? JsonObject)?.toMessage() }
                .sortedBy { it.createdAt }
            if (messages.isEmpty()) return@mapNotNull null
            ImportedConversation(
                externalId = id,
                title = session.string("title")?.ifBlank { null }
                    ?: session.string("name")?.ifBlank { null }
                    ?: "导入的对话",
                createdAt = session.number("createdAt") ?: session.number("created_at")
                    ?: messages.first().createdAt,
                updatedAt = session.number("updatedAt") ?: session.number("updated_at")
                    ?: messages.last().createdAt,
                messages = messages,
                systemPrompt = session.string("system_prompt") ?: session.string("systemPrompt"),
            )
        }
    }

    /** 单条消息的统一转换：role 兼容 user/human、assistant/ai 两种叫法。 */
    private fun JsonObject.toMessage(): ImportedMessage? {
        val roleRaw = string("role") ?: string("sender") ?: return null
        val role = when (roleRaw.lowercase()) {
            "user", "human" -> ImportedRole.USER
            "assistant", "ai", "bot" -> ImportedRole.ASSISTANT
            "system" -> ImportedRole.SYSTEM
            "tool" -> ImportedRole.TOOL
            else -> return null
        }
        val text = string("content")
            ?: string("text")
            ?: run {
                // 部分格式把内容放在 parts / content 数组里
                val parts = (this["parts"] as? JsonArray) ?: (this["content"] as? JsonArray)
                parts?.mapNotNull { part ->
                    when (part) {
                        is JsonPrimitive -> part.contentOrNull
                        is JsonObject -> part.string("text") ?: part.string("content")
                        else -> null
                    }
                }?.joinToString("\n\n")
            }
            ?: return null
        if (text.isBlank()) return null

        return ImportedMessage(
            role = role,
            text = text,
            createdAt = number("createdAt") ?: number("created_at")
                ?: number("timestamp") ?: number("date") ?: 0L,
            reasoning = string("reasoning"),
        )
    }

    private fun JsonObject.array(vararg keys: String): List<kotlinx.serialization.json.JsonElement> {
        for (key in keys) {
            val value = this[key] as? JsonArray ?: continue
            return value.toList()
        }
        return emptyList()
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.number(key: String): Long? {
        val raw = (this[key] as? JsonPrimitive)?.contentOrNull ?: return null
        return raw.toDoubleOrNull()?.toLong()
    }

    private val SUPPORTED = setOf(
        ImportSource.NEXTCHAT,
        ImportSource.LOBE_CHAT,
        ImportSource.JAN,
        ImportSource.MSTY,
    )
}
