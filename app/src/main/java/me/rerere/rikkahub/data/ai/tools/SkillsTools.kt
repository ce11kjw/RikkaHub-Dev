package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.skills.SkillFrontmatterParser
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.putJsonObject
import me.rerere.rikkahub.data.skills.SkillManager
import me.rerere.rikkahub.data.skills.SkillMetadata
import me.rerere.rikkahub.data.skills.SkillPaths

// 与 Agent Skills 规范的 description 上限一致
private const val MAX_SKILL_DESCRIPTION_LENGTH = 1024

fun createSkillTools(
    enabledSkills: Set<String>,
    allSkills: List<SkillMetadata>,
): List<Tool> {
    val available = allSkills.filter { it.name in enabledSkills }
    if (available.isEmpty()) return emptyList()

    return listOf(
        Tool(
            name = "use_skill",
            description = """
                Load and apply a skill to get specialized instructions or capabilities.
                Call this tool when the user's request matches one of the available skills.
            """.trimIndent(),
            systemPrompt = { _, _ ->
                buildString {
                    appendLine("**Skills**")
                    appendLine(
                        """
                        Each skill below is a procedure written by someone who has done this task before. Loading one
                        costs one tool call and gives you steps, thresholds and pitfalls that general knowledge
                        does not have. Leaving it unused while you improvise is the main way answers get worse.

                        Before you start answering or writing code, scan the list and ask: does one of these describe
                        the situation I am in? Judge by the situation, not by shared words - a request to "make this
                        report shorter" matches a writing skill even if it never says "writing".

                        If one applies, call `use_skill` with its name as your first action, then follow what it says.
                        Do not answer first and load later: the answer written before the skill is usually wasted.

                        When several apply, load the most specific one. Load a second only if the first genuinely
                        does not cover what you are doing - two overlapping procedures pull in different directions.

                        If none apply, say nothing about skills and just answer. Do not mention this list to the user,
                        and do not load a skill to look thorough.
                        """.trimIndent()
                    )
                    appendLine("<available_skills>")
                    available.forEach { skill ->
                        appendLine("  <skill>")
                        // 技能可能来自第三方导入，转义并限长，防止 name/description 闭合标签注入任意系统提示
                        appendLine("    <name>${skill.name.escapeXml()}</name>")
                        appendLine("    <description>${skill.description.take(MAX_SKILL_DESCRIPTION_LENGTH).escapeXml()}</description>")
                        appendLine("  </skill>")
                    }
                    append("</available_skills>")
                    appendLine()
                }
            },
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("name", buildJsonObject {
                            put("type", "string")
                            put("description", "The name of the skill to use")
                        })
                        put("path", buildJsonObject {
                            put("type", "string")
                            put(
                                "description",
                                "Optional relative path to a file inside the skill directory. Omit to read the default SKILL.md instructions. Only use paths extracted from Markdown links in the SKILL.md content. Do NOT guess or infer paths."
                            )
                        })
                    },
                    required = listOf("name")
                )
            },
            execute = {
                val name = it.jsonObject["name"]?.jsonPrimitive?.content
                    ?: error("name is required")
                // 模型可能照抄系统提示中转义后的名称，两种形式都接受
                val skill = available.firstOrNull { skill -> skill.name == name || skill.name.escapeXml() == name }
                    ?: error("Skill '$name' is not available. Available skills: ${available.joinToString { it.name }}")
                val path = it.jsonObject["path"]?.jsonPrimitive?.content
                val content = if (path.isNullOrBlank()) {
                    require(skill.skillFile.exists()) { "Skill '$name' not found" }
                    SkillFrontmatterParser.extractBody(skill.skillFile.readText())
                } else {
                    val target = SkillPaths.resolveSkillFile(skill.skillDir, path)
                        ?: error("Path '$path' is outside the skill directory")
                    require(target.exists()) { "File '$path' not found in skill '$name'" }
                    target.readText()
                }
                listOf(UIMessagePart.Text(content))
            }
        )
    )
}

private fun String.escapeXml(): String = buildString(length) {
    for (c in this@escapeXml) {
        when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            else -> append(c)
        }
    }
}

/**
 * 让模型在对话里直接创建一个技能。
 *
 * 这是「可自己写」的另一半：用户说「把刚才这套流程存成技能」时，
 * 模型不需要用户去文件管理器敲 markdown，直接落盘成 /skills/<name>/SKILL.md。
 *
 * 只创建用户技能，不能覆盖内置技能（内置目录只读）；同名用户技能会被覆盖，
 * 因此这里在 overwrite=false 时会拒绝，让模型先问用户。
 */
internal fun createSkillWriterTool(
    skillManager: SkillManager,
    onCreated: suspend (SkillMetadata) -> Unit,
): Tool = Tool(
    name = "create_skill",
    description = """
        Create a new reusable skill from the current conversation.
        Use it when the user asks to turn a workflow, prompt, or set of steps into a skill,
        or says "remember this as a procedure".
        Writes /skills/<name>/SKILL.md. Fails if a user skill with that name already exists
        unless overwrite is true. Built-in skills cannot be modified.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putJsonObject("name") {
                    put("type", "string")
                    put("description", "Skill name: lowercase letters, digits, hyphens; must match the folder name")
                }
                putJsonObject("description") {
                    put("type", "string")
                    put(
                        "description",
                        "When this skill should trigger. State what it does and the situations that call for it; " +
                            "this text is all the model sees before loading the skill"
                    )
                }
                putJsonObject("instructions") {
                    put("type", "string")
                    put("description", "Markdown body: the actual procedure, checks, and pitfalls")
                }
                putJsonObject("overwrite") {
                    put("type", "boolean")
                    put("description", "Replace an existing user skill with the same name. Defaults to false")
                }
            },
            required = listOf("name", "description", "instructions")
        )
    },
    // 会写文件，让用户先确认
    needsApproval = { true },
    execute = { args ->
        val obj = args.jsonObject
        val rawName = obj["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val desc = obj["description"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val body = obj["instructions"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val overwrite = obj["overwrite"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false

        // 名字要能直接当目录名用：限制字符集，挡住 .. 和路径分隔符
        require(rawName.matches(Regex("[a-z0-9][a-z0-9-]{0,62}"))) {
            "name must be lowercase letters, digits or hyphens, start with a letter or digit, and be 1-63 chars"
        }
        require(desc.isNotBlank()) { "description is required" }
        require(body.isNotBlank()) { "instructions is required" }

        val existing = skillManager.findSkill(rawName)
        if (existing != null && existing.builtin) {
            error("'$rawName' is a built-in skill and cannot be modified; pick another name")
        }
        if (existing != null && !overwrite) {
            error("A user skill named '$rawName' already exists; pass overwrite=true to replace it")
        }

        val content = buildString {
            appendLine("---")
            appendLine("name: $rawName")
            // description 里有换行会破坏 frontmatter，压成一行
            appendLine("description: ${desc.replace(Regex("\\s+"), " ")}")
            appendLine("---")
            appendLine()
            append(body.trim())
        }

        val saved = skillManager.saveSkill(rawName, content)
            ?: error("Failed to write skill '$rawName'")

        onCreated(saved)
        listOf(UIMessagePart.Text(buildJsonObject {
            put("ok", true)
            put("name", saved.name)
            put("path", "skills/${saved.name}/SKILL.md")
            put(
                "note",
                "Created but not enabled. The user must enable it for their assistant " +
                    "(extensions panel → Skills) before it can trigger."
            )
        }.toString()))
    }
)
