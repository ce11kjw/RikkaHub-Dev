package me.rerere.rikkahub.data.ai.tools

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.ai.tools.local.evaluateJavascript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 技能脚本「真的能跑」的真机验证。
 *
 * 这是这套功能的核心价值：技能文档写得再好，脚本跑不起来就只是说明书。
 */
@RunWith(AndroidJUnit4::class)
class SkillRunnerExecutionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun runShell(command: String, timeoutSec: Int): SkillExecResult = runBlocking {
        val process = ProcessBuilder("sh", "-c", command).start()
        val finished = process.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return@runBlocking SkillExecResult(-1, "", "", timedOut = true)
        }
        SkillExecResult(
            exitCode = process.exitValue(),
            stdout = process.inputStream.bufferedReader().readText(),
            stderr = process.errorStream.bufferedReader().readText(),
        )
    }

    @Test fun runsShellScriptFromSkillDirectory() {
        val dir = File(context.cacheDir, "skill-test-${System.nanoTime()}").apply { mkdirs() }
        try {
            val script = File(dir, "hello.sh").apply {
                writeText("#!/bin/sh\necho \"hello from skill: \$1\"\n")
            }
            val result = runShell("sh ${script.absolutePath} world", 10)

            assertEquals("脚本应正常退出", 0, result.exitCode)
            assertTrue("应拿到脚本输出", result.stdout.contains("hello from skill: world"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun capturesNonZeroExitAndStderr() {
        val dir = File(context.cacheDir, "skill-fail-${System.nanoTime()}").apply { mkdirs() }
        try {
            val script = File(dir, "fail.sh").apply {
                writeText("#!/bin/sh\necho 'boom' >&2\nexit 3\n")
            }
            val result = runShell("sh ${script.absolutePath}", 10)

            assertEquals("退出码要如实带回", 3, result.exitCode)
            assertTrue("stderr 不能丢", result.stderr.contains("boom"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun runsJavaScriptSkill() {
        val output = runBlocking { evaluateJavascript("1 + 2 * 3") }
        assertTrue("JS 引擎应可用，实际输出: $output", output.contains("7"))
    }

    /** 超时必须能中止，否则一个死循环脚本会把整轮对话卡住。 */
    @Test fun timesOutInsteadOfHanging() {
        val result = runShell("sleep 5", 1)
        assertTrue("应标记超时", result.timedOut)
    }
}
