package me.rerere.rikkahub.data.datastore

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 自动压缩触发点的计算规则。
 *
 * 4.0 起阈值是绝对 token 数（设置页滑块 0 ~ 1M，默认 600k）。
 * 这里锁住三条：默认值、范围夹紧、以及"不能超过模型窗口"的换算。
 */
class AutoCompressLimitTest {

    @Test
    fun `默认阈值是 600k`() {
        assertEquals(600_000, DEFAULT_AUTO_COMPRESS_TOKEN_LIMIT)
    }

    @Test
    fun `滑块范围是 0 到 1M`() {
        assertEquals(0, AUTO_COMPRESS_TOKEN_RANGE.first)
        assertEquals(1_000_000, AUTO_COMPRESS_TOKEN_RANGE.last)
    }

    @Test
    fun `超出范围的值会被夹紧`() {
        val tooSmall = (-500).coerceIn(AUTO_COMPRESS_TOKEN_RANGE)
        val tooBig = 5_000_000.coerceIn(AUTO_COMPRESS_TOKEN_RANGE)

        assertEquals(0, tooSmall)
        assertEquals(1_000_000, tooBig)
    }

    /**
     * 小窗口模型不能配 600k 阈值 —— 否则永远等不到触发，请求会先被服务端拒绝。
     * 与 ChatService.effectiveCompressLimit 保持同一套规则：留 10% 余量。
     */
    private fun effectiveLimit(configured: Int, modelContextLength: Int): Int {
        if (modelContextLength <= 0) return configured.coerceAtLeast(1)
        val headroom = (modelContextLength / 10).coerceAtLeast(1_000)
        val modelCeiling = (modelContextLength - headroom).coerceAtLeast(1_000)
        return minOf(configured, modelCeiling).coerceAtLeast(1)
    }

    @Test
    fun `大窗口模型按用户设定走`() {
        // 1M 窗口，留 100k 余量 → 上限 900k；用户设 600k，就用 600k
        assertEquals(600_000, effectiveLimit(600_000, 1_000_000))
    }

    @Test
    fun `小窗口模型自动缩到窗口以内`() {
        // 32k 窗口，留 3.2k 余量 → 上限 28.8k，比 600k 小，取 28800
        assertEquals(28_800, effectiveLimit(600_000, 32_000))
    }

    @Test
    fun `窗口极小时至少留 1k`() {
        // 4k 窗口，10% 是 400，但下限 1k → 上限 3k
        assertEquals(3_000, effectiveLimit(600_000, 4_000))
        // 1k 窗口，任何情况下都不能算出 0
        assertEquals(1_000, effectiveLimit(600_000, 1_000))
    }

    @Test
    fun `上下文长度未知时按用户设定走`() {
        assertEquals(600_000, effectiveLimit(600_000, 0))
        assertEquals(600_000, effectiveLimit(600_000, -1))
    }

    @Test
    fun `设成 0 表示关闭自动压缩`() {
        // 0 会让 reachesAutoCompressThreshold 永远成立，调用方据此关闭功能；
        // 这里锁住夹紧后仍是 0，不会被改成别的值
        assertEquals(0, 0.coerceIn(AUTO_COMPRESS_TOKEN_RANGE))
    }
}
