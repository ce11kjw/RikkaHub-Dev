package me.rerere.rikkahub.data.ai.habit

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.skills.SkillManager
import java.io.File

private const val TAG = "HabitSkillWriter"

/**
 * 把分析结果写成用户技能 `my-habits`。
 *
 * 写到**用户技能目录**（不是内置目录）：这份技能归用户所有，可以随时手改，
 * 也不会被 App 更新覆盖掉。
 *
 * 覆盖策略：自动分析的部分每次重写，但 `## 我的补充` 以下的内容原样保留 ——
 * 用户手写的东西不能被自动更新冲掉。
 */
class HabitSkillWriter(private val skillManager: SkillManager) {
    companion object {
        const val SKILL_NAME = "my-habits"
        /** 用户手写内容的起始标记。 */
        private const val USER_SECTION = "## 我的补充"
    }

    /**
     * 写入分析结果。
     *
     * @param body 模型产出的 Markdown 正文
     * @return 是否写入成功
     */
    suspend fun write(body: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val dir = skillManager.getSkillsDir().resolve(SKILL_NAME)
            if (!dir.exists()) dir.mkdirs()

            val file = dir.resolve("SKILL.md")
            val preserved = readUserSection(file)

            val content = buildString {
                appendLine("---")
                appendLine("name: $SKILL_NAME")
                appendLine("description: >-")
                appendLine("  Auto-generated summary of this user's collaboration habits, refreshed periodically")
                appendLine("  from his own messages. Use for any development or operations task in this workspace.")
                appendLine("  根据这位用户的真实发言自动归纳的协作习惯，会定期刷新。适用于本工作区所有开发和运维任务。")
                appendLine("---")
                appendLine()
                appendLine("<!-- 本文件由「习惯分析」自动生成，前半部分每次分析都会重写。")
                appendLine("     想加自己的规则，写在下面的「我的补充」里，那部分不会被覆盖。 -->")
                appendLine()
                appendLine(body.trim())
                appendLine()
                appendLine()
                appendLine(USER_SECTION)
                appendLine()
                if (preserved.isNullOrBlank()) {
                    appendLine("<!-- 在这里写你自己的规则，自动分析不会覆盖这一段 -->")
                } else {
                    append(preserved.trimEnd())
                    appendLine()
                }
            }

            file.writeText(content)
            Log.i(TAG, "写入 $SKILL_NAME，正文 ${body.length} 字")
            true
        }.onFailure {
            Log.w(TAG, "write failed", it)
        }.getOrDefault(false)
    }

    /** 读出旧文件里「我的补充」之后的内容，用于保留。 */
    private fun readUserSection(file: File): String? {
        if (!file.isFile) return null
        return runCatching {
            val text = file.readText()
            val idx = text.indexOf(USER_SECTION)
            if (idx < 0) null else text.substring(idx + USER_SECTION.length)
        }.getOrNull()
    }

    /** 技能是否已经存在（决定要不要提示用户去启用）。 */
    fun exists(): Boolean = skillManager.getSkillsDir().resolve(SKILL_NAME).resolve("SKILL.md").isFile
}
