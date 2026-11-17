package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 「跟随聊天模型」的解析规则。
 *
 * 快速/翻译/OCR/记忆整理这几个位置的模型字段是 Uuid?，null 表示跟随聊天模型。
 * 统一走 resolveModelId，避免某个调用点漏了回退、用了失效的 id。
 */
class ResolveModelIdTest {

    private fun provider(vararg modelIds: Uuid): ProviderSetting =
        ProviderSetting.OpenAI(
            id = Uuid.random(),
            name = "p",
            models = modelIds.map { Model(modelId = "m-${it.toString().take(4)}", displayName = "m", id = it) },
        )

    @Test
    fun `字段为 null 时跟随聊天模型`() {
        val chatId = Uuid.random()
        val settings = Settings(
            chatModelId = chatId,
            providers = listOf(provider(chatId)),
        )
        assertEquals("null 应回退到聊天模型", chatId, settings.resolveModelId(null))
    }

    @Test
    fun `字段有值时用自己的`() {
        val chatId = Uuid.random()
        val fastId = Uuid.random()
        val settings = Settings(
            chatModelId = chatId,
            providers = listOf(provider(chatId, fastId)),
        )
        assertEquals("配了就用配的", fastId, settings.resolveModelId(fastId))
    }

    /** 配了但那个模型已经被删掉：不能返回失效 id，要回退。 */
    @Test
    fun `字段指向已删除的模型时回退到聊天模型`() {
        val chatId = Uuid.random()
        val settings = Settings(
            chatModelId = chatId,
            providers = listOf(provider(chatId)),
        )
        assertEquals("失效 id 应回退", chatId, settings.resolveModelId(Uuid.random()))
    }

    /** 聊天模型本身也没有：返回 null，让调用方决定怎么报错。 */
    @Test
    fun `聊天模型也不存在时返回 null`() {
        val settings = Settings(
            chatModelId = Uuid.random(),
            providers = listOf(provider(Uuid.random())),
        )
        assertNull(settings.resolveModelId(null))
    }

    @Test
    fun `默认设置下四个字段都是跟随`() {
        val s = Settings()
        assertEquals("快速模型默认跟随", null, s.fastModelId)
        assertEquals("翻译模型默认跟随", null, s.translateModeId)
        assertEquals("OCR 模型默认跟随", null, s.ocrModelId)
        assertEquals("记忆模型默认跟随", null, s.memoryModelId)
    }
}
