package me.rerere.rikkahub.data.sync.importer

import kotlinx.datetime.toInstant
import kotlinx.datetime.toKotlinTimeZone
import me.rerere.ai.provider.ProviderSetting
import java.io.File

/**
 * Chatbox 的适配层。
 *
 * 真实的解析逻辑在 [ChatboxImporter] 里（它要处理图片资源、消息分叉等细节，已经比较完整），
 * 这里只把它接到「从其他应用导入」的统一流程上，不重复实现一遍。
 */
internal object ChatboxImporterAdapter : ConversationImporter {
    override fun supports(source: ImportSource) = source == ImportSource.CHATBOX

    override suspend fun import(
        file: File,
        source: ImportSource,
        existingProviders: List<ProviderSetting>,
        shouldImportConversation: suspend (String) -> Boolean,
        onConversation: suspend (ImportedConversation) -> Unit,
        onProviders: suspend (List<ProviderSetting>) -> Unit,
        onNote: (String) -> Unit,
    ): ImportSummary {
        val result = ChatboxImporter.importStreaming(
            file = file,
            assistantId = kotlin.uuid.Uuid.parse(DEFAULT_ASSISTANT_ID),
            providers = existingProviders,
            shouldImportConversation = { id -> shouldImportConversation(id.toString()) },
            onConversation = { conversation ->
                // Chatbox 的 Conversation 已经是 RikkaHub 内部模型，直接转成统一格式再交回去，
                // 让「去重 / 写入 / 建索引」只有一份实现。
                onConversation(conversation.toImported())
            },
        )

        if (result.providers.isNotEmpty()) {
            onProviders(result.providers)
        }

        val notes = buildList {
            if (result.skippedForkMessages > 0) add("跳过了 ${result.skippedForkMessages} 条分叉消息")
            if (result.skippedEmptyMessages > 0) add("跳过了 ${result.skippedEmptyMessages} 条空消息")
            if (result.skippedImageParts > 0) add("${result.skippedImageParts} 张图片未能导入")
        }

        return ImportSummary(
            source = ImportSource.CHATBOX,
            fileName = file.name,
            conversations = 0, // 由外层统一统计
            messages = 0,
            providers = result.providers.size,
            models = result.providers.sumOf { it.models.size },
            skipped = result.skippedSessions,
            notes = notes,
        )
    }

    private fun me.rerere.rikkahub.data.model.Conversation.toImported(): ImportedConversation {
        val flat = messageNodes.flatMap { it.messages }
        return ImportedConversation(
            externalId = id.toString(),
            title = title,
            createdAt = createAt.toEpochMilli(),
            updatedAt = updateAt.toEpochMilli(),
            messages = flat.map { message ->
                ImportedMessage(
                    role = when (message.role) {
                        me.rerere.ai.core.MessageRole.USER -> ImportedRole.USER
                        me.rerere.ai.core.MessageRole.ASSISTANT -> ImportedRole.ASSISTANT
                        me.rerere.ai.core.MessageRole.SYSTEM -> ImportedRole.SYSTEM
                        me.rerere.ai.core.MessageRole.TOOL -> ImportedRole.TOOL
                    },
                    text = message.parts.filterIsInstance<me.rerere.ai.ui.UIMessagePart.Text>()
                        .joinToString("\n\n") { it.text },
                    // 消息时间是 kotlinx.datetime.LocalDateTime，换算成毫秒要显式给时区
                    createdAt = message.createdAt
                        .toInstant(java.time.ZoneId.systemDefault().toKotlinTimeZone())
                        .toEpochMilliseconds(),
                    reasoning = message.parts.filterIsInstance<me.rerere.ai.ui.UIMessagePart.Reasoning>()
                        .joinToString("\n\n") { it.reasoning }.ifBlank { null },
                )
            }.filter { it.text.isNotBlank() || !it.reasoning.isNullOrBlank() },
        )
    }

    private const val DEFAULT_ASSISTANT_ID = "0950e2dc-9bd5-4801-afa3-aa887aa36b4e"
}
