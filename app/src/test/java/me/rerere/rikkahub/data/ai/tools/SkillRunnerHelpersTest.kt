package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 技能脚本运行器的辅助逻辑测试。
 *
 * 真正执行脚本需要设备环境（见 androidTest），这里验证「哪些脚本会被发现」和
 * 「参数怎么安全地拼进命令」，这两处出错会导致脚本根本跑不起来或命令被注入。
 */
class SkillRunnerHelpersTest {
    @get:Rule val temp = TemporaryFolder()

    private fun skillDir(vararg files: String): File {
        val dir = temp.newFolder("skill")
        files.forEach { relative ->
            File(dir, relative).apply {
                parentFile?.mkdirs()
                writeText("// $relative")
            }
        }
        return dir
    }

    @Test fun findsSupportedScriptsOnly() {
        val dir = skillDir("SKILL.md", "run.sh", "tool.js", "analyze.py", "notes.txt", "icon.png")
        val scripts = listSkillScripts(dir)

        assertEquals(listOf("analyze.py", "run.sh", "tool.js"), scripts)
    }

    /** 常见布局是 scripts/ 子目录，也要能发现。 */
    @Test fun findsScriptsInSubdirectories() {
        val dir = skillDir("SKILL.md", "scripts/fetch.sh", "scripts/lib/helper.js")
        val scripts = listSkillScripts(dir)

        assertTrue("应发现子目录脚本", scripts.contains("scripts/fetch.sh"))
        assertTrue("应发现更深一层的脚本", scripts.contains("scripts/lib/helper.js"))
    }

    @Test fun returnsEmptyWhenDirectoryIsMissing() {
        val missing = File(temp.root, "nope")
        assertTrue(listSkillScripts(missing).isEmpty())
    }

    /** 参数必须被单引号包住，否则带空格或分号的参数会破坏命令。 */
    @Test fun quotesArgumentsSafely() {
        assertEquals("'hello'", shellQuote("hello"))
        assertEquals("'a b'", shellQuote("a b"))
        // 内部的单引号按 POSIX 规则转义，不能直接闭合
        assertEquals("'it'\\''s'", shellQuote("it's"))
        assertEquals("'rm -rf /; echo x'", shellQuote("rm -rf /; echo x"))
    }

    /** 转义后的参数拼进命令时不能再被 shell 解释成多个命令。 */
    @Test fun injectedArgumentsStayInsideQuotes() {
        val malicious = "x; rm -rf /"
        val command = "sh /tmp/run.sh ${shellQuote(malicious)}"
        // 整段恶意内容应完整落在单引号内部
        assertTrue(command.contains("'x; rm -rf /'"))
    }
}
