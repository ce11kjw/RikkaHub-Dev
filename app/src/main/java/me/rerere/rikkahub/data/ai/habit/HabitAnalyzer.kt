package me.rerere.rikkahub.data.ai.habit

import android.util.Log
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.UsageRecorder
import me.rerere.rikkahub.data.ai.UsageTag
import me.rerere.rikkahub.data.ai.UsageSource
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.resolveModelId
import me.rerere.rikkahub.utils.JsonInstant

private const val TAG = "HabitAnalyzer"

/**
 * 让模型读用户的历史发言，归纳出他的协作习惯。
 *
 * 用**聊天模型**而不是快速模型：习惯归纳要求理解语气和反复出现的模式，
 * 快速模型容易只做词频统计，得不出"他要求先给方案"这类结论。
 */
class HabitAnalyzer(
    private val providerManager: me.rerere.ai.provider.ProviderManager,
    private val usageRecorder: UsageRecorder,
) {
    /** 一次分析最多送多少条消息给模型。太多会顶到窗口，太少看不出模式。 */
    private val batchSize = 400

    /**
     * 分析用户的发言，产出技能正文。
     *
     * @return Markdown 正文；失败返回 null
     */
    suspend fun analyze(settings: Settings, messages: List<String>): String? {
        if (messages.size < 10) {
            Log.w(TAG, "样本太少（${messages.size} 条），跳过分析")
            return null
        }

        val modelId = settings.resolveModelId(settings.chatModelId) ?: run {
            Log.w(TAG, "没有可用的聊天模型")
            return null
        }
        val model = settings.findModelById(modelId) ?: return null
        val provider = model.findProvider(settings.providers) ?: return null

        val sample = messages.takeLast(batchSize)
        val prompt = buildPrompt(sample)

        return runCatching {
            val result = usageRecorder.wrap(
                providerManager.getProviderByType(provider),
                // 单独标记来源，便于在用量里区分这部分消耗
                UsageTag(UsageSource.CHAT),
            ).generateText(
                providerSetting = provider,
                messages = listOf(UIMessage.user(prompt)),
                params = me.rerere.rikkahub.data.ai.backgroundTextGenerationParams(
                    model = model,
                    sessionId = kotlin.uuid.Uuid.random(),
                ),
            )
            result.message.toText().trim().takeIf { it.isNotBlank() }
        }.onFailure {
            Log.w(TAG, "analyze failed", it)
        }.getOrNull()
    }

    /**
     * 分析用的提示词。
     *
     * 要求很具体：只用可观察的行为，不许编心理画像 —— 不然会产出
     * "他是一个追求完美的人" 这类没用的话。
     */
    private fun buildPrompt(messages: List<String>): String = buildString {
        appendLine("下面是一个用户在过去一段时间里的发言记录（只包含他自己说的话）。")
        appendLine("请归纳他的**协作偏好**，输出可以直接作为 AI 助手行为准则的 Markdown。")
        appendLine()
        appendLine("要求：")
        appendLine("1. 只写**可观察的行为模式**，不要写心理画像")
        appendLine("   ❌ \"他是一个追求完美的人\"")
        appendLine("   ✅ \"他反复要求先给方案再执行（出现 N 次）\"")
        appendLine("2. 每条规则要写清**具体怎么做**，不要抽象")
        appendLine("3. 按重要性排序，最重要的放最前面")
        appendLine("4. 引用他的**原话**作为依据，每条最多引 1-2 句")
        appendLine("5. 不确定的不要写。宁可少写几条，也不要编")
        appendLine("6. 用中文写，标题层级用 ##")
        appendLine()
        appendLine("输出结构：")
        appendLine("```")
        appendLine("## 一、<规则名>")
        appendLine("<具体怎么做>")
        appendLine("> 「他的原话」")
        appendLine()
        appendLine("## 二、...")
        appendLine("```")
        appendLine()
        appendLine("发言记录：")
        appendLine("---")
        messages.forEachIndexed { i, m -> appendLine("${i + 1}. $m") }
    }
}
