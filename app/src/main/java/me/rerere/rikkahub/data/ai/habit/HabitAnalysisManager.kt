package me.rerere.rikkahub.data.ai.habit

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.data.datastore.SettingsStore

private const val TAG = "HabitAnalysis"

/** 一次分析的结果，供 UI 展示。 */
sealed interface HabitAnalysisResult {
    data class Success(val messageCount: Int, val skillChars: Int) : HabitAnalysisResult
    data class Failed(val reason: String) : HabitAnalysisResult
    /** 用户没开这个功能，或者上次分析还没到时间。 */
    data object Skipped : HabitAnalysisResult
}

/**
 * 习惯分析的编排：收集 → 分析 → 写技能。
 *
 * 加锁是因为它可能被「手动点按钮」和「后台定时任务」同时触发，
 * 两次并发分析会互相覆盖结果，还白烧两份 token。
 */
class HabitAnalysisManager(
    private val settingsStore: SettingsStore,
    private val collector: HabitDataCollector,
    private val analyzer: HabitAnalyzer,
    private val writer: HabitSkillWriter,
) {
    private val lock = Mutex()

    /**
     * 跑一次完整分析。
     *
     * @param force true 表示手动触发，忽略「是否到时间」和「功能是否开启」的判断
     */
    suspend fun run(force: Boolean = false): HabitAnalysisResult = lock.withLock {
        val settings = settingsStore.awaitLoaded()
        val config = settings.habitAnalysisConfig

        if (!force) {
            if (!config.enabled) return HabitAnalysisResult.Skipped
            val elapsed = System.currentTimeMillis() - config.lastRunTime
            val intervalMs = config.intervalDays * 24L * 60 * 60 * 1000
            if (elapsed < intervalMs) {
                Log.i(TAG, "还没到分析时间（已过 ${elapsed / 3_600_000}h）")
                return HabitAnalysisResult.Skipped
            }
        }

        Log.i(TAG, "开始分析，范围 ${config.rangeDays} 天")
        val messages = collector.collect(config.rangeDays)
        if (messages.isEmpty()) {
            return HabitAnalysisResult.Failed("最近 ${config.rangeDays} 天没有找到可分析的对话")
        }

        val body = analyzer.analyze(settings, messages)
            ?: return HabitAnalysisResult.Failed("模型没有返回有效结果，检查是否配置了聊天模型")

        if (!writer.write(body)) {
            return HabitAnalysisResult.Failed("写入技能文件失败")
        }

        // 记录时间，用于下次判断间隔；失败时不记录，下次还会重试
        settingsStore.update { latest ->
            latest.copy(
                habitAnalysisConfig = latest.habitAnalysisConfig.copy(
                    lastRunTime = System.currentTimeMillis(),
                    lastMessageCount = messages.size,
                ),
            )
        }

        Log.i(TAG, "分析完成，样本 ${messages.size} 条，产出 ${body.length} 字")
        return HabitAnalysisResult.Success(messages.size, body.length)
    }
}
