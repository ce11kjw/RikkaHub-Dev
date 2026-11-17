package me.rerere.rikkahub.data.sync

import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.QuickMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class SettingsMergeTest {
    private val shared = Uuid.random()

    @Test fun addsOnlyMissingEntries() {
        val current = Settings(quickMessages = listOf(QuickMessage(id = shared, title = "本地")))
        val backup = Settings(
            quickMessages = listOf(
                QuickMessage(id = shared, title = "备份里的同名条目"),
                QuickMessage(id = Uuid.random(), title = "只存在于备份"),
            )
        )
        val (merged, added) = SettingsMerge.merge(current, backup)

        assertEquals(1, added)
        assertEquals(listOf("本地", "只存在于备份"), merged.quickMessages.map { it.title })
    }

    /** 同 id 的条目必须保留当前版本，不能被备份里的旧内容覆盖。 */
    @Test fun keepsCurrentVersionOfSameId() {
        val current = Settings(lorebooks = listOf(Lorebook(id = shared, name = "现在的世界书")))
        val backup = Settings(lorebooks = listOf(Lorebook(id = shared, name = "很久以前的世界书")))

        val (merged, added) = SettingsMerge.merge(current, backup)

        assertEquals(0, added)
        assertEquals(listOf("现在的世界书"), merged.lorebooks.map { it.name })
    }

    @Test fun emptyBackupChangesNothing() {
        val current = Settings(quickMessages = listOf(QuickMessage(title = "keep")))
        val (merged, added) = SettingsMerge.merge(current, Settings())

        assertEquals(0, added)
        assertEquals(current, merged)
    }

    /** 标量设置（主题、默认模型等）一律以当前为准，备份里的旧值不该盖上来。 */
    @Test fun scalarSettingsStayUnchanged() {
        val current = Settings(dynamicColor = true, translateTargetLanguage = "zh-CN")
        val backup = Settings(dynamicColor = false, translateTargetLanguage = "en-US")

        val (merged, _) = SettingsMerge.merge(current, backup)

        assertTrue(merged.dynamicColor)
        assertEquals("zh-CN", merged.translateTargetLanguage)
    }
}
