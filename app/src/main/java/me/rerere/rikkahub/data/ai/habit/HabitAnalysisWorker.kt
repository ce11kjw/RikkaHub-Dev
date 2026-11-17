package me.rerere.rikkahub.data.ai.habit

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.Constraints
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

private const val TAG = "HabitAnalysisWorker"
private const val WORK_NAME = "habit-analysis"

/**
 * 定时跑习惯分析。
 *
 * 用 WorkManager 而不是自己起定时器：分析要调模型、要联网，
 * WorkManager 能在设备空闲/有网时才跑，被打断了下次还会补上。
 */
class HabitAnalysisWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params), KoinComponent {

    override suspend fun doWork(): Result {
        val manager: HabitAnalysisManager by inject()
        return when (val result = manager.run(force = false)) {
            is HabitAnalysisResult.Success -> {
                Log.i(TAG, "分析完成：${result.messageCount} 条消息")
                Result.success()
            }
            // 被跳过（没到时间/没开启）不算失败，正常结束即可
            HabitAnalysisResult.Skipped -> Result.success()
            is HabitAnalysisResult.Failed -> {
                Log.w(TAG, "分析失败：${result.reason}")
                // 失败重试一次：可能是临时网络问题
                if (runAttemptCount < 2) Result.retry() else Result.failure()
            }
        }
    }

    companion object {
        /**
         * 按设置同步调度，幂等。
         *
         * @param enabled 关掉时取消任务
         */
        fun schedule(context: Context, enabled: Boolean, intervalDays: Int) {
            val workManager = WorkManager.getInstance(context)
            if (!enabled) {
                workManager.cancelUniqueWork(WORK_NAME)
                return
            }
            // 分析要调模型，必须有网；电池低时别跑，免得用户以为耗电
            val request = PeriodicWorkRequestBuilder<HabitAnalysisWorker>(
                intervalDays.toLong().coerceAtLeast(1), TimeUnit.DAYS,
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()

            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
            Log.i(TAG, "已调度，每 $intervalDays 天一次")
        }
    }
}

/** 启动和设置变化时调用，把配置同步到 WorkManager。 */
object HabitAnalysisScheduler {
    fun sync(context: Context, config: me.rerere.rikkahub.data.datastore.HabitAnalysisConfig) {
        HabitAnalysisWorker.schedule(
            context = context,
            enabled = config.enabled,
            intervalDays = config.intervalDays,
        )
    }
}
