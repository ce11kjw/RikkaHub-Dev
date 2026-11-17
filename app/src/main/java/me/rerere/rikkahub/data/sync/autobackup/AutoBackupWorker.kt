package me.rerere.rikkahub.data.sync.autobackup

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.sync.S3Sync
import me.rerere.rikkahub.data.sync.webdav.WebDavSync
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

private const val TAG = "AutoBackupWorker"
private const val WORK_NAME = "rikkahub-auto-backup"

/**
 * 后台自动备份。
 *
 * 只备份数据库（会话记录），不备份附件：附件动辄几百 MB，后台跑既费流量又容易被系统杀掉，
 * 需要连附件一起备时仍在备份页面手动操作。
 *
 * 走 [WorkManager] 而不是自己起协程，因为它是系统级调度：进程被杀、设备重启后依然会被重新拉起，
 * 而基于 `CoroutineScope` 的定时器一旦进程退出就没了。
 */
class AutoBackupWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params), KoinComponent {

    private val settingsStore: SettingsStore by inject()
    private val webDavSync: WebDavSync by inject()
    private val s3Sync: S3Sync by inject()

    override suspend fun doWork(): Result {
        val settings = settingsStore.awaitLoaded()
        val config = settings.autoBackupConfig
        if (!config.enabled) return Result.success()

        // 两个目的地都没配就没有可做的事，直接成功退出，避免无限重试
        val webDavReady = settings.webDavConfig.url.isNotBlank()
        val s3Ready = settings.s3Config.bucket.isNotBlank()
        if (!webDavReady && !s3Ready) {
            return finish("未配置 WebDAV 或 S3，无法自动备份", success = false)
        }

        // 条件不满足时交回系统稍后重试（例如等待充电或连上 WiFi）
        val constraintMet = (!config.onlyWhenCharging || isCharging()) &&
            (!config.onlyOnWifi || isOnWifi())
        if (!constraintMet) {
            return Result.retry()
        }

        val failures = mutableListOf<String>()
        val succeeded = mutableListOf<String>()

        if (webDavReady) {
            runCatching {
                // 自动备份只带数据库，不带附件
                webDavSync.backup(
                    settings.webDavConfig.copy(
                        items = listOf(me.rerere.rikkahub.data.datastore.WebDavConfig.BackupItem.DATABASE)
                    )
                )
            }.onSuccess {
                succeeded += "WebDAV"
            }.onFailure { e ->
                Log.w(TAG, "webdav backup failed", e)
                failures += "WebDAV: ${e.message ?: e::class.simpleName}"
            }
        }

        if (s3Ready) {
            runCatching {
                s3Sync.backupToS3(
                    settings.s3Config.copy(
                        items = listOf(me.rerere.rikkahub.data.sync.s3.S3Config.BackupItem.DATABASE)
                    )
                )
            }.onSuccess {
                succeeded += "S3"
            }.onFailure { e ->
                Log.w(TAG, "s3 backup failed", e)
                failures += "S3: ${e.message ?: e::class.simpleName}"
            }
        }

        return when {
            succeeded.isNotEmpty() -> finish(
                "已备份到 ${succeeded.joinToString("、")}" +
                    if (failures.isNotEmpty()) "；失败：${failures.joinToString("、")}" else "",
                success = true,
            )
            // 全部失败：交回系统退避重试，同时把原因记下来
            else -> finish("备份失败：${failures.joinToString("、")}", success = false)
        }
    }

    private suspend fun finish(message: String, success: Boolean): Result {
        settingsStore.update { current ->
            current.copy(
                autoBackupConfig = current.autoBackupConfig.copy(
                    lastAutoBackupTime = if (success) System.currentTimeMillis()
                    else current.autoBackupConfig.lastAutoBackupTime,
                    lastAutoBackupResult = message,
                )
            )
        }
        Log.i(TAG, message)
        // 失败也返回 success：靠 Constraints 的退避重试已经足够，这里不制造失败重试风暴
        return Result.success()
    }

    private fun isCharging(): Boolean {
        val manager = applicationContext.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
            ?: return true
        return manager.isCharging
    }

    private fun isOnWifi(): Boolean {
        val connectivity = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager ?: return true
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
    }

    companion object {
        /**
         * 按当前设置注册或取消定时任务。
         *
         * 用 [ExistingPeriodicWorkPolicy.UPDATE]：改了间隔或条件后直接更新已有任务，
         * 不会同时存在两个备份任务。
         */
        fun schedule(
            context: Context,
            enabled: Boolean,
            intervalHours: Int,
            onlyWhenCharging: Boolean,
            onlyOnWifi: Boolean,
        ) {
            val workManager = WorkManager.getInstance(context)
            if (!enabled) {
                workManager.cancelUniqueWork(WORK_NAME)
                return
            }

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(
                    if (onlyOnWifi) NetworkType.UNMETERED else NetworkType.CONNECTED
                )
                .setRequiresCharging(onlyWhenCharging)
                .build()

            // WorkManager 周期任务最小 15 分钟；设置里给了更小的值就夹到 15 分钟
            val interval = intervalHours.coerceAtLeast(1).toLong()
            val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(interval, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }
}

/**
 * 让「当前设置」和「已注册的后台任务」保持一致。
 *
 * 设置页改了开关、应用启动、数据恢复后都调用它，避免只改设置不生效或残留旧任务。
 */
object AutoBackupScheduler {
    /** 按设置同步一次后台任务，幂等。 */
    fun sync(context: Context, settings: me.rerere.rikkahub.data.datastore.Settings) {
        val config = settings.autoBackupConfig
        AutoBackupWorker.schedule(
            context = context,
            enabled = config.enabled,
            intervalHours = config.intervalHours,
            onlyWhenCharging = config.onlyWhenCharging,
            onlyOnWifi = config.onlyOnWifi,
        )
    }
}
