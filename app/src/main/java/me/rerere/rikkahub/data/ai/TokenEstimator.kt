package me.rerere.rikkahub.data.ai

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/**
 * 粗略估算一批消息要占多少 token。
 *
 * 为什么不用精确分词：真正准确要加载对应模型的词表（几十 MB），而且不同供应商的分词器
 * 都不一样。这里只要判断「会不会超」，估算偏保守就够用 —— 估多了最多多压一次，
 * 估少了才会真的被服务端拒绝。
 *
 * 经验值（中英混排、含代码）：
 * - 中文：约 1 字 = 1 token（保守取 1.0，实际常是 0.6~0.7）
 * - 英文：约 4 字符 = 1 token
 * - 代码/JSON：约 3 字符 = 1 token（符号多，token 更碎）
 *
 * 保守意味着宁可多估 20%，也不让请求打出去被拒。
 */
object TokenEstimator {

    /** 每条消息的固定开销（角色标记、分隔符等）。 */
    private const val PER_MESSAGE_OVERHEAD = 4

    /** 图片/文件这类附件按固定值估，拿不到实际大小。 */
    private const val PER_ATTACHMENT_ESTIMATE = 800

    fun estimate(messages: List<UIMessage>): Int {
        if (messages.isEmpty()) return 0
        return messages.sumOf { estimateMessage(it) }
    }

    fun estimateMessage(message: UIMessage): Int {
        var total = PER_MESSAGE_OVERHEAD
        for (part in message.parts) {
            total += when (part) {
                is UIMessagePart.Text -> estimateText(part.text)
                is UIMessagePart.Image -> PER_ATTACHMENT_ESTIMATE
                else -> {
                    // 工具调用/结果等结构化内容，按 toString 长度粗估
                    val raw = runCatching { part.toString().length }.getOrDefault(0)
                    raw / 3
                }
            }
        }
        return total
    }

    /**
     * 估算一段文本的 token 数。
     *
     * 分开数中文和其余字符，因为同一个字符在不同语言里的 token 密度差好几倍。
     */
    fun estimateText(text: String): Int {
        if (text.isEmpty()) return 0
        var cjk = 0
        var other = 0
        for (ch in text) {
            if (ch.code in 0x4E00..0x9FFF || ch.code in 0x3040..0x30FF || ch.code in 0xAC00..0xD7AF) {
                cjk++
            } else {
                other++
            }
        }
        // 中文 1 字约 1 token；其余按 3.5 字符 1 token（比 4 保守一点）
        return cjk + (other / 3.5).toInt() + 1
    }

    /**
     * 判断「发出去会不会超」。
     *
     * @param estimated 估算的 token 总数（含历史 + 新消息）
     * @param contextLength 模型窗口大小
     * @return 需要压缩时返回 true
     */
    fun needsCompression(estimated: Int, contextLength: Int): Boolean {
        if (contextLength <= 0) return false
        // 留 15% 余量：估算本身有误差，加上这一轮模型输出也要占窗口
        val ceiling = (contextLength * 0.85).toInt()
        return estimated >= ceiling
    }
}
