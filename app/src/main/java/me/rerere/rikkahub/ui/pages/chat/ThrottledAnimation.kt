package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay

/**
 * 把动画值的更新频率限制到 [framesPerSecond]，其余帧复用上一次的值。
 *
 * 为什么需要：动态渐变背景每帧都在变，而它同时是模糊采样的源。
 * 144Hz 下这意味着每秒 144 次「重画渐变 + 重算模糊」，GPU 和合成器都吃不消，
 * 表现就是整个 App 掉帧。
 *
 * 但光斑的漂移周期是 5.5~8.5 秒一圈 —— 每秒 30 次采样和 144 次采样，
 * 对肉眼来说完全一样。所以这里只是把「重复计算同一个几乎没有变化的东西」的次数降下来，
 * 画面本身一帧不丢，效果不变。
 *
 * @param source 原始动画值（通常是 infiniteTransition 驱动）
 * @param framesPerSecond 采样频率。默认 30，是流畅和开销的平衡点
 */
@Composable
internal fun rememberThrottledFloat(
    source: State<Float>,
    framesPerSecond: Int = 30,
): State<Float> {
    // 用 mutableFloatStateOf 而不是普通变量：只有值真的变化时才触发重组重绘
    val throttled = remember { mutableFloatStateOf(source.value) }
    val frameMillis = (1000L / framesPerSecond.coerceAtLeast(1)).coerceAtLeast(1L)

    LaunchedEffect(source, framesPerSecond) {
        while (true) {
            // 读当前值，变了才写回 —— 值没变就不会引起任何重绘
            val current = source.value
            if (current != throttled.floatValue) {
                throttled.floatValue = current
            }
            delay(frameMillis)
        }
    }
    return throttled
}
