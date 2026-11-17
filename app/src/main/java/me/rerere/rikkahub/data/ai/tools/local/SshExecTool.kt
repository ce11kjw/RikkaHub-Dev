package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.ByteArrayOutputStream

/** 单次命令输出回给模型的上限，避免 cat 一个大文件把上下文撑爆。 */
private const val MAX_OUTPUT_CHARS = 16_000

/**
 * SSH 远程执行。
 *
 * **服务器信息全部作为调用参数传入，不在任何地方保存**：
 * 每台机器连哪、用什么账号密码，都由模型在调用时给出，执行完立刻断开，
 * App 里不会留下服务器清单或凭据。
 *
 * 默认要求用户确认：执行远程命令是破坏性操作，除非用户明确把这工具设为免确认。
 */
internal fun buildSshExecTool(): Tool = Tool(
    name = "ssh_exec",
    description = """
        Execute a command on a remote server over SSH and return its output.
        Provide host, port, username, password and command for every call; nothing is stored.
        Returns exit status, stdout and stderr.
        Output longer than $MAX_OUTPUT_CHARS characters is truncated.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putJsonObject("host") {
                    put("type", "string")
                    put("description", "Server hostname or IP address")
                }
                putJsonObject("port") {
                    put("type", "integer")
                    put("description", "SSH port, defaults to 22")
                }
                putJsonObject("username") {
                    put("type", "string")
                    put("description", "SSH username")
                }
                putJsonObject("password") {
                    put("type", "string")
                    put("description", "SSH password")
                }
                putJsonObject("command") {
                    put("type", "string")
                    put("description", "Shell command to run")
                }
                putJsonObject("timeout") {
                    put("type", "integer")
                    put("description", "Command timeout in seconds, defaults to 60")
                }
            },
            required = listOf("host", "username", "password", "command")
        )
    },
    // 远程执行命令可能改动机器状态，让用户先看一眼
    needsApproval = { true },
    execute = { args ->
        val obj = args.jsonObject
        val host = obj["host"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val username = obj["username"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val password = obj["password"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val command = obj["command"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val port = obj["port"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 22
        val timeoutSec = obj["timeout"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 60

        require(host.isNotBlank()) { "host is required" }
        require(username.isNotBlank()) { "username is required" }
        require(command.isNotBlank()) { "command is required" }

        val result = withContext(Dispatchers.IO) {
            var session: Session? = null
            var channel: ChannelExec? = null
            try {
                val jsch = JSch()
                session = jsch.getSession(username, host, port)
                session.setPassword(password)
                // 手机上没有 known_hosts，这里不校验主机指纹；凭据本身不落盘
                session.setConfig("StrictHostKeyChecking", "no")
                session.timeout = timeoutSec * 1000
                session.connect(timeoutSec * 1000)

                channel = session.openChannel("exec") as ChannelExec
                channel.setCommand(command)
                val stdout = ByteArrayOutputStream()
                val stderr = ByteArrayOutputStream()
                channel.setOutputStream(stdout)
                channel.setErrStream(stderr)
                channel.connect()

                // 轮询等待：既能响应超时，也不会像 wait() 一样卡死整个线程
                val deadline = System.currentTimeMillis() + timeoutSec * 1000L
                while (!channel.isClosed) {
                    if (System.currentTimeMillis() > deadline) {
                        throw IllegalStateException("Command timed out after ${timeoutSec}s")
                    }
                    Thread.sleep(50)
                }

                val outText = stdout.toString(Charsets.UTF_8.name())
                val errText = stderr.toString(Charsets.UTF_8.name())
                buildJsonObject {
                    put("host", host)
                    put("exit_code", channel.exitStatus)
                    put("stdout", outText.take(MAX_OUTPUT_CHARS))
                    put("stderr", errText.take(MAX_OUTPUT_CHARS))
                    if (outText.length > MAX_OUTPUT_CHARS || errText.length > MAX_OUTPUT_CHARS) {
                        put("truncated", true)
                    }
                }
            } catch (e: Exception) {
                // 把失败原因带回去，让模型能据此调整（比如换端口、检查密码）
                buildJsonObject {
                    put("host", host)
                    put("error", e.message ?: e::class.java.simpleName)
                }
            } finally {
                runCatching { channel?.disconnect() }
                runCatching { session?.disconnect() }
            }
        }
        listOf(UIMessagePart.Text(result.toString()))
    }
)
