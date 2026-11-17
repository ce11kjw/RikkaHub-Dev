package me.rerere.rikkahub.data.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 技能的创建规则。
 *
 * 用户手填的三段内容要拼成合法 SKILL.md，名字要能当目录名，description 里的换行
 * 必须压平 —— 这几处错了会导致技能保存成功但永远无法触发（frontmatter 解析不出来）。
 */
class SkillCreationTest {

    /** 与 SkillsPage.buildSkillMarkdown 同一套规则 */
    private fun build(name: String, description: String, body: String): String =
        buildString {
            appendLine("---")
            appendLine("name: ${name.trim()}")
            appendLine("description: ${description.trim().replace(Regex("\\s+"), " ")}")
            appendLine("---")
            appendLine()
            appendLine(body.trim())
        }

    private val validName = Regex("[a-z0-9][a-z0-9-]{0,62}")

    @Test
    fun `拼出来的内容能被解析器读回`() {
        val md = build("disk-cleanup", "查磁盘占用时使用", "# 排查\n\n先 df -h。")
        val frontmatter = SkillFrontmatterParser.parse(md)

        assertEquals("disk-cleanup", frontmatter["name"])
        assertEquals("查磁盘占用时使用", frontmatter["description"])
        val body = SkillFrontmatterParser.extractBody(md)
        assertTrue("正文要保留实际内容", body.contains("先 df -h"))
    }

    /** 多行 description 会破坏 frontmatter，必须压成一行 */
    @Test
    fun `description 里的换行会被压平`() {
        val md = build("test-skill", "第一行\n第二行\n\n第三行", "body")
        val frontmatter = SkillFrontmatterParser.parse(md)
        val desc = frontmatter["description"]

        assertNotNull(desc)
        assertFalse("不能残留换行", desc!!.contains("\n"))
        assertEquals("第一行 第二行 第三行", desc)
    }

    @Test
    fun `合法的名字通过校验`() {
        listOf("a", "disk-cleanup", "test123", "skill-2").forEach {
            assertTrue("$it 应合法", it.matches(validName))
        }
    }

    @Test
    fun `不合法的名字被拒绝`() {
        listOf(
            "",                     // 空
            "Disk-Cleanup",         // 大写
            "-leading",             // 减号开头
            "../escape",            // 路径穿越
            "has space",            // 空格
            "name_with_underscore", // 下划线
        ).forEach {
            assertFalse("'$it' 应被拒绝", it.matches(validName))
        }
    }

    @Test
    fun `超过 63 字符的名字被拒绝`() {
        val tooLong = "a".repeat(64)
        assertFalse(tooLong.matches(validName))
        assertTrue("a".repeat(63).matches(validName))
    }
}
