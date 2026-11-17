package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import java.time.Duration
import java.time.Instant

/**
 * 新会话自动接上文的功能里，「接什么」由这里决定。
 *
 * 只用本地数据拼，不额外调模型：新会话的第一轮本来就要等一次网络往返，
 * 再插一次摘要生成会让「我接着刚才说」变成十几秒的空等。
 */
internal object CrossConversationContext {
    /** 摘要正文上限，超了截断。它每轮都在请求前缀里，太长会白占窗口 */
    const val MAX_SUMMARY_LENGTH = 3_000

    /** 取多少条消息拼摘要 */
    private const val TAIL_MESSAGE_COUNT = 12

    /** 单条消息最多带多少字，避免一条长回答吃掉整个摘要 */
    private const val TAIL_MESSAGE_LENGTH = 600

    /**
     * 上一个会话已经有一套压缩摘要时，那个摘要比重新拼的尾部消息更完整，优先用它。
     * 但它可能很短（只剩一句「已解决」），太短就加上尾部消息兜底。
     */
    private const val MIN_SUMMARY_TO_SKIP_TAIL = 400

    /**
     * 把 [previous] 变成一段可以直接塞进 system prompt 的前文说明。
     *
     * @return 没有可用内容时返回 null，调用方据此跳过拼接
     */
    fun build(previous: Conversation, now: Instant = Instant.now()): String? {
        val messages = previous.currentMessages.filter {
            (it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT) && it.toText().isNotBlank()
        }
        val storedSummary = previous.contextSummary?.summary?.trim().orEmpty()

        val body = if (storedSummary.length >= MIN_SUMMARY_TO_SKIP_TAIL) {
            storedSummary
        } else {
            val tail = renderTail(messages.filterNot { it.isContextCheckpoint })
            listOf(storedSummary, tail).filter { it.isNotBlank() }.joinToString("\n\n")
        }
        if (body.isBlank()) return null

        val title = previous.title.ifBlank { "（未命名）" }
        return buildString {
            appendLine("[上一段对话]")
            appendLine("用户刚刚在另一个会话里和你说过下面这些内容。现在这是一个新会话，")
            appendLine("继续聊的时候直接当作已经知道的背景，不要向用户复述，也不要问「你之前说过什么」。")
            appendLine("如果用户说「接着上面」「继续」，指的就是这段内容。")
            appendLine()
            appendLine("标题：$title")
            appendLine("时间：${describeTime(previous.updateAt, now)}")
            appendLine()
            appendLine(body.trim().take(MAX_SUMMARY_LENGTH))
        }.trim()
    }

    /**
     * 从最新的消息往回填，装不下的丢掉最旧的。
     *
     * 反过来（从旧到新拼接再截断）会把最新的对话截掉，留下几小时前的开场白 ——
     * 用户说「接着刚才」时最需要的恰恰是最后那几句。
     */
    private fun renderTail(messages: List<UIMessage>): String {
        val picked = ArrayDeque<String>()
        var budget = MAX_SUMMARY_LENGTH
        for (message in messages.takeLast(TAIL_MESSAGE_COUNT).asReversed()) {
            // 用户说的话信息密度高，少截一点；助手的回复常有大段代码，截短些
            val limit = if (message.role == MessageRole.USER) TAIL_MESSAGE_LENGTH else TAIL_MESSAGE_LENGTH / 2
            val who = if (message.role == MessageRole.USER) "用户" else "你"
            val line = "$who：${message.toText().trim().take(limit)}"
            if (line.length + 1 > budget) break
            budget -= line.length + 1
            picked.addFirst(line)
        }
        return picked.joinToString("\n")
    }

    /** 「3 小时前」比一个绝对日期更容易让模型判断该不该当作当前话题 */
    private fun describeTime(then: Instant, now: Instant): String {
        val elapsed = Duration.between(then, now)
        val hours = elapsed.toHours()
        return when {
            elapsed.isNegative -> "刚刚"
            hours < 1 -> "刚刚（${elapsed.toMinutes()} 分钟前）"
            hours < 24 -> "${hours} 小时前"
            hours < 24 * 7 -> "${hours / 24} 天前"
            else -> "${hours / 24 / 7} 周前"
        }
    }
}

/**
 * 给新会话算一次开场摘要，已经有就不再算。
 *
 * 只在会话还没有任何消息时生效：用户已经开始聊了再补一段「上一集」，
 * 会打断当前的对话节奏。
 */
internal fun Conversation.withPriorContextFrom(previous: Conversation?, now: Instant = Instant.now()): Conversation {
    if (previous == null || previous.id == id) return this
    if (priorContext != null) return this
    val hasOwnMessages = currentMessages.any {
        it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT
    }
    if (hasOwnMessages) return this
    // 不在这里打日志：这段逻辑是纯数据变换，单元测试会直接跑它，
    // 而 android.util.Log 在 JVM 测试里没打桩。摘要本身存进 priorContext，要看得见
    val built = CrossConversationContext.build(previous, now) ?: return this
    return copy(priorContext = built)
}
