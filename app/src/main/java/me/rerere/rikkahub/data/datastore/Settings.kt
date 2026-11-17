package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.ProviderSetting
import me.rerere.asr.ASRProviderSetting
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_OCR_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_SUGGESTION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TITLE_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TRANSLATION_PROMPT
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.QuickMessage
import me.rerere.rikkahub.data.model.Tag
import me.rerere.rikkahub.data.sync.s3.S3Config
import me.rerere.rikkahub.ui.theme.CustomTheme
import me.rerere.rikkahub.ui.theme.PresetThemes
import me.rerere.search.SearchCommonOptions
import me.rerere.search.SearchServiceOptions
import me.rerere.tts.provider.TTSProviderSetting
import kotlin.uuid.Uuid

@Serializable
data class Settings(
    @Transient
    val init: Boolean = false,
    val dynamicColor: Boolean = true,
    val themeId: String = PresetThemes[0].id,
    val customThemes: List<CustomTheme> = emptyList(),
    val developerMode: Boolean = false,
    val displaySetting: DisplaySetting = DisplaySetting(),
    val networkSetting: NetworkSetting = NetworkSetting(),
    val favoriteModels: List<Uuid> = emptyList(),
    val chatModelId: Uuid = Uuid.random(),
    // null = 跟随聊天模型。默认跟随，少一次选择，也不会因为没配而用了错的模型
    val fastModelId: Uuid? = null,
    val fastModelReasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
    // 记忆整理用的模型，没设置时用快速模型
    val memoryModelId: Uuid? = null,
    val memoryModelReasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
    val imageGenerationModelId: Uuid = Uuid.random(),
    val enableAutoTitle: Boolean = true,
    val titlePrompt: String = DEFAULT_TITLE_PROMPT,
    // null = 跟随聊天模型
    val translateModeId: Uuid? = null,
    val translatePrompt: String = DEFAULT_TRANSLATION_PROMPT,
    val translateThinkingBudget: Int = 0,
    // 翻译页的目标语言，存 BCP 47 语言标签
    val translateTargetLanguage: String = "zh-CN",
    val enableSuggestion: Boolean = true,
    val suggestionPrompt: String = DEFAULT_SUGGESTION_PROMPT,
    // 回复结束后上下文占用达到 autoCompressTokenLimit 时自动压缩历史。
    // 用绝对 token 数而不是百分比：不同模型窗口差十倍，百分比换算出来的触发点
    // 往往离真正上限太近，一次工具结果涌入就会直接冲破限制。
    val enableAutoCompress: Boolean = true,
    val autoCompressTokenLimit: Int = DEFAULT_AUTO_COMPRESS_TOKEN_LIMIT,
    // null = 跟随聊天模型
    val ocrModelId: Uuid? = null,
    // 习惯分析：定期读本地对话，让模型归纳用户的协作偏好，写成 my-habits 技能
    val habitAnalysisConfig: HabitAnalysisConfig = HabitAnalysisConfig(),
    /**
     * 助手全局同步：改一个助手的配置，其他助手全部跟着变。
     *
     * 只同步行为配置（模型、提示词、工具、各类开关），不同步名字、头像、背景
     * —— 那些是「这个助手长什么样」，全同步就分不清谁是谁了。
     *
     * 默认关闭：这会让原本分开配的助手被统一，不该替用户做决定。
     */
    val assistantSyncEnabled: Boolean = false,
    val ocrPrompt: String = DEFAULT_OCR_PROMPT,
    val assistantId: Uuid = DEFAULT_ASSISTANT_ID,
    val providers: List<ProviderSetting> = DEFAULT_PROVIDERS,
    val assistants: List<Assistant> = DEFAULT_ASSISTANTS,
    val assistantTags: List<Tag> = emptyList(),
    val searchServices: List<SearchServiceOptions> = listOf(SearchServiceOptions.DEFAULT),
    val searchCommonOptions: SearchCommonOptions = SearchCommonOptions(),
    val searchServiceSelected: Int = 0,
    val mcpServers: List<McpServerConfig> = emptyList(),
    val webDavConfig: WebDavConfig = WebDavConfig(),
    val s3Config: S3Config = S3Config(),
    // RemoteFileStore 上传临时素材用的桶，和备份的 s3Config 互不影响；items 字段在这里不使用
    val uploadS3Config: S3Config = S3Config(),
    val mediaGenerationProviders: List<MediaGenerationProviderSetting> = emptyList(),
    val ttsProviders: List<TTSProviderSetting> = DEFAULT_TTS_PROVIDERS,
    val selectedTTSProviderId: Uuid = DEFAULT_SYSTEM_TTS_ID,
    val defaultTTSPlaybackSpeed: Float = 1.0f,
    val asrProviders: List<ASRProviderSetting> = emptyList(),
    val selectedASRProviderId: Uuid? = null,
    val modeInjections: List<PromptInjection.ModeInjection> = DEFAULT_MODE_INJECTIONS,
    val lorebooks: List<Lorebook> = emptyList(),
    val quickMessages: List<QuickMessage> = emptyList(),
    val webServerEnabled: Boolean = false,
    val webServerPort: Int = 8080,
    val webServerJwtEnabled: Boolean = false,
    val webServerAccessPassword: String = "",
    val webServerLocalhostOnly: Boolean = false,
    val backupReminderConfig: BackupReminderConfig = BackupReminderConfig(),
    val autoBackupConfig: AutoBackupConfig = AutoBackupConfig(),
    val sponsorAlertDismissedAt: Int = 0,
) {
    companion object {
        // 构造一个用于初始化的settings, 但它不能用于保存，防止使用初始值存储
        fun dummy() = Settings(init = true)
    }
}

@Serializable
data class NetworkSetting(
    val userAgent: String = "",
    val proxyUrl: String = "",
    val proxyUsername: String = "",
    val proxyPassword: String = "",
    val enableAutoRetry: Boolean = true,
)

@Serializable
enum class ChatFontFamily {
    @SerialName("default")
    DEFAULT,

    @SerialName("serif")
    SERIF,

    @SerialName("monospace")
    MONOSPACE,

    @SerialName("custom")
    CUSTOM,
}

@Serializable
enum class BackgroundEffectType {
    @SerialName("blur")
    BLUR,

    @SerialName("glass")
    GLASS,
}

@Serializable
enum class ConversationSortOrder {
    @SerialName("update_time")
    UPDATE_TIME,

    @SerialName("create_time")
    CREATE_TIME,
}

@Serializable
data class DisplaySetting(
    val userAvatar: Avatar = Avatar.Dummy,
    val userNickname: String = "",
    val showUserAvatar: Boolean = true,
    val showAssistantBubble: Boolean = false,
    val bubbleOpacity: Float = 1.0f,
    val showModelIcon: Boolean = true,
    val showModelName: Boolean = true,
    val showDateTimeInMessage: Boolean = false,
    val showTokenUsage: Boolean = true,
    val showThinkingContent: Boolean = true,
    val autoCloseThinking: Boolean = true,
    val updateCheckDisabledUntilEpochMillis: Long = 0L,
    val showMessageJumper: Boolean = true,
    val messageJumperOnLeft: Boolean = false,
    val fontSizeRatio: Float = 1.0f,
    val enableMessageGenerationHapticEffect: Boolean = false,
    val skipCropImage: Boolean = true,
    val enableNotificationOnMessageGeneration: Boolean = false,
    val enableLiveUpdateNotification: Boolean = false,
    val enableFloatingChatWindow: Boolean = false,
    val codeBlockAutoWrap: Boolean = false,
    val codeBlockAutoCollapse: Boolean = false,
    val showLineNumbers: Boolean = false,
    val ttsOnlyReadQuoted: Boolean = false,
    val ttsOnlyReadOutsideBrackets: Boolean = false,
    val autoPlayTTSAfterGeneration: Boolean = false,
    val voiceModeListenWhileReplying: Boolean = true,
    val pasteLongTextAsFile: Boolean = false,
    val pasteLongTextThreshold: Int = 1000,
    val sendOnEnter: Boolean = false,
    val enableAutoScroll: Boolean = true,
    val enableLatexRendering: Boolean = true,
    val enableQuoteHighlight: Boolean = false,
    val enableBlurEffect: Boolean = false,
    val backgroundEffectType: BackgroundEffectType = BackgroundEffectType.BLUR,
    val chatFontFamily: ChatFontFamily = ChatFontFamily.DEFAULT,
    val chatCustomFontPath: String = "",
    val chatCustomFontName: String = "",
    val enableVolumeKeyScroll: Boolean = false,
    val volumeKeyScrollRatio: Float = 1.0f,
    val conversationSortOrder: ConversationSortOrder = ConversationSortOrder.UPDATE_TIME,
)

@Serializable
data class WebDavConfig(
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val path: String = "rikkahub_backups",
    val items: List<BackupItem> = listOf(
        BackupItem.DATABASE,
        BackupItem.FILES
    ),
) {
    @Serializable
    enum class BackupItem {
        DATABASE,
        FILES,
    }
}

@Serializable
data class BackupReminderConfig(
    val enabled: Boolean = false,
    val intervalDays: Int = 7,
    val lastBackupTime: Long = 0L,
)

/**
 * 自动备份：由 WorkManager 在后台定期把备份上传到 WebDAV / S3。
 *
 * 默认关闭。只传数据库（会话）不传文件，避免每次几百 MB 的附件把流量和电量吃掉；
 * 需要连附件一起备的，仍用页面上手动备份。
 */
@Serializable
data class AutoBackupConfig(
    val enabled: Boolean = false,
    /** 间隔小时数；WorkManager 最小 15 分钟，这里从 6 小时起。 */
    val intervalHours: Int = 24,
    /** 只在充电时执行，省电。 */
    val onlyWhenCharging: Boolean = true,
    /** 只在连 WiFi 时执行，避免偷跑流量。 */
    val onlyOnWifi: Boolean = true,
    /** 上次自动备份完成时间，用于界面显示。 */
    val lastAutoBackupTime: Long = 0L,
    /** 上次自动备份的结果说明（成功/失败原因），供界面回显。 */
    val lastAutoBackupResult: String = "",
)

/**
 * 习惯分析的配置。
 *
 * 默认关闭：这是个会消耗 token 的后台任务，要不要开应该由用户决定。
 */
@Serializable
data class HabitAnalysisConfig(
    val enabled: Boolean = false,
    /** 分析最近多少天的对话。 */
    val rangeDays: Int = 30,
    /** 每隔多少天自动分析一次。 */
    val intervalDays: Int = 14,
    /** 上次分析完成的时间戳，0 表示从没分析过。 */
    val lastRunTime: Long = 0L,
    /** 上次分析用了多少条消息，用于界面上显示效果。 */
    val lastMessageCount: Int = 0,
)
