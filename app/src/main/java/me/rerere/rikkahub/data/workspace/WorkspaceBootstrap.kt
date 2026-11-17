package me.rerere.rikkahub.data.workspace

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.repository.WorkspaceRepository

private const val TAG = "WorkspaceBootstrap"

/**
 * 给 workspace 装上一批常用工具。
 *
 * Ubuntu base 是最小系统，只有 apt 和 coreutils。技能里的 node/python 脚本
 * 要靠这些工具才能跑起来，但让用户自己去敲 apt install 不现实 ——
 * 多数人不知道要装什么，也不知道卡在哪。
 *
 * 这里在环境就绪后跑一次，失败不影响使用（脚本会退回系统 sh）。
 */
class WorkspaceBootstrap(private val workspaceRepository: WorkspaceRepository) {

    /**
     * 需要安装的软件包。
     *
     * 只装通用工具，不装特定用途的东西：
     * - nodejs/npm：modern-web-guidance 这类 CLI 工具要用
     * - python3：大量技能脚本是 python 写的
     * - curl/git/jq：常见脚本依赖
     * - unzip/xz-utils：解压用
     */
    private val packages = listOf(
        "nodejs", "npm", "python3", "curl", "git", "jq", "unzip", "xz-utils",
    )

    /** 标记装过的文件，避免每次启动重装。 */
    private val stampFile = ".rikka-bootstrap-done"

    /**
     * 在 [workspaceId] 里执行初始化。
     *
     * @param force 忽略已完成标记，强制重装
     * @return 是否成功
     */
    suspend fun bootstrap(workspaceId: String, force: Boolean = false): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!force && alreadyDone(workspaceId)) {
                    Log.i(TAG, "已初始化过，跳过")
                    return@runCatching true
                }

                // 先同步软件源索引，否则 install 会找不到包
                Log.i(TAG, "apt update")
                val update = run(workspaceId, "apt-get update -qq", timeoutSec = 300)
                if (update.exitCode != 0) {
                    Log.w(TAG, "apt update 失败: ${update.stderr.take(300)}")
                    return@runCatching false
                }

                Log.i(TAG, "安装 ${packages.joinToString(" ")}")
                // DEBIAN_FRONTEND 防止卡在交互式提问上
                val install = run(
                    workspaceId,
                    "DEBIAN_FRONTEND=noninteractive apt-get install -y -qq --no-install-recommends " +
                        packages.joinToString(" "),
                    timeoutSec = 900,
                )
                if (install.exitCode != 0) {
                    Log.w(TAG, "安装失败: ${install.stderr.take(500)}")
                    return@runCatching false
                }

                // 装完打个标记，下次不再跑
                run(workspaceId, "touch ~/$stampFile", timeoutSec = 10)
                Log.i(TAG, "初始化完成")
                true
            }.onFailure {
                Log.w(TAG, "bootstrap failed", it)
            }.getOrDefault(false)
        }

    /** 已经初始化过没有。 */
    private suspend fun alreadyDone(workspaceId: String): Boolean {
        val result = run(workspaceId, "test -f ~/$stampFile && echo yes || echo no", timeoutSec = 10)
        return result.stdout.contains("yes")
    }

    /** 检查某个工具在不在，供技能脚本探测环境。 */
    suspend fun hasTool(workspaceId: String, tool: String): Boolean {
        val result = run(workspaceId, "command -v $tool || echo __MISSING__", timeoutSec = 10)
        return !result.stdout.contains("__MISSING__")
    }

    private suspend fun run(workspaceId: String, command: String, timeoutSec: Int) =
        workspaceRepository.executeCommand(
            id = workspaceId,
            command = command,
            cwd = "",
            timeoutMillis = timeoutSec * 1000L,
        )
}
