package me.rerere.ai.pricing

import kotlinx.serialization.Serializable

/**
 * 一个模型的价格，单位是「美元 / 100 万 token」。
 *
 * 用百万分之一的单价而不是「每次请求多少钱」，是因为各家 API 都按 token 计价，
 * 而且输入/输出/缓存命中三档价格差很多（缓存命中常常只有输入的 1/10）。
 */
@Serializable
data class ModelPricing(
    val inputPerMillion: Double = 0.0,
    val outputPerMillion: Double = 0.0,
    /** 缓存读取的价格；留空表示按输入价计（有些供应商不给缓存单独定价）。 */
    val cachedInputPerMillion: Double? = null,
) {
    val isConfigured: Boolean
        get() = inputPerMillion > 0.0 || outputPerMillion > 0.0

    /**
     * 算这一次请求花了多少钱。
     *
     * 注意 [cachedTokens] 是 [promptTokens] 的一部分（缓存命中的那部分），
     * 所以计价方式是「没命中的部分按输入价 + 命中的部分按缓存价」，不能两者相加。
     */
    fun cost(promptTokens: Long, completionTokens: Long, cachedTokens: Long): Double {
        if (!isConfigured) return 0.0
        val cached = cachedTokens.coerceIn(0, promptTokens)
        val uncachedPrompt = promptTokens - cached
        val cachedPrice = cachedInputPerMillion ?: inputPerMillion
        return (uncachedPrompt * inputPerMillion +
            cached * cachedPrice +
            completionTokens * outputPerMillion) / 1_000_000.0
    }
}

/**
 * 内置的常见模型价格表，用于「没手动填价格」时给一个合理估算。
 *
 * 数据取自各厂商公开定价（2026 年），单位美元/百万 token。
 * 只收录能确定价格的；查不到的返回 null，让界面显示「未配置价格」而不是算出一个错的数字。
 * 匹配时按模型 id 的关键词做最长匹配，所以 `gpt-5.6-luna` 这种带后缀的也能命中 `gpt-5`。
 */
object BuiltInPricing {
    /** 关键词 → 价格；匹配时按 key 长度从长到短尝试，避免 `gpt-4` 抢走 `gpt-4o`。 */
    private val table: List<Pair<String, ModelPricing>> = listOf(
        // OpenAI
        "gpt-5.6" to ModelPricing(1.25, 10.0, 0.125),
        "gpt-5" to ModelPricing(1.25, 10.0, 0.125),
        "gpt-4o-mini" to ModelPricing(0.15, 0.60, 0.075),
        "gpt-4o" to ModelPricing(2.50, 10.0, 1.25),
        "gpt-4.1-mini" to ModelPricing(0.40, 1.60, 0.10),
        "gpt-4.1" to ModelPricing(2.00, 8.00, 0.50),
        "o4-mini" to ModelPricing(1.10, 4.40, 0.275),
        "o3-mini" to ModelPricing(1.10, 4.40, 0.55),
        "o3" to ModelPricing(2.00, 8.00, 0.50),
        // Anthropic
        "claude-opus-4" to ModelPricing(15.0, 75.0, 1.50),
        "claude-opus-5" to ModelPricing(15.0, 75.0, 1.50),
        "claude-sonnet-4" to ModelPricing(3.00, 15.0, 0.30),
        "claude-haiku-4" to ModelPricing(0.80, 4.00, 0.08),
        "claude-3-5-sonnet" to ModelPricing(3.00, 15.0, 0.30),
        // Google
        "gemini-2.5-pro" to ModelPricing(1.25, 10.0, 0.31),
        "gemini-2.5-flash" to ModelPricing(0.30, 2.50, 0.075),
        "gemini-3" to ModelPricing(1.25, 10.0, 0.31),
        // DeepSeek
        "deepseek-v4.1" to ModelPricing(0.28, 0.42, 0.028),
        "deepseek-v4" to ModelPricing(0.28, 0.42, 0.028),
        "deepseek-reasoner" to ModelPricing(0.55, 2.19, 0.14),
        "deepseek-chat" to ModelPricing(0.28, 0.42, 0.028),
        // 其他常见
        "kimi-k3" to ModelPricing(0.60, 2.50, 0.15),
        "kimi-k2" to ModelPricing(0.60, 2.50, 0.15),
        "qwen3" to ModelPricing(0.40, 1.20, 0.10),
        "glm-5" to ModelPricing(0.60, 2.20, 0.11),
        "glm-4.6" to ModelPricing(0.60, 2.20, 0.11),
        "minimax-m3" to ModelPricing(0.30, 1.20, 0.03),
        "grok-4" to ModelPricing(3.00, 15.0, 0.75),
        "mimo" to ModelPricing(0.14, 0.56, 0.014),
    ).sortedByDescending { it.first.length }

    /**
     * 按模型 id 猜价格。匹配时忽略大小写，并跳过 `provider/` 前缀
     * （你的网关返回的是 `反代/deepseek-v4.1-flash`，真正的模型名在后半段）。
     */
    fun lookup(modelId: String): ModelPricing? {
        val name = modelId.substringAfterLast('/').lowercase()
        if (name.isBlank()) return null
        return table.firstOrNull { (keyword, _) -> name.contains(keyword) }?.second
    }
}
