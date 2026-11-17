package me.rerere.rikkahub.data.ai

import me.rerere.ai.ui.UIMessage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * token 估算：只要能判断「会不会超」就够，不追求精确。
 *
 * 关键性质：
 * 1. 宁可估多不要估少（估少会导致请求被服务端拒绝）
 * 2. 中英文密度差异要体现出来
 * 3. 空内容不能算出负数或崩溃
 */
class TokenEstimatorTest {

    @Test
    fun `空文本估 0`() {
        assertTrue(TokenEstimator.estimateText("") == 0)
    }

    @Test
    fun `中文按每字约 1 token 估`() {
        val n = TokenEstimator.estimateText("这是一段中文测试")
        // 8 个汉字，允许 ±2 的浮动
        assertTrue("估算值 $n 应接近 8", n in 6..12)
    }

    @Test
    fun `英文同样长度估得更少`() {
        val zh = TokenEstimator.estimateText("这是一段中文测试内容")
        val en = TokenEstimator.estimateText("this is a test content")
        assertTrue("英文($en) 应少于中文($zh)", en < zh)
    }

    @Test
    fun `估算结果偏保守`() {
        // 10 个汉字，真实 token 通常在 6-7，估算不应低于这个数
        val n = TokenEstimator.estimateText("十个汉字的文本内容")
        assertTrue("估算 $n 不应过于乐观", n >= 8)
    }

    @Test
    fun `消息级估算包含固定开销`() {
        val one = TokenEstimator.estimate(listOf(UIMessage.user("")))
        assertTrue("空消息也有角色开销", one > 0)
    }

    @Test
    fun `多消息累加`() {
        val single = TokenEstimator.estimate(listOf(UIMessage.user("测试")))
        val double = TokenEstimator.estimate(listOf(UIMessage.user("测试"), UIMessage.user("测试")))
        assertTrue("两条应比一条多", double > single)
    }

    @Test
    fun `超过窗口 85% 才算需要压缩`() {
        val window = 1_000_000
        assertFalse("50 万不该触发", TokenEstimator.needsCompression(500_000, window))
        assertFalse("80 万不该触发", TokenEstimator.needsCompression(800_000, window))
        assertTrue("90 万该触发", TokenEstimator.needsCompression(900_000, window))
        assertTrue("超了必然触发", TokenEstimator.needsCompression(1_200_000, window))
    }

    @Test
    fun `窗口未知时不触发压缩`() {
        assertFalse(TokenEstimator.needsCompression(1_000_000, 0))
        assertFalse(TokenEstimator.needsCompression(1_000_000, -1))
    }

    /**
     * 真实场景边界：1M 窗口下，
     * 60 万字约占 60%，不该触发压缩（还有空间）；
     * 90 万字约占 90%，必须触发。
     */
    @Test
    fun `60 万字不触发但有足够余量`() {
        val text = "错误信息".repeat(150_000) // 60 万字
        val estimated = TokenEstimator.estimateText(text)
        assertTrue("60 万字应估出 40 万以上，实际 $estimated", estimated > 400_000)
        assertFalse(
            "60 万约占窗口 60%，不该压缩（估 $estimated）",
            TokenEstimator.needsCompression(estimated, 1_000_000),
        )
    }

    @Test
    fun `90 万字触发压缩`() {
        val text = "错误信息".repeat(225_000) // 90 万字
        val estimated = TokenEstimator.estimateText(text)
        assertTrue(
            "90 万约占窗口 90%，应触发压缩（估 $estimated）",
            TokenEstimator.needsCompression(estimated, 1_000_000),
        )
    }

    /** 小窗口模型：同样的内容更容易触发。 */
    @Test
    fun `同样的内容在小窗口下会触发压缩`() {
        val text = "错误信息".repeat(50_000) // 20 万字
        val estimated = TokenEstimator.estimateText(text)
        assertFalse("1M 窗口下不触发", TokenEstimator.needsCompression(estimated, 1_000_000))
        assertTrue("128k 窗口下必须触发", TokenEstimator.needsCompression(estimated, 128_000))
    }
}
