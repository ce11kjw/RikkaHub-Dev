package me.rerere.rikkahub.data.sync.importer

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.provider.ProviderSetting
import java.io.File

/**
 * 支持从别处导入的 AI 聊天应用。
 *
 * 这里的 `id` 只是内部标识，用于选中解析器和记录统计，不影响界面文案。
 */
enum class ImportSource(
    val displayName: String,
    /** 是否有会话记录可导入（Claude/ChatGPT 官方导出只有会话，没有 API 配置）。 */
    val hasConversations: Boolean,
    /** 是否可能带提供商/API 配置。 */
    val hasProviders: Boolean,
) {
    CHATBOX("Chatbox", hasConversations = true, hasProviders = true),
    CHERRY_STUDIO("Cherry Studio", hasConversations = true, hasProviders = true),
    NEXTCHAT("NextChat", hasConversations = true, hasProviders = true),
    LOBE_CHAT("LobeChat", hasConversations = true, hasProviders = true),
    CHATGPT("ChatGPT", hasConversations = true, hasProviders = false),
    CLAUDE("Claude", hasConversations = true, hasProviders = false),
    JAN("Jan", hasConversations = true, hasProviders = true),
    MSTY("Msty", hasConversations = true, hasProviders = true);

    companion object {
        /**
         * 按文件名和内容特征猜来源。
         *
         * 只看文件名并不可靠：很多工具导出的都叫 `conversations.json`，
         * 所以文件名命中后再用内容特征二次确认，两个都过才算。
         */
        fun detect(fileName: String, contentProbe: String?): ImportSource? {
            val name = fileName.lowercase()
            contentProbe?.let { probe ->
                detectByContent(probe)?.let { return it }
            }
            return when {
                name.startsWith("chatbox") || name.contains("chatbox") -> CHATBOX
                name.contains("cherry") -> CHERRY_STUDIO
                name.contains("nextchat") || name.contains("chatgpt-next-web") -> NEXTCHAT
                name.contains("lobe") -> LOBE_CHAT
                name.contains("jan") -> JAN
                name.contains("msty") -> MSTY
                else -> null
            }
        }

        /** 用内容的结构特征判断，比文件名可靠。 */
        fun detectByContent(probe: String): ImportSource? {
            val head = probe.take(4096)
            return when {
                // ChatGPT：mapping 节点树 + current_node
                "\"mapping\"" in head && "\"current_node\"" in head -> CHATGPT
                // Claude：chat_messages + sender
                "\"chat_messages\"" in head -> CLAUDE
                // LobeChat：exportType + state
                "\"exportType\"" in head && "\"state\"" in head -> LOBE_CHAT
                // NextChat：包含 chat 数组与 mask
                "\"masks\"" in head && "\"historyMessageCount\"" in head -> NEXTCHAT
                // Jan：包含 threads / assistants 且带 settings
                "\"threads\"" in head && "\"assistants\"" in head -> JAN
                // Msty：conversations + messages 且带 folder
                "\"folder_id\"" in head && "\"messages\"" in head -> MSTY
                else -> null
            }
        }
    }
}

/** 一次导入的来源、文件名和统计结果，用于界面回显「导入了什么、导了多少」。 */
data class ImportSummary(
    val source: ImportSource?,
    val fileName: String,
    val conversations: Int = 0,
    val messages: Int = 0,
    val providers: Int = 0,
    val models: Int = 0,
    val skipped: Int = 0,
    /** 只支持会话不支持的来源（例如官方导出没有 API 配置）会在这里说明。 */
    val notes: List<String> = emptyList(),
)

/**
 * 各来源解析器的统一出口。
 *
 * 解析器只负责把自己的格式读成 [ImportedConversation]，写库统一在这里之后的流程完成，
 * 这样 8 个来源共用同一套写入与去重逻辑。
 */
internal interface ConversationImporter {
    fun supports(source: ImportSource): Boolean

    /**
     * 解析给定的文件。[onConversation] 每解析出一条会话就回调一次，避免大备份一次性占满内存。
     */
    suspend fun import(
        file: File,
        source: ImportSource,
        existingProviders: List<ProviderSetting>,
        shouldImportConversation: suspend (externalId: String) -> Boolean,
        onConversation: suspend (ImportedConversation) -> Unit,
        onProviders: suspend (List<ProviderSetting>) -> Unit,
        onNote: (String) -> Unit,
    ): ImportSummary
}

/** 从任意工具解析出来的一条会话，字段都做了统一化，写入前不需要再关心来源格式。 */
data class ImportedConversation(
    /** 原工具里的会话 id，用于去重；缺失时由解析器生成稳定 id。 */
    val externalId: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<ImportedMessage>,
    /** 原工具里的系统提示词，会映射到 RikkaHub 的会话级系统提示。 */
    val systemPrompt: String? = null,
    val modelId: String? = null,
    val providerName: String? = null,
)

data class ImportedMessage(
    val role: ImportedRole,
    val text: String,
    val createdAt: Long,
    val reasoning: String? = null,
    val modelId: String? = null,
)

enum class ImportedRole { USER, ASSISTANT, SYSTEM, TOOL }

/** 解析器内部用的安全取值工具，避免各处重复写一大堆空判断。 */
internal object JsonSafe {
    fun JsonObject.string(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content

    fun JsonObject.number(key: String): Long? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toLong()

    fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
}
