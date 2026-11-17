package me.rerere.ai.pricing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelPricingTest {
    /** 100 万输入 + 100 万输出，按单价直接算，验证单位换算没写错。 */
    @Test fun costScalesPerMillionTokens() {
        val pricing = ModelPricing(inputPerMillion = 3.0, outputPerMillion = 15.0)
        assertEquals(18.0, pricing.cost(1_000_000, 1_000_000, 0), 0.000001)
    }

    /**
     * 缓存 token 是输入 token 的一部分，不能「输入全价 + 缓存价」重复计算。
     * 例：100 万输入里 80 万命中缓存，输入价 3、缓存价 0.3、输出价 0。
     * 正确结果 = 20 万*3/1M + 80 万*0.3/1M = 0.6 + 0.24 = 0.84
     */
    @Test fun cachedTokensAreNotDoubleCounted() {
        val pricing = ModelPricing(inputPerMillion = 3.0, outputPerMillion = 15.0, cachedInputPerMillion = 0.3)
        val cost = pricing.cost(promptTokens = 1_000_000, completionTokens = 0, cachedTokens = 800_000)
        assertEquals(0.84, cost, 0.000001)
    }

    /** 没单独给缓存定价时，缓存部分按输入价算。 */
    @Test fun missingCachedPriceFallsBackToInputPrice() {
        val pricing = ModelPricing(inputPerMillion = 3.0, outputPerMillion = 0.0)
        val cost = pricing.cost(promptTokens = 1_000_000, completionTokens = 0, cachedTokens = 500_000)
        assertEquals(3.0, cost, 0.000001)
    }

    /** 缓存数异常（大于输入）时不能算出负数。 */
    @Test fun cachedTokensAreClampedToPrompt() {
        val pricing = ModelPricing(inputPerMillion = 3.0, outputPerMillion = 0.0, cachedInputPerMillion = 0.3)
        val cost = pricing.cost(promptTokens = 100, completionTokens = 0, cachedTokens = 999_999)
        assertTrue("不应出现负数花费", cost >= 0.0)
    }

    @Test fun unconfiguredPricingCountsAsZero() {
        val pricing = ModelPricing()
        assertTrue(!pricing.isConfigured)
        assertEquals(0.0, pricing.cost(1_000_000, 1_000_000, 0), 0.000001)
    }

    // ---------- 内置表匹配 ----------

    @Test fun lookupIgnoresProviderPrefix() {
        // 网关返回的是「反代/deepseek-v4.1-flash」，真实模型名在后半段
        val pricing = BuiltInPricing.lookup("反代/deepseek-v4.1-flash")
        assertTrue("应该能从带前缀的 id 匹配到价格", pricing != null)
        assertTrue(pricing!!.isConfigured)
    }

    @Test fun lookupIsCaseInsensitive() {
        assertEquals(
            BuiltInPricing.lookup("deepseek-v4.1-flash")?.inputPerMillion,
            BuiltInPricing.lookup("DeepSeek-V4.1-Flash")?.inputPerMillion,
        )
    }

    @Test fun unknownModelReturnsNullInsteadOfGuessing() {
        assertNull(BuiltInPricing.lookup("some-random-internal-model-xyz"))
    }

    @Test fun longerKeywordWins() {
        // gpt-4o 与 gpt-4o-mini 都能命中时，应当选更具体（更长）的那个
        val mini = BuiltInPricing.lookup("gpt-4o-mini")
        val plain = BuiltInPricing.lookup("gpt-4o")
        assertTrue("mini 的输入价应低于标准版", mini!!.inputPerMillion < plain!!.inputPerMillion)
    }
}
