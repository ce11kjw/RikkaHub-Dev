package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class ConversationEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("assistant_id", defaultValue = "0950e2dc-9bd5-4801-afa3-aa887aa36b4e")
    val assistantId: String,
    @ColumnInfo("title")
    val title: String,
    @ColumnInfo("nodes")
    val nodes: String,
    @ColumnInfo("create_at")
    val createAt: Long,
    @ColumnInfo("update_at")
    val updateAt: Long,
    @ColumnInfo("suggestions", defaultValue = "[]")
    val chatSuggestions: String,
    @ColumnInfo("is_pinned", defaultValue = "0")
    val isPinned: Boolean,
    @ColumnInfo("custom_system_prompt", defaultValue = "")
    val customSystemPrompt: String = "",
    @ColumnInfo("mode_injection_ids", defaultValue = "[]")
    val modeInjectionIds: String = "[]",
    @ColumnInfo("lorebook_ids", defaultValue = "[]")
    val lorebookIds: String = "[]",
    @ColumnInfo("workspace_cwd", defaultValue = "")
    val workspaceCwd: String = "",
    @ColumnInfo("folder_id", defaultValue = "")
    val folderId: String = "",
    @ColumnInfo("config", defaultValue = "")
    val config: String = "",
    /**
     * 上下文压缩摘要（[me.rerere.rikkahub.data.model.ContextSummary] 的 JSON），空串表示没有。
     *
     * 之前只存在内存里，App 重启后摘要丢失，等于压缩白做一遍；这里补上持久化。
     * 单独一列而不是塞进 nodes，是为了让聊天记录保持原样 —— 摘要只影响发给模型的内容。
     */
    @ColumnInfo("context_summary", defaultValue = "")
    val contextSummary: String = "",
    /**
     * 新会话开场时自动带上的「上一次对话」摘要，空串表示没有。
     *
     * 存成列而不是每轮现算：这段内容每轮都要出现在请求前缀里，
     * 现算意味着每轮多读一次别的会话，还会让前缀不稳定、打掉提示词缓存。
     */
    @ColumnInfo("prior_context", defaultValue = "")
    val priorContext: String = "",
)
