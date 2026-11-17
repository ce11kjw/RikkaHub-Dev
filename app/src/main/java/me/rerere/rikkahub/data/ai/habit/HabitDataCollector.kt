package me.rerere.rikkahub.data.ai.habit

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.repository.ConversationRepository

private const val TAG = "HabitDataCollector"

/**
 * 从本地对话里抽出用户的发言，供习惯分析使用。
 *
 * 只取**用户说的话**，助手回复一律不要：
 * 1. 省一半以上的 token；
 * 2. 习惯体现在他怎么提要求、怎么纠正，助手说什么反而是噪声。
 *
 * 粘贴的大段文档/代码也滤掉 —— 那些不是他的表达，统计进去会把"习惯"带偏
 * （实测里"参数名""返回值"这类词能进高频词前五，全是粘贴内容贡献的）。
 */
class HabitDataCollector(
    private val conversationDAO: ConversationDAO,
    private val conversationRepository: ConversationRepository,
) {
    private companion object {
        /** 超过这个长度按粘贴内容处理，不算他的表达。 */
        const val MAX_MESSAGE_CHARS = 300
        const val MIN_MESSAGE_CHARS = 2
        /** 最多扫多少个会话，避免历史很长时卡住。 */
        const val MAX_CONVERSATIONS = 300
    }

    /**
     * 收集最近 [days] 天内的用户消息。
     *
     * @param maxMessages 最多返回多少条，控制送给模型的 token 量
     * @return 去重后的用户发言，按时间从早到晚
     */
    suspend fun collect(days: Int, maxMessages: Int = 800): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            val cutoff = System.currentTimeMillis() - days * 24L * 60 * 60 * 1000
            val conversations = conversationDAO.getRecentConversations(MAX_CONVERSATIONS)
            val messages = mutableListOf<String>()

            for (entity in conversations) {
                // 会话列表已按更新时间倒序，遇到够老的就停
                if (entity.updateAt < cutoff) break

                val conversation = runCatching {
                    conversationRepository.getConversationById(kotlin.uuid.Uuid.parse(entity.id))
                }.getOrNull() ?: continue

                for (node in conversation.messageNodes) {
                    val msg = node.currentMessage
                    if (msg.role != MessageRole.USER) continue
                    val text = msg.userTextOrNull() ?: continue
                    if (text.length < MIN_MESSAGE_CHARS || text.length > MAX_MESSAGE_CHARS) continue
                    messages.add(text)
                }
                if (messages.size >= maxMessages) break
            }

            // 去重：同一句反复说不增加信息量，还会让统计失真
            messages.distinct().take(maxMessages)
        }.onFailure {
            Log.w(TAG, "collect failed", it)
        }.getOrDefault(emptyList())
    }

    /** 去掉图片、文件等非文本内容，只留文字。 */
    private fun UIMessage.userTextOrNull(): String? {
        val text = parts.filterIsInstance<UIMessagePart.Text>()
            .joinToString(" ") { it.text }
            .trim()
        return text.takeIf { it.isNotBlank() }
    }
}
