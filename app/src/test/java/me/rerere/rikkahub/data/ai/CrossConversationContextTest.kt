package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ContextSummary
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * 跨对话上下文：把「上一段对话」变成新会话的开场背景。
 *
 * 这里的重点是「带什么」和「什么时候带」，因为一段错误的前文会污染整场对话，
 * 而漏带只是少了点便利。
 */
class CrossConversationContextTest {

    private fun node(message: UIMessage) = MessageNode(messages = listOf(message), selectIndex = 0)

    private fun conversation(
        vararg messages: UIMessage,
        title: String = "上一段",
        summary: ContextSummary? = null,
        updateAt: Instant = Instant.parse("2026-11-17T10:00:00Z"),
    ) = Conversation(
        id = Uuid.random(),
        assistantId = Uuid.random(),
        title = title,
        messageNodes = messages.map(::node),
        contextSummary = summary,
        updateAt = updateAt,
    )

    private val now = Instant.parse("2026-11-17T12:00:00Z")

    @Test
    fun `没有消息时不生成背景`() {
        assertNull(CrossConversationContext.build(conversation(), now))
    }

    @Test
    fun `只有工具调用的会话不生成背景`() {
        val tool = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.ToolCall(toolCallId = "1", toolName = "x", arguments = "{}"),
                UIMessagePart.ToolResult(
                    toolCallId = "1",
                    toolName = "x",
                    content = kotlinx.serialization.json.JsonPrimitive(""),
                    arguments = kotlinx.serialization.json.JsonPrimitive("{}"),
                ),
            ),
        )
        assertNull(CrossConversationContext.build(conversation(tool, title = "工具"), now))
    }

    /** 核心场景：新会话开场就能看到上一段聊了什么。 */
    @Test
    fun `把用户和助手的发言带进背景`() {
        val previous = conversation(
            UIMessage.user("把阈值改成 600k"),
            UIMessage.assistant("改好了"),
            title = "改压缩阈值",
        )

        val built = CrossConversationContext.build(previous, now)

        assertNotNull(built)
        assertTrue("要带标题", built!!.contains("改压缩阈值"))
        assertTrue("要带用户的话", built.contains("把阈值改成 600k"))
        assertTrue("要带助手的话", built.contains("改好了"))
        assertTrue("要说明这是上一段对话", built.contains("[上一段对话]"))
        assertTrue("要给出时间线索", built.contains("小时前"))
    }

    /** 时间线索必须是人能读懂的相对时间，绝对日期模型判断不出「还该不该当作当前话题」。 */
    @Test
    fun `时间按距离现在多久来描述`() {
        val justNow = conversation(
            UIMessage.user("刚说完"),
            updateAt = now.minusSeconds(300),
        )
        assertTrue(CrossConversationContext.build(justNow, now)!!.contains("刚刚"))

        val daysAgo = conversation(UIMessage.user("几天前"), updateAt = now.minusSeconds(86_400L * 3))
        assertTrue(CrossConversationContext.build(daysAgo, now)!!.contains("3 天前"))
    }

    /** 上一个会话已经压缩过时，它的摘要比重新拼尾部消息更完整，优先用摘要。 */
    @Test
    fun `有压缩摘要时优先用摘要`() {
        val summaryText = "这是一段够长的压缩摘要。".repeat(40)
        val previous = conversation(
            UIMessage.user("被压缩掉的内容"),
            summary = ContextSummary(summary = summaryText, upToNodeId = Uuid.random()),
        )

        val built = CrossConversationContext.build(previous, now)!!

        assertTrue(built.contains(summaryText.take(50)))
    }

    /** 摘要太短说明信息不够，得用尾部消息兜底，否则新会话几乎拿不到上下文。 */
    @Test
    fun `摘要太短时用尾部消息兜底`() {
        val previous = conversation(
            UIMessage.user("用户说过的一件具体的事"),
            summary = ContextSummary(summary = "已解决", upToNodeId = Uuid.random()),
        )

        val built = CrossConversationContext.build(previous, now)!!

        assertTrue("短摘要本身要留着", built.contains("已解决"))
        assertTrue("尾部消息要补上", built.contains("用户说过的一件具体的事"))
    }

    /** 背景每轮都在请求前缀里，必须有长度上限，别让一段旧对话吃掉整个窗口。 */
    @Test
    fun `背景长度有上限`() {
        val huge = conversation(
            *Array(30) { UIMessage.user("很长的一句话".repeat(200)) },
            title = "很长",
        )

        val built = CrossConversationContext.build(huge, now)!!

        assertTrue(
            "背景不能超过上限",
            built.length <= CrossConversationContext.MAX_SUMMARY_LENGTH + 500,
        )
    }

    /**
     * 内容超长时必须丢掉最旧的，留下最新的。
     *
     * 这个曾经反着做过：从旧到新拼接再截断，结果「上一段对话」全是几小时前的开场白，
     * 用户说「接着刚才」时最需要的最后几句反而没了。
     */
    @Test
    fun `内容超长时保留最新的对话`() {
        // 每条用户消息都接近单条上限，12 条加起来必然超过总预算
        val filler = "填充内容".repeat(200)
        val messages = buildList {
            add(UIMessage.user("很早以前说的话"))
            repeat(11) { add(UIMessage.user(filler)) }
            add(UIMessage.user("最后一句要紧的话"))
        }
        val previous = conversation(*messages.toTypedArray())

        val built = CrossConversationContext.build(previous, now)!!

        assertTrue("最新的一句必须在", built.contains("最后一句要紧的话"))
        assertFalse("最早的一句应该被挤掉", built.contains("很早以前说的话"))
    }

    // ---- 什么时候往会话上挂 ----

    @Test
    fun `新会话会带上上一个会话的背景`() {
        val previous = conversation(UIMessage.user("上一句"))
        val fresh = Conversation(id = Uuid.random(), assistantId = previous.assistantId, messageNodes = emptyList())

        val seeded = fresh.withPriorContextFrom(previous, now)

        assertNotNull(seeded.priorContext)
        assertSame("聊天记录不能被动到", fresh.messageNodes, seeded.messageNodes)
    }

    @Test
    fun `已经有背景时不重复生成`() {
        val previous = conversation(UIMessage.user("上一句"))
        val fresh = Conversation(
            id = Uuid.random(),
            assistantId = previous.assistantId,
            messageNodes = emptyList(),
            priorContext = "已有的背景",
        )

        assertSame(fresh, fresh.withPriorContextFrom(previous, now))
    }

    /** 用户已经开口聊了，再从中间插一段「上一集」会打断节奏。 */
    @Test
    fun `已经开始对话时不补背景`() {
        val previous = conversation(UIMessage.user("上一句"))
        val started = Conversation(
            id = Uuid.random(),
            assistantId = previous.assistantId,
            messageNodes = listOf(node(UIMessage.user("这一句"))),
        )

        assertSame(started, started.withPriorContextFrom(previous, now))
    }

    @Test
    fun `没有上一个会话时原样返回`() {
        val fresh = Conversation(id = Uuid.random(), assistantId = Uuid.random(), messageNodes = emptyList())
        assertSame(fresh, fresh.withPriorContextFrom(null, now))
    }

    /** 自己接自己会无限递归（虽然调用方拦了，但这里也要挡一道）。 */
    @Test
    fun `不会把会话自己当成上一段`() {
        val self = conversation(UIMessage.user("自己"))
        assertSame(self, self.withPriorContextFrom(self, now))
    }

    /** 背景不能被当成一条真实消息写进聊天记录。 */
    @Test
    fun `背景只影响请求不影响聊天记录`() {
        val previous = conversation(UIMessage.user("上一句"))
        val fresh = Conversation(id = Uuid.random(), assistantId = previous.assistantId, messageNodes = emptyList())
        val seeded = fresh.withPriorContextFrom(previous, now)

        assertEquals(0, seeded.messageNodes.size)
        assertEquals(0, seeded.currentMessages.size)
        assertFalse(seeded.currentMessages.any { it.toText().contains("上一句") })
    }
}
