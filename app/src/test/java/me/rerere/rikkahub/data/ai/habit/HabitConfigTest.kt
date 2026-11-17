package me.rerere.rikkahub.data.ai.habit

import me.rerere.rikkahub.data.datastore.HabitAnalysisConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 习惯分析配置的默认值。
 *
 * 关键点：**默认关闭**。这是个会烧 token 的后台任务，
 * 不该在用户不知情时自己跑起来。
 */
class HabitConfigTest {

    @Test
    fun `默认关闭，不会偷偷消耗 token`() {
        val config = HabitAnalysisConfig()
        assertFalse("默认必须是关的", config.enabled)
    }

    @Test
    fun `默认范围 30 天，间隔 14 天`() {
        val config = HabitAnalysisConfig()
        assertEquals(30, config.rangeDays)
        assertEquals(14, config.intervalDays)
    }

    @Test
    fun `从未分析过时 lastRunTime 为 0`() {
        assertEquals(0L, HabitAnalysisConfig().lastRunTime)
    }

    /** 开了之后，间隔判断要正确 —— 没到时间就不该重复分析。 */
    @Test
    fun `间隔判断逻辑`() {
        val now = System.currentTimeMillis()
        val day = 24L * 60 * 60 * 1000

        // 刚分析过 → 不该再跑
        val justRan = HabitAnalysisConfig(lastRunTime = now)
        val elapsed1 = now - justRan.lastRunTime
        assertTrue("刚跑过应判定为未到时间", elapsed1 < justRan.intervalDays * day)

        // 15 天前分析过（间隔 14 天）→ 该跑了
        val longAgo = HabitAnalysisConfig(lastRunTime = now - 15 * day)
        val elapsed2 = now - longAgo.lastRunTime
        assertTrue("超过间隔应触发", elapsed2 >= longAgo.intervalDays * day)

        // 从没跑过 → elapsed 是当前时间戳，必然超过间隔
        val never = HabitAnalysisConfig(lastRunTime = 0)
        assertTrue("从未跑过应触发", now - never.lastRunTime >= never.intervalDays * day)
    }

    @Test
    fun `范围天数决定收集窗口`() {
        val short = HabitAnalysisConfig(rangeDays = 7)
        val long = HabitAnalysisConfig(rangeDays = 90)
        assertTrue("90 天窗口应大于 7 天", long.rangeDays > short.rangeDays)
    }
}
