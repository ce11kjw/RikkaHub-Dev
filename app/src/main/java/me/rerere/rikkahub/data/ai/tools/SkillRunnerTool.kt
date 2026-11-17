package me.rerere.rikkahub.data.ai.tools

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.skills.SkillMetadata
import me.rerere.rikkahub.data.skills.SkillPaths
import java.io.File
import java.util.concurrent.TimeUnit

private const val TAG = "SkillRunnerTool"

/** 脚本输出回给模型的上限。 */
private const val MAX_OUTPUT_CHARS = 16_000

/** 默认超时，技能脚本通常很快，超过这个多半是挂住了。 */
private const val DEFAULT_TIMEOUT_SEC = 60

/**
 * 运行技能自带的脚本。
 *
 * 这是让技能「真正跑起来」的关键：`use_skill` 只能把 SKILL.md 和附属文件的内容读给模型看，
 * 技能里写的脚本模型根本执行不了，只能照抄命令让用户自己去终端敲。
 *
 * 支持三类脚本，按环境能力自动选择执行方式：
 * - `.js` / `.mjs`：始终可用（QuickJS 内置，零依赖）
 * - `.sh`：优先在用户配置的 proot 工作区里跑（有完整 Linux 命令），
 *           没有工作区时退回系统 shell（受限，但 printf/grep/sed 这类够用）
 * - `.py`：只在 proot 工作区里跑（Android 系统本身没有 python）
 *
 * 脚本必须位于启用技能的目录内，路径穿越会被 [SkillPaths.resolveSkillFile] 挡掉。
 */
internal fun createSkillRunnerTool(
    enabledSkills: Set<String>,
    allSkills: List<SkillMetadata>,
    runShell: suspend (command: String, timeoutSec: Int) -> SkillExecResult,
    runJavaScript: suspend (code: String, scriptDir: File, args: List<String>, timeoutSec: Int) -> SkillExecResult,
): Tool? {
    val available = allSkills.filter { it.name in enabledSkills }
    if (available.isEmpty()) return null

    return Tool(
        name = "run_skill_script",
        description = """
            Run a script file that belongs to an enabled skill and return its output.
            Use this instead of asking the user to run things manually.
            Supported: .js/.mjs (always), .sh (shell), .py (needs a Linux workspace).
            Omit path to list the scripts available in that skill.
        """.trimIndent().replace("\n", " "),
        systemPrompt = { _, _ ->
            buildString {
                appendLine("**Skill scripts**")
                appendLine("Skills may ship scripts. Run them with `run_skill_script` instead of pasting commands for the user:")
                appendLine("<skill_scripts>")
                available.forEach { skill ->
                    val scripts = listSkillScripts(skill.skillDir)
                    if (scripts.isNotEmpty()) {
                        appendLine("  <skill name=\"${skill.name}\">")
                        scripts.forEach { appendLine("    <script>$it</script>") }
                        appendLine("  </skill>")
                    }
                }
                append("</skill_scripts>")
            }
        },
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("skill") {
                        put("type", "string")
                        put("description", "Name of the skill that owns the script")
                    }
                    putJsonObject("path") {
                        put("type", "string")
                        put(
                            "description",
                            "Script path relative to the skill directory. Omit to list available scripts."
                        )
                    }
                    putJsonObject("args") {
                        put("type", "array")
                        put("description", "Optional command-line arguments passed to the script")
                        putJsonObject("items") { put("type", "string") }
                    }
                    putJsonObject("timeout") {
                        put("type", "integer")
                        put("description", "Timeout in seconds, defaults to $DEFAULT_TIMEOUT_SEC")
                    }
                },
                required = listOf("skill")
            )
        },
        // 脚本可能改动设备上的东西，先让用户看一眼再跑
        needsApproval = { true },
        execute = { args ->
            val obj = args.jsonObject
            val skillName = obj["skill"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val path = obj["path"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val scriptArgs = (obj["args"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
            val timeout = (obj["timeout"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                ?: DEFAULT_TIMEOUT_SEC).coerceIn(5, 600)

            val skill = available.firstOrNull { it.name == skillName }
                ?: error("Skill '$skillName' is not enabled. Available: ${available.joinToString { it.name }}")

            // 不带 path：列出这个技能里有哪些脚本可跑
            if (path.isBlank()) {
                val scripts = listSkillScripts(skill.skillDir)
                return@Tool listOf(
                    UIMessagePart.Text(buildJsonObject {
                        put("skill", skill.name)
                        putJsonArray("scripts") { scripts.forEach { add(it) } }
                        put("hint", "Pass one of these as `path` to run it")
                    }.toString())
                )
            }

            val file = SkillPaths.resolveSkillFile(skill.skillDir, path)
                ?: error("Path '$path' is outside skill '$skillName'")
            require(file.isFile) { "Script '$path' not found in skill '$skillName'" }

            val ext = file.extension.lowercase()
            require(ext in SUPPORTED_EXTENSIONS) {
                "Unsupported script type '.$ext'. Supported: ${SUPPORTED_EXTENSIONS.joinToString { ".$it" }}"
            }

            val quotedArgs = scriptArgs.joinToString(" ") { shellQuote(it) }
            val result = withContext(Dispatchers.IO) {
                when (ext) {
                    // JS 走内置 QuickJS：不依赖系统装了 node，永远可用
                    "js", "mjs", "cjs" -> runJavaScript(file.readText(), file.parentFile ?: skill.skillDir, scriptArgs, timeout)
                    // shell 系始终有 /system/bin/sh
                    "sh", "bash" -> runShell("sh ${file.absolutePath} $quotedArgs".trim(), timeout)
                    else -> {
                        val interpreter = INTERPRETERS[ext]
                            ?: return@withContext SkillExecResult(
                                exitCode = -1, stdout = "", stderr = "",
                                error = "No interpreter configured for '.$ext'",
                            )
                        // 先探测解释器在不在，不在就直接给出可操作的提示，
                        // 而不是让模型看到一句 "python3: not found" 后反复重试
                        val probe = runShell("command -v $interpreter || echo __MISSING__", 10)
                        if (probe.stdout.contains("__MISSING__")) {
                            return@withContext SkillExecResult(
                                exitCode = -1, stdout = "", stderr = "",
                                error = buildString {
                                    append("'.${ext}' 脚本需要 $interpreter，但这台设备上没有。\n")
                                    append("可选做法：\n")
                                    append("1) 把这个脚本改成 .js —— 内置 QuickJS 引擎始终可用，不需要安装任何东西；\n")
                                    append("2) 在 workspace 里建一个装有 $interpreter 的 Linux 环境，技能脚本会在那里执行；\n")
                                    append("3) 如果逻辑简单，直接用 shell 重写（/system/bin/sh 始终存在）。")
                                },
                            )
                        }
                        runShell("$interpreter ${file.absolutePath} $quotedArgs".trim(), timeout)
                    }
                }
            }

            Log.i(TAG, "run_skill_script: ${skill.name}/$path exit=${result.exitCode}")
            listOf(UIMessagePart.Text(buildJsonObject {
                put("skill", skill.name)
                put("path", path)
                put("exit_code", result.exitCode)
                put("stdout", result.stdout.take(MAX_OUTPUT_CHARS))
                put("stderr", result.stderr.take(MAX_OUTPUT_CHARS))
                if (result.timedOut) put("timed_out", true)
                if (result.truncated || result.stdout.length > MAX_OUTPUT_CHARS ||
                    result.stderr.length > MAX_OUTPUT_CHARS
                ) {
                    put("truncated", true)
                }
                result.error?.let { put("error", it) }
            }.toString()))
        }
    )
}

/** 脚本执行结果，统一 sh / js 两条路径的形态。 */
internal data class SkillExecResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
    val truncated: Boolean = false,
    val error: String? = null,
)

private val SUPPORTED_EXTENSIONS = listOf("js", "mjs", "cjs", "sh", "bash", "py", "pl", "rb", "lua")

/**
 * 非 JS / 非 shell 的脚本用什么解释器跑。
 *
 * 这些解释器 Android 系统默认都没有，只有 workspace 的 Linux 环境里可能有，
 * 所以执行前必须先探测；缺失时给出改造建议，而不是让模型反复撞墙。
 */
private val INTERPRETERS = mapOf(
    "py" to "python3",
    "pl" to "perl",
    "rb" to "ruby",
    "lua" to "lua",
)

/** 单引号包裹，内部的单引号按 shell 规则转义。 */
internal fun shellQuote(value: String): String =
    "'" + value.replace("'", "'\\''") + "'"

/**
 * 列出技能目录里的脚本，只返回相对路径，按名字排序。
 * 只扫两层（技能名/scripts/xxx.sh 这种常见布局），不递归太深避免卡住。
 */
internal fun listSkillScripts(skillDir: File): List<String> {
    if (!skillDir.isDirectory) return emptyList()
    return skillDir.walkTopDown()
        .maxDepth(3)
        .filter { it.isFile && it.extension.lowercase() in SUPPORTED_EXTENSIONS }
        .map { it.relativeTo(skillDir).invariantSeparatorsPath }
        .sorted()
        .take(50)
        .toList()
}
