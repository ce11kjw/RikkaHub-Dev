package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.os.Environment
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
import java.io.File

/** 单次读取上限，避免一个大文件把上下文撑爆。 */
private const val MAX_READ_CHARS = 100_000
/** 单次列目录上限。 */
private const val MAX_LIST_ENTRIES = 500

/**
 * 手机本地文件工具：列目录、读文件、写文件。
 *
 * 限制在「应用私有目录 + 共享下载目录 + 共享文档目录」三处，不开放整机遍历：
 * 模型拿不到系统分区，也碰不到别的应用的数据。写文件同样只在这三处内进行。
 */
internal fun buildFileAccessTools(context: Context): List<Tool> {
    val roots = listOf(
        context.filesDir to "app_files",
        context.cacheDir to "app_cache",
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) to "Downloads",
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS) to "Documents",
    )

    /**
     * 把用户给的相对路径解析成真实文件，并确保落在允许的根目录内。
     * 用 canonicalPath 比较，`../` 之类的穿越写法会被挡住。
     */
    fun resolve(path: String): File {
        val candidate = File(path)
        // 绝对路径：必须落在某个允许根目录下
        if (candidate.isAbsolute) {
            val target = candidate.canonicalFile
            val ok = roots.any { (dir, _) ->
                runCatching { target.path.startsWith(dir.canonicalFile.path) }.getOrDefault(false)
            }
            require(ok) { "Path is outside the allowed directories: $path" }
            return target
        }
        // 相对路径：默认相对下载目录
        val base = roots.firstOrNull { it.second == "Downloads" }?.first
            ?: roots.first().first
        val target = File(base, path).canonicalFile
        require(target.path.startsWith(base.canonicalFile.path)) {
            "Path escapes the allowed directory: $path"
        }
        return target
    }

    val listTool = Tool(
        name = "list_files",
        description = """
            List files and folders under an allowed directory on this phone.
            Allowed roots: app private files, app cache, shared Downloads and Documents.
            Omit the path to list Downloads.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Directory path; defaults to the shared Downloads folder")
                    }
                }
            )
        },
        needsApproval = { false },
        execute = { args ->
            val path = args.jsonObject["path"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val result = withContext(Dispatchers.IO) {
                val dir = if (path.isBlank()) {
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                } else {
                    resolve(path)
                }
                require(dir.isDirectory) { "Not a directory: ${dir.absolutePath}" }
                val entries = dir.listFiles().orEmpty()
                    .sortedBy { it.name.lowercase() }
                    .take(MAX_LIST_ENTRIES)
                buildJsonObject {
                    put("path", dir.absolutePath)
                    put("truncated", entries.size >= MAX_LIST_ENTRIES)
                    putJsonArray("entries") {
                        entries.forEach { f ->
                            add(
                                buildJsonObject {
                                    put("name", f.name)
                                    put("is_dir", f.isDirectory)
                                    if (f.isFile) put("size", f.length())
                                }
                            )
                        }
                    }
                }
            }
            listOf(UIMessagePart.Text(result.toString()))
        }
    )

    val readTool = Tool(
        name = "read_file",
        description = """
            Read a UTF-8 text file from this phone.
            Allowed roots: app private files, app cache, shared Downloads and Documents.
            Content longer than $MAX_READ_CHARS characters is truncated.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "File path, absolute or relative to Downloads")
                    }
                },
                required = listOf("path")
            )
        },
        needsApproval = { false },
        execute = { args ->
            val path = args.jsonObject["path"]?.jsonPrimitive?.contentOrNull.orEmpty()
            require(path.isNotBlank()) { "path is required" }
            val result = withContext(Dispatchers.IO) {
                val file = resolve(path)
                require(file.isFile) { "Not a file: ${file.absolutePath}" }
                val text = file.readText()
                buildJsonObject {
                    put("path", file.absolutePath)
                    put("size", file.length())
                    put("truncated", text.length > MAX_READ_CHARS)
                    put("content", text.take(MAX_READ_CHARS))
                }
            }
            listOf(UIMessagePart.Text(result.toString()))
        }
    )

    val writeTool = Tool(
        name = "write_file",
        description = """
            Write UTF-8 text to a file on this phone, creating parent folders as needed.
            Allowed roots: app private files, app cache, shared Downloads and Documents.
            Overwrites the file when it already exists.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "File path, absolute or relative to Downloads")
                    }
                    putJsonObject("content") {
                        put("type", "string")
                        put("description", "Text to write")
                    }
                    putJsonObject("append") {
                        put("type", "boolean")
                        put("description", "Append instead of overwrite, defaults to false")
                    }
                },
                required = listOf("path", "content")
            )
        },
        // 写文件会覆盖用户数据，让用户先确认
        needsApproval = { true },
        execute = { args ->
            val obj = args.jsonObject
            val path = obj["path"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val content = obj["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val append = obj["append"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
            require(path.isNotBlank()) { "path is required" }
            val result = withContext(Dispatchers.IO) {
                val file = resolve(path)
                file.parentFile?.mkdirs()
                if (append) file.appendText(content) else file.writeText(content)
                buildJsonObject {
                    put("path", file.absolutePath)
                    put("size", file.length())
                    put("appended", append)
                }
            }
            listOf(UIMessagePart.Text(result.toString()))
        }
    )

    return listOf(listTool, readTool, writeTool)
}
