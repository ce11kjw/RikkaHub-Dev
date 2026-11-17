package me.rerere.rikkahub.data.model

import me.rerere.ai.ui.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 上下文压缩：摘要只影响「发给模型的消息」，不碰聊天记录。
 *
 * 这是这套改动的核心约束 —— 压缩后 messageNodes 必须一字不差，
 * 用户翻历史时看到的始终是原始对话，不会冒出一条摘要节点。
 */
class ConversationContextSummaryTest {

    private fun conversation(vararg texts: String): Conversation {
        val nodes = texts.mapIndexed { i, t ->
            if (i % 2 == 0) UIMessage.user(t).toMessageNode()
            else UIMessage.assistant(t).toMessageNode()
        }
        return Conversation(assistantId = Uuid.random(), messageNodes = nodes)
    }

    @Test
    fun `没有摘要时发给模型的就是完整历史`() {
        val source = conversation("a", "b", "c")

        assertEquals(source.currentMessages, source.messagesForModel())
    }

    @Test
    fun `有摘要时切点之前的历史被摘要替代`() {
        val source = conversation("a", "b", "c", "d")
        val cutoff = source.messageNodes[1].id
        val compressed = source.copy(
            contextSummary = ContextSummary(summary = "前两条的摘要", upToNodeId = cutoff),
        )

        val sent = compressed.messagesForModel()

        // 摘要 + 切点之后的两条
        assertEquals(3, sent.size)
        assertEquals("前两条的摘要", sent.first().toText())
        assertTrue("摘要要标记为合成消息，界面上不当成用户消息", sent.first().isSynthetic)
        assertEquals(source.currentMessages.drop(2), sent.drop(1))
    }

    @Test
    fun `压缩后聊天记录一个字都不变`() {
        val source = conversation("a", "b", "c", "d")
        val before = source.messageNodes
        val compressed = source.copy(
            contextSummary = ContextSummary(
                summary = "摘要",
                upToNodeId = source.messageNodes[1].id,
            ),
        )

        // 关键断言：messageNodes 没被改动，也没多出摘要节点
        assertEquals(before, compressed.messageNodes)
        assertEquals(4, compressed.messageNodes.size)
        assertTrue(compressed.messageNodes.none { it.currentMessage.isContextCheckpoint })
    }

    @Test
    fun `切点节点被删掉时摘要失效，退回完整历史`() {
        val source = conversation("a", "b", "c")
        val compressed = source.copy(
            contextSummary = ContextSummary(
                summary = "摘要",
                upToNodeId = Uuid.random(), // 指向一个不存在的节点
            ),
        )

        // 不能因为摘要指向失效就丢掉全部历史，那样等于把对话弄没了
        assertEquals(source.currentMessages, compressed.messagesForModel())
    }

    @Test
    fun `切点是最后一条时仍保留摘要，不会发出空列表`() {
        val source = conversation("a", "b")
        val compressed = source.copy(
            contextSummary = ContextSummary(
                summary = "摘要",
                upToNodeId = source.messageNodes.last().id,
            ),
        )

        // 切点之后没有消息，仍然要把摘要发出去，否则模型什么上下文都没有
        val sent = compressed.messagesForModel()
        assertEquals(1, sent.size)
        assertEquals("摘要", sent.first().toText())
    }

    @Test
    fun `没有摘要时原样返回历史`() {
        val source = conversation("a", "b")
        // currentMessages 是计算属性，每次访问都新建 List，
        // 所以比内容而不是比引用
        assertEquals(source.currentMessages, source.messagesForModel())
        assertEquals(2, source.messagesForModel().size)
    }
}
