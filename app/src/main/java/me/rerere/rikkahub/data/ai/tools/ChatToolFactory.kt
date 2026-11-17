package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import me.rerere.rikkahub.data.ai.tools.local.evaluateJavascript
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.repository.memoryId
import me.rerere.rikkahub.data.skills.SkillManager
import me.rerere.rikkahub.service.BackgroundShellManager
import me.rerere.workspace.WorkspaceShellStatus
import java.io.File
import kotlin.uuid.Uuid

private const val TAG = "ChatToolFactory"
private const val MAX_TOOL_OUTPUT_CHARS = 32 * 1024
private const val TOOL_OUTPUT_PREVIEW_CHARS = 4 * 1024

// 搜索结果的体积由用户设置的结果数决定，且结果列表 UI 与引用跳转都依赖完整的 JSON 结构，不参与截断
private val TOOLS_WITHOUT_OUTPUT_TRUNCATION = setOf("search_web")

internal fun shouldUseExternalWebSearch(assistant: Assistant, model: Model): Boolean {
    return assistant.enableWebSearch && BuiltInTools.Search !in model.tools
}

class InvalidMcpServerNamesException(val names: List<String>) :
    IllegalStateException("Invalid MCP server names: ${names.joinToString(", ")}")

/** Creates the complete tool set for one generation run, including approval resumption. */
class ChatToolFactory(
    private val context: Context,
    private val memoryRepository: MemoryRepository,
    private val conversationRepository: ConversationRepository,
    private val localTools: LocalTools,
    private val mcpManager: McpManager,
    private val skillManager: SkillManager,
    private val workspaceRepository: WorkspaceRepository,
    private val backgroundShellManager: BackgroundShellManager,
) {
    suspend fun createTools(
        settings: Settings,
        assistant: Assistant,
        model: Model,
        conversationId: Uuid,
        workspaceCwd: String? = null,
        /** 是否允许查所有助手的会话；false 时只看当前助手 */
        crossAssistantContext: Boolean = false,
    ): List<Tool> {
        val workspaceTools =
            createWorkspaceToolsIfReady(assistant.workspaceId?.toString(), workspaceCwd, conversationId)
        val tools = buildList {
            if (assistant.enableMemory) {
                addAll(buildMemoryTools(memoryRepository, assistant.memoryId))
            }
            if (shouldUseExternalWebSearch(assistant, model)) {
                addAll(createSearchTools(settings))
            }
            addAll(localTools.getTools(assistant.localTools))
            // 跨对话上下文本身就需要这几个工具，不然「记得刚才聊过」只能靠开场摘要硬撑，
            // 用户追问细节时模型没法自己往回翻，所以开了它就一并注册。
            if (assistant.enableRecentChatsReference || crossAssistantContext) {
                // 开了跨对话时 assistantId 传 null：用户会在别的助手那里聊同一件事，
                // 只认当前助手就会出现「明明聊过却说不记得」。
                addAll(
                    createConversationTools(
                        conversationRepo = conversationRepository,
                        assistantId = assistant.id.takeUnless { crossAssistantContext },
                    )
                )
            }
            addAll(workspaceTools)
            // 内置技能始终注册：用户不用逐个勾选，模型每轮对话都能自主调用。
            // 用户自己写的技能仍然要靠 enabledSkills 显式启用，避免一堆没写好的技能挤占上下文。
            val allSkills = skillManager.listSkills()
            val effectiveSkills = assistant.enabledSkills +
                allSkills.filter { it.builtin }.map { it.name }.toSet()
            if (effectiveSkills.isNotEmpty()) {
                addAll(
                    createSkillTools(
                        enabledSkills = effectiveSkills,
                        allSkills = allSkills,
                    )
                )
                // 光能读 SKILL.md 不够：技能里的脚本要能真跑起来才算有用。
                // shell 优先走用户配好的工作区（有完整 Linux 环境），没有就退回系统 sh。
                skillRunnerTool(assistant, workspaceCwd, effectiveSkills, allSkills)?.let { add(it) }
                // 让模型能把对话里的流程直接存成技能，用户不用自己去写 markdown
                add(
                    createSkillWriterTool(skillManager) { created ->
                        Log.i(TAG, "create_skill: ${created.name}")
                    }
                )
            }

            val mcpTools = mcpManager.getAllAvailableTools(assistant)
            val invalidNames = mcpTools
                .map { it.second }
                .distinct()
                .filter { name -> name.isEmpty() || !name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' } }
            if (invalidNames.isNotEmpty()) {
                throw InvalidMcpServerNamesException(invalidNames)
            }
            mcpTools.forEach { (serverId, serverName, tool) ->
                add(
                    Tool(
                        name = "mcp__${serverName}__${tool.name}",
                        description = tool.description ?: "",
                        parameters = { tool.inputSchema },
                        needsApproval = { tool.needsApproval },
                        execute = { mcpManager.callTool(serverId, tool.name, it.jsonObject) },
                    )
                )
            }
        }
        // 过长的输出存成文件让模型用 shell 按需读取；没有 shell 时模型读不到文件，只能原样返回。
        // 文件放在本机, 只有本地 Rootfs 挂载了 /tool_outputs, 远程工作区同样读不到
        val canReadToolOutputs = workspaceTools.isNotEmpty() &&
            assistant.workspaceId?.let { workspaceRepository.getById(it.toString()) }?.capabilities()?.rootfs == true
        return if (canReadToolOutputs) tools.map(::withOutputTruncation) else tools
    }

    private fun withOutputTruncation(tool: Tool): Tool {
        if (tool.name in TOOLS_WITHOUT_OUTPUT_TRUNCATION) return tool
        val execute = tool.execute
        return tool.copy(execute = { truncateOutput(execute(it)) })
    }

    private fun truncateOutput(output: List<UIMessagePart>): List<UIMessagePart> {
        val textParts = output.filterIsInstance<UIMessagePart.Text>()
        val totalChars = textParts.sumOf { it.text.length }
        if (totalChars <= MAX_TOOL_OUTPUT_CHARS) return output

        val fileName = "${Uuid.random()}.txt"
        Log.i(TAG, "truncateOutput: saving $totalChars chars to $fileName")
        val fullText = textParts.joinToString("\n") { it.text }
        val outputDir = File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() }
        File(outputDir, fileName).writeText(fullText)

        return listOf(
            UIMessagePart.Text(
                buildString {
                    appendLine("[Tool output truncated: $totalChars characters total]")
                    appendLine("Full output saved to: /tool_outputs/$fileName")
                    appendLine("Use shell to read: `cat /tool_outputs/$fileName`")
                    appendLine("Use shell to search: `grep \"pattern\" /tool_outputs/$fileName`")
                    appendLine()
                    append(fullText.take(TOOL_OUTPUT_PREVIEW_CHARS))
                }
            )
        ) + output.filter { it !is UIMessagePart.Text }
    }

    /**
     * 构造技能脚本执行工具。
     *
     * 两条执行路径：
     * - JS：走内置 QuickJS，始终可用
     * - sh / py：有就绪的 workspace 就在它的环境里跑，否则退回系统 shell（py 会因此不可用）
     */
    private suspend fun skillRunnerTool(
        assistant: Assistant,
        cwd: String?,
        enabledSkills: Set<String>,
        allSkills: List<me.rerere.rikkahub.data.skills.SkillMetadata>,
    ): Tool? {
        val workspaceId = assistant.workspaceId?.toString()
        val workspace = workspaceId?.let { workspaceRepository.getById(it) }
        val shellReady = workspace?.shellStatus == WorkspaceShellStatus.READY.name

        return createSkillRunnerTool(
            enabledSkills = enabledSkills,
            allSkills = allSkills,
            runShell = { command, timeoutSec ->
                if (shellReady && workspaceId != null) {
                    val result = workspaceRepository.executeCommand(
                        id = workspaceId,
                        command = command,
                        cwd = cwd.orEmpty(),
                        timeoutMillis = timeoutSec * 1000L,
                    )
                    SkillExecResult(
                        exitCode = result.exitCode,
                        stdout = result.stdout,
                        stderr = result.stderr,
                        timedOut = result.timedOut,
                        truncated = result.truncated,
                    )
                } else {
                    // 没有工作区：用系统 shell。能跑 printf/grep/sed 这类内置命令，
                    // 但没有 python、没有包管理器，脚本里用到就失败，把原因如实带回去
                    runSystemShell(command, timeoutSec)
                }
            },
            runJavaScript = { code, scriptDir, args, _ ->
                // 给脚本套一层：注入 __dir/__args 和一组文件读写函数，
                // 否则 JS 脚本只能算数，读不到同目录的数据文件，能力太窄。
                // 读写都限制在技能目录内，防止脚本越界访问应用数据。
                val wrapped = buildString {
                    appendLine("(function(){")
                    appendLine("const __dir = ${jsString(scriptDir.absolutePath)};")
                    appendLine("const __args = ${args.joinToString(",", "[", "]") { jsString(it) }};")
                    appendLine(JS_SKILL_FS_PRELUDE)
                    appendLine("try {")
                    appendLine(code)
                    appendLine("} catch (e) { __rikkahubThrow(e); }")
                    appendLine("})();")
                }
                val access = object : me.rerere.rikkahub.data.ai.tools.local.SkillFileAccess {
                    // root 由 JS 侧传进来，但这里再按 canonicalPath 验一次，
                    // 防止脚本伪造根目录绕过前端的 .. 检查
                    private fun resolve(root: String, rel: String): java.io.File? {
                        val rootDir = java.io.File(root).canonicalFile
                        val target = java.io.File(rootDir, rel).canonicalFile
                        return if (target.path == rootDir.path || target.path.startsWith(rootDir.path + java.io.File.separator)) {
                            target
                        } else null
                    }

                    override fun read(root: String, relPath: String): String =
                        resolve(root, relPath)?.takeIf { it.isFile }?.readText()
                            ?: error("cannot read: $relPath")

                    override fun write(root: String, relPath: String, text: String): Boolean {
                        val target = resolve(root, relPath) ?: error("cannot write: $relPath")
                        target.parentFile?.mkdirs()
                        target.writeText(text)
                        return true
                    }

                    override fun exists(root: String, relPath: String): Boolean =
                        resolve(root, relPath)?.exists() == true

                    override fun list(root: String, relPath: String): String {
                        val dir = resolve(root, relPath) ?: return "[]"
                        return (dir.list() ?: emptyArray())
                            .sorted()
                            .joinToString(",", "[", "]") { jsString(it) }
                    }
                }
                runCatching { SkillExecResult(0, evaluateJavascript(wrapped, fileAccess = access), "") }
                    .getOrElse { e -> SkillExecResult(1, "", e.message ?: "javascript failed", error = e.message) }
            },
        )
    }

    /** 用 Android 自带的 sh 执行命令，超时后强杀。 */
    private suspend fun runSystemShell(command: String, timeoutSec: Int): SkillExecResult =
        withContext(Dispatchers.IO) {
            try {
                val process = ProcessBuilder("sh", "-c", command)
                    .redirectErrorStream(false)
                    .start()
                val finished = process.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    return@withContext SkillExecResult(
                        exitCode = -1, stdout = "", stderr = "", timedOut = true,
                        error = "Timed out after ${timeoutSec}s",
                    )
                }
                val out = process.inputStream.bufferedReader().readText()
                val err = process.errorStream.bufferedReader().readText()
                SkillExecResult(process.exitValue(), out, err)
            } catch (e: Exception) {
                SkillExecResult(-1, "", "", error = e.message ?: e::class.java.simpleName)
            }
        }

    private suspend fun createWorkspaceToolsIfReady(
        workspaceId: String?,
        cwd: String?,
        conversationId: Uuid,
    ): List<Tool> {
        if (workspaceId.isNullOrBlank()) return emptyList()
        val workspace = workspaceRepository.getById(workspaceId) ?: return emptyList()
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) {
            Log.d(
                TAG,
                "createWorkspaceToolsIfReady: skip workspace tools, workspace=$workspaceId, status=${workspace.shellStatus}"
            )
            return emptyList()
        }
        return createWorkspaceTools(workspaceId, workspaceRepository, cwd, conversationId, backgroundShellManager)
    }
}

/** 把字符串安全地嵌进 JS 源码。 */
private fun jsString(value: String): String = buildString {
    append('"')
    for (c in value) {
        when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
    }
    append('"')
}

/**
 * 注入到技能 JS 脚本前面的一组文件读写函数。
 *
 * 只用 Kotlin 侧已经暴露的 binding，不额外开 JNI 通道。所有路径都以脚本所在目录为根，
 * 禁止通过 `..` 跳出，避免技能脚本读到应用私有数据。
 */
private val JS_SKILL_FS_PRELUDE = """
const __fs = (() => {
  const norm = p => {
    const parts = [];
    for (const seg of String(p).split('/')) {
      if (seg === '' || seg === '.') continue;
      if (seg === '..') { if (parts.length) parts.pop(); continue; }
      parts.push(seg);
    }
    return parts.join('/');
  };
  const safe = p => {
    const rel = norm(p);
    if (rel.startsWith('..')) throw new Error('path escapes skill directory: ' + p);
    return rel;
  };
  return {
    readText: p => __rikkahubReadFile(__dir, safe(p)),
    writeText: (p, t) => __rikkahubWriteFile(__dir, safe(p), String(t)),
    exists: p => __rikkahubExists(__dir, safe(p)),
    list: p => __rikkahubList(__dir, safe(p || '.')),
  };
})();
const fs = __fs;
const readFile = p => __fs.readText(p);
const writeFile = (p, t) => __fs.writeText(p, t);
""".trimIndent()
