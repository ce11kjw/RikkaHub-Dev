package me.rerere.rikkahub.ui.context

import androidx.compose.runtime.compositionLocalOf

/**
 * 当前会话的上下文压缩情况，供消息底部的用量卡片展示。
 *
 * 只有会话级信息（压缩掉了多少、压缩后剩多少）是消息自身拿不到的，
 * 用 CompositionLocal 传下去，免得到处改函数签名。
 */
data class ContextCompressionInfo(
    /** 被摘要替代、不再发给模型的消息条数 */
    val foldedMessageCount: Int,
    /** 这些消息原来的 token 数（按最后一次 usage 推算） */
    val foldedTokens: Int,
    /** 摘要本身占的 token（估算） */
    val summaryTokens: Int,
) {
    /** 净省下的 token；摘要比原文还长时可能是负数 */
    val savedTokens: Int get() = foldedTokens - summaryTokens
}

/** 没有压缩时为 null，卡片里就不显示这一行。 */
val LocalContextCompression = compositionLocalOf<ContextCompressionInfo?> { null }
