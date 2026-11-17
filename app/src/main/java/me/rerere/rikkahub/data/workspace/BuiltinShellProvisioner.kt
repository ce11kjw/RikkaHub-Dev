package me.rerere.rikkahub.data.workspace

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.db.dao.WorkspaceDAO
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceShellStatus

private const val TAG = "BuiltinShellProvisioner"

/** 自动创建的工作区名字，用户能在工作区列表里看到并管理它。 */
const val BUILTIN_WORKSPACE_NAME = "内置运行环境"

/**
 * 为技能脚本自动准备一套 Linux 环境。
 *
 * 技能里的脚本多数是 python / node 写的，而 Android 系统只有 sh。以前要用户
 * 自己去工作区里建环境、下 rootfs —— 绝大多数人不会做，于是技能脚本永远跑不起来。
 *
 * 这里在 App 启动时检查一次：没有可用的本地环境就自动建一套并装好 Ubuntu base。
 * 装完之后 python3 / node 都能用，不再受系统限制。
 *
 * 全程在后台，失败不影响 App —— 脚本会退回系统 sh，功能受限但不会崩。
 */
class BuiltinShellProvisioner(
    private val workspaceRepository: WorkspaceRepository,
    private val workspaceDAO: WorkspaceDAO,
    private val bootstrap: WorkspaceBootstrap,
) {
    /**
     * 确保存在一个可用的本地工作区。
     *
     * @return 可用工作区的 id；已有或刚装好时返回它，失败返回 null
     */
    suspend fun ensureReady(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val all = workspaceDAO.getAll().filter { !it.isSsh() }

            // 已经有 READY 的直接用，不重复下载几百兆的 rootfs
            all.firstOrNull { it.shellStatus == WorkspaceShellStatus.READY.name }?.let {
                Log.i(TAG, "已有可用环境: ${it.id}")
                // 顺手补装工具：上次可能只装完 rootfs 就被打断了
                bootstrap.bootstrap(it.id)
                return@runCatching it.id
            }

            // 正在装就别重复触发，否则会并发下载同一个包
            if (all.any { it.shellStatus == WorkspaceShellStatus.INSTALLING.name }) {
                Log.i(TAG, "环境正在安装中，跳过")
                return@runCatching null
            }

            // 优先复用已有的本地工作区，没有才新建
            val target = all.firstOrNull() ?: workspaceRepository.create(BUILTIN_WORKSPACE_NAME)

            Log.i(TAG, "开始安装 rootfs 到 ${target.id}")
            if (workspaceRepository.installRootfs(target.id, DEFAULT_ROOTFS_URL)) {
                Log.i(TAG, "环境安装完成: ${target.id}，开始装常用工具")
                // 装 node/python 这些技能脚本要用的工具。
                // 失败也不影响 —— 脚本会退回系统 sh，只是功能受限
                bootstrap.bootstrap(target.id)
                target.id
            } else {
                Log.w(TAG, "环境安装失败，技能脚本将退回系统 sh")
                null
            }
        }.onFailure {
            Log.w(TAG, "准备内置环境失败", it)
        }.getOrNull()
    }

    /** 已就绪的环境 id，供调用方决定要不要走 Linux 路径。 */
    suspend fun readyWorkspaceId(): String? = withContext(Dispatchers.IO) {
        runCatching {
            workspaceDAO.getAll()
                .firstOrNull {
                    !it.isSsh() && it.shellStatus == WorkspaceShellStatus.READY.name
                }?.id
        }.getOrNull()
    }

    companion object {
        /** 与工作区详情页的默认值一致：Ubuntu 24.04 base arm64。 */
        const val DEFAULT_ROOTFS_URL =
            "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-arm64.tar.gz"
    }
}
