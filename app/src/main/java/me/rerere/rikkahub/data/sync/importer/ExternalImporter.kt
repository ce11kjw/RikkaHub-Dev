package me.rerere.rikkahub.data.sync.importer

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.toLocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.uuid.Uuid

private const val TAG = "ExternalImporter"

/**
 * 「从其他应用导入」的统一入口。
 *
 * 流程：探测来源 → 选解析器 → 归一化成 [ImportedConversation] → 写库。
 * 去重靠「由来源 id 派生的稳定 UUID」：同一份备份导入两次不会产生重复会话，
 * 而且不会覆盖本地已有的同名会话（本地会话的 UUID 是随机的，撞不上）。
 */
class ExternalImporter(
    private val context: Context,
    private val database: AppDatabase,
) {
    private val importers: List<ConversationImporter> = listOf(
        ChatboxImporterAdapter,
        ChatGptImporter,
        ClaudeImporter,
        JsonStateImporter,
    )

    /**
     * 只探测不写入，供界面在选完文件后提示「识别为 XXX 备份」。
     */
    fun detect(file: File): ImportSource? = detectSource(file)

    suspend fun import(
        file: File,
        existingProviders: List<ProviderSetting>,
        shouldImportConversation: suspend (String) -> Boolean = { true },
        onProviders: suspend (List<ProviderSetting>) -> Unit = {},
    ): ImportSummary = withContext(Dispatchers.IO) {
        val source = detectSource(file)
            ?: throw IllegalArgumentException("无法识别这个备份来自哪个应用，请确认文件是否正确")
        val importer = importers.firstOrNull { it.supports(source) }
            ?: throw IllegalArgumentException("暂不支持导入 ${source.displayName} 的备份")

        val notes = mutableListOf<String>()
        var conversations = 0
        var messages = 0

        val summary = importer.import(
            file = file,
            source = source,
            existingProviders = existingProviders,
            shouldImportConversation = shouldImportConversation,
            onConversation = { imported ->
                val written = writeConversation(imported, source)
                if (written > 0) {
                    conversations++
                    messages += written
                }
            },
            onProviders = onProviders,
            onNote = { notes += it },
        )

        Log.i(TAG, "import: ${source.displayName} → $conversations 会话 / $messages 消息")
        summary.copy(
            conversations = conversations,
            messages = messages,
            notes = (summary.notes + notes).distinct(),
        )
    }

    /**
     * 把一条外部会话写成 RikkaHub 的会话 + 消息节点。
     * 返回写入的消息条数；会话已存在（重复导入）时返回 0。
     */
    private suspend fun writeConversation(
        imported: ImportedConversation,
        source: ImportSource,
    ): Int {
        val conversationId = stableUuid("${source.name}:${imported.externalId}")
        if (database.conversationDao().getConversationById(conversationId.toString()) != null) {
            return 0
        }

        val uiMessages = imported.messages
            .filter { it.text.isNotBlank() || !it.reasoning.isNullOrBlank() }
            .map { it.toUiMessage() }
        if (uiMessages.isEmpty()) return 0

        val now = System.currentTimeMillis()
        val createdAt = imported.createdAt.takeIf { it > 0 } ?: now
        val updatedAt = imported.updatedAt.takeIf { it > 0 } ?: createdAt

        // 一个 node 装整条会话：外部工具基本没有分支，用单节点最贴近原样
        val node = MessageNode(
            id = stableUuid("${source.name}:node:${imported.externalId}"),
            messages = uiMessages,
            selectIndex = uiMessages.size - 1,
        )

        val conversation = Conversation(
            id = conversationId,
            assistantId = Uuid.parse(DEFAULT_ASSISTANT_ID),
            title = imported.title.ifBlank { "导入的对话" },
            messageNodes = listOf(node),
            createAt = Instant.ofEpochMilli(createdAt),
            updateAt = Instant.ofEpochMilli(updatedAt),
        )

        database.conversationDao().insert(
            me.rerere.rikkahub.data.db.entity.ConversationEntity(
                id = conversation.id.toString(),
                assistantId = DEFAULT_ASSISTANT_ID,
                title = conversation.title,
                nodes = "[]",
                createAt = createdAt,
                updateAt = updatedAt,
                chatSuggestions = "[]",
                isPinned = false,
                customSystemPrompt = imported.systemPrompt.orEmpty(),
            )
        )
        database.messageNodeDao().insert(
            me.rerere.rikkahub.data.db.entity.MessageNodeEntity(
                id = node.id.toString(),
                conversationId = conversation.id.toString(),
                nodeIndex = 0,
                messages = me.rerere.rikkahub.utils.JsonInstant.encodeToString(node.messages),
                selectIndex = node.selectIndex,
            )
        )

        // 新会话必须补全文索引，否则搜索不到
        runCatching { MessageFtsManager(database).indexConversation(conversation) }
            .onFailure { Log.w(TAG, "indexConversation failed for ${conversation.id}", it) }

        return uiMessages.size
    }

    private fun ImportedMessage.toUiMessage(): UIMessage = UIMessage(
        id = stableUuid("msg:$createdAt:$role:$text".take(200)),
        role = when (role) {
            ImportedRole.USER -> MessageRole.USER
            ImportedRole.ASSISTANT -> MessageRole.ASSISTANT
            ImportedRole.SYSTEM -> MessageRole.SYSTEM
            ImportedRole.TOOL -> MessageRole.TOOL
        },
        parts = buildList {
            if (!reasoning.isNullOrBlank()) add(UIMessagePart.Reasoning(reasoning))
            if (text.isNotBlank()) add(UIMessagePart.Text(text))
        },
        // UIMessage.createdAt 用的是 kotlinx.datetime.LocalDateTime
        createdAt = kotlinx.datetime.Instant.fromEpochMilliseconds(
            createdAt.takeIf { it > 0 } ?: System.currentTimeMillis()
        ).toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault()),
    )

    /** 由来源字符串派生固定 UUID：同一会话重复导入得到同一个 id，天然去重。 */
    private fun stableUuid(seed: String): Uuid = Uuid.parse(conversationKey(seed))

    /** 会话 id 的派生规则；ViewModel 判重也用它，保证两边一致。 */
    companion object {
        fun conversationKey(source: ImportSource, externalId: String): String =
            UUID.nameUUIDFromBytes("${source.name}:$externalId".toByteArray()).toString()

        /** 导入的会话没有指定助手时挂到默认助手。 */
        const val DEFAULT_ASSISTANT_ID = "0950e2dc-9bd5-4801-afa3-aa887aa36b4e"

        internal fun conversationKey(seed: String): String =
            UUID.nameUUIDFromBytes(seed.toByteArray()).toString()
    }

    private fun detectSource(file: File): ImportSource? {
        val name = file.name
        // Chatbox 是 zip，先按包内 manifest 判断
        if (runCatching { java.util.zip.ZipFile(file).use { true } }.getOrDefault(false)) {
            val probe = runCatching {
                java.util.zip.ZipFile(file).use { zip ->
                    if (zip.getEntry("manifest.json") != null) "chatbox-backup"
                    else zip.entries().asSequence()
                        .firstOrNull { it.name.endsWith(".json") }
                        ?.let { entry ->
                            if (entry.size > 65536) "" else
                                zip.getInputStream(entry).bufferedReader().use { it.readText().take(4096) }
                        }.orEmpty()
                }
            }.getOrDefault("")
            ImportSource.detect(name, probe)?.let { return it }
            if (probe.contains("chatbox-backup")) return ImportSource.CHATBOX
        }

        val probe = runCatching {
            file.bufferedReader().use { reader ->
                val buffer = CharArray(4096)
                val read = reader.read(buffer)
                if (read > 0) String(buffer, 0, read) else ""
            }
        }.getOrDefault("")
        return ImportSource.detect(name, probe)
    }

}
