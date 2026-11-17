package me.rerere.rikkahub.data.sync.autobackup

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.datastore.AutoBackupConfig
import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 自动备份任务注册的真机测试。
 *
 * 重点验证「改设置 -> 任务跟着变」：只把开关存进 DataStore 而不重新注册任务时，
 * 用户会以为开了但实际没在跑。
 */
@RunWith(AndroidJUnit4::class)
class AutoBackupScheduleTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val workManager get() = WorkManager.getInstance(context)
    private val workName = "rikkahub-auto-backup"

    // JUnit4 要求 @Before 返回 void，写成 = runBlocking{} 会返回 Unit 的包装体导致整类无法实例化
    @Before
    fun cleanUp() {
        runBlocking { workManager.cancelUniqueWork(workName) }
    }

    private fun infos(): List<WorkInfo> = workManager.getWorkInfosForUniqueWork(workName).get()

    private fun settings(
        enabled: Boolean,
        intervalHours: Int = 24,
        onlyWhenCharging: Boolean = true,
        onlyOnWifi: Boolean = true,
    ) = Settings(
        autoBackupConfig = AutoBackupConfig(
            enabled = enabled,
            intervalHours = intervalHours,
            onlyWhenCharging = onlyWhenCharging,
            onlyOnWifi = onlyOnWifi,
        )
    )

    @Test fun disabledSettingRegistersNoWork() {
        AutoBackupScheduler.sync(context, settings(enabled = false))
        // 取消后 WorkManager 仍会保留一条 CANCELLED 记录，所以判据是「没有存活任务」而不是列表为空
        val alive = infos().filter { it.state != WorkInfo.State.CANCELLED }
        assertTrue("关闭时不该有存活任务", alive.isEmpty())
    }

    @Test fun enabledSettingRegistersPeriodicWork() {
        AutoBackupScheduler.sync(context, settings(enabled = true))
        val infos = infos()
        assertEquals("开启后应注册一个任务", 1, infos.size)
        val constraints = infos.single().constraints
        assertEquals("应要求 WiFi（UNMETERED）", NetworkType.UNMETERED, constraints.requiredNetworkType)
        assertTrue("应要求充电", constraints.requiresCharging())
    }

    /** 关闭后必须真的撤销，否则用户关了还在偷偷备份。 */
    @Test fun turningOffCancelsExistingWork() {
        AutoBackupScheduler.sync(context, settings(enabled = true))
        assertTrue(infos().isNotEmpty())

        AutoBackupScheduler.sync(context, settings(enabled = false))
        val remaining = infos().filter { it.state != WorkInfo.State.CANCELLED }
        assertTrue("关闭后不该还有存活任务", remaining.isEmpty())
    }

    /** 改间隔只能更新同一个任务，不能变成两个任务同时跑。 */
    @Test fun changingIntervalUpdatesInsteadOfDuplicating() {
        AutoBackupScheduler.sync(context, settings(enabled = true, intervalHours = 24))
        AutoBackupScheduler.sync(context, settings(enabled = true, intervalHours = 6))

        val active = infos().filter { it.state != WorkInfo.State.CANCELLED }
        assertEquals("更新设置后仍应只有一个任务", 1, active.size)
    }

    /** 关掉「仅 WiFi」后网络约束应放宽到 CONNECTED。 */
    @Test fun wifiConstraintFollowsSetting() {
        AutoBackupScheduler.sync(context, settings(enabled = true, onlyOnWifi = false))
        assertEquals(NetworkType.CONNECTED, infos().single().constraints.requiredNetworkType)
    }

    /** 关掉「仅充电」后不应再要求充电。 */
    @Test fun chargingConstraintFollowsSetting() {
        AutoBackupScheduler.sync(context, settings(enabled = true, onlyWhenCharging = false))
        assertTrue(
            "关掉仅充电后不该要求充电",
            !infos().single().constraints.requiresCharging(),
        )
    }
}
