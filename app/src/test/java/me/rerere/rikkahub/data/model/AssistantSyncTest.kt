package me.rerere.rikkahub.data.model

import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 助手全局同步的核心规则。
 *
 * 两条底线：
 * 1. 行为配置（模型、提示词、工具）必须同步过去
 * 2. 身份和外观（名字、头像、背景）绝对不能同步，否则所有助手长得一样
 */
class AssistantSyncTest {

    private fun assistant(name: String) = Assistant(id = Uuid.random(), name = name)

    @Test
    fun `模型改动会同步给其他助手`() {
        val a = assistant("A")
        val b = assistant("B")
        val modelId = Uuid.random()

        val changedA = a.copy(chatModelId = modelId)
        val result = AssistantSync.broadcast(changedA, listOf(changedA, b))

        val syncedB = result.first { it.id == b.id }
        assertEquals("B 应该用上 A 的模型", modelId, syncedB.chatModelId)
    }

    @Test
    fun `提示词和工具体同步`() {
        val a = assistant("A").copy(
            systemPrompt = "新提示词",
            localTools = listOf(LocalToolOption.Clipboard, LocalToolOption.Calendar),
            enableWebSearch = true,
        )
        val b = assistant("B")

        val result = AssistantSync.broadcast(a, listOf(a, b))
        val syncedB = result.first { it.id == b.id }

        assertEquals("新提示词", syncedB.systemPrompt)
        assertEquals(listOf(LocalToolOption.Clipboard, LocalToolOption.Calendar), syncedB.localTools)
        assertTrue(syncedB.enableWebSearch)
    }

    /** 最关键的一条：名字/头像/背景不能被冲掉。 */
    @Test
    fun `名字和背景不会被同步覆盖`() {
        val a = assistant("助手A").copy(background = "a.png", useGradientBackground = true)
        val b = assistant("助手B").copy(background = "b.png", useGradientBackground = false)

        val result = AssistantSync.broadcast(a, listOf(a, b))
        val syncedB = result.first { it.id == b.id }

        assertEquals("B 的名字必须保留", "助手B", syncedB.name)
        assertEquals("B 的背景必须保留", "b.png", syncedB.background)
        assertEquals("B 的渐变设置必须保留", false, syncedB.useGradientBackground)
        assertNotEquals("两个助手 id 不能相同", a.id, syncedB.id)
    }

    @Test
    fun `发现哪个助手被改了`() {
        val a = assistant("A")
        val b = assistant("B")
        val before = listOf(a, b)
        val after = listOf(a.copy(systemPrompt = "改了"), b)

        val changed = AssistantSync.findChanged(before, after)
        assertNotNull("应该发现 A 变了", changed)
        assertEquals(a.id, changed!!.id)
    }

    /** 改名字不算配置变更，不该触发同步 —— 否则改个名字会把所有配置冲掉。 */
    @Test
    fun `只改名字不触发同步`() {
        val a = assistant("A")
        val before = listOf(a)
        val after = listOf(a.copy(name = "A 改名了"))

        assertNull("改名不该触发同步", AssistantSync.findChanged(before, after))
    }

    /** 新增或删除助手时列表长度变了，不该触发同步。 */
    @Test
    fun `增减助手不触发同步`() {
        val a = assistant("A")
        val b = assistant("B")

        assertNull("新增助手", AssistantSync.findChanged(listOf(a), listOf(a, b)))
        assertNull("删除助手", AssistantSync.findChanged(listOf(a, b), listOf(a)))
    }

    @Test
    fun `同步后列表长度和顺序不变`() {
        val a = assistant("A")
        val b = assistant("B")
        val c = assistant("C")
        val changed = a.copy(chatModelId = Uuid.random())

        val result = AssistantSync.broadcast(changed, listOf(changed, b, c))

        assertEquals(3, result.size)
        assertEquals(listOf(changed.id, b.id, c.id), result.map { it.id })
    }

    /** 记忆统一靠 useGlobalMemory，同步它就等于同步了记忆库。 */
    @Test
    fun `全局记忆开关会同步`() {
        val a = assistant("A").copy(useGlobalMemory = true, enableMemory = true)
        val b = assistant("B").copy(useGlobalMemory = false)

        val result = AssistantSync.broadcast(a, listOf(a, b))
        val syncedB = result.first { it.id == b.id }

        assertTrue("B 也应开启全局记忆", syncedB.useGlobalMemory)
        assertTrue(syncedB.enableMemory)
    }

    @Test
    fun `预设消息和快捷消息会同步`() {
        val a = assistant("A").copy(
            presetMessages = listOf(me.rerere.ai.ui.UIMessage.user("预设")),
            quickMessageIds = setOf(Uuid.random()),
        )
        val b = assistant("B")

        val result = AssistantSync.broadcast(a, listOf(a, b))
        val syncedB = result.first { it.id == b.id }

        assertEquals(1, syncedB.presetMessages.size)
        assertEquals(a.quickMessageIds, syncedB.quickMessageIds)
    }
}
