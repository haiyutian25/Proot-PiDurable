package com.lhzkml.spike.core.data.runtime

import com.lhzkml.spike.core.domain.model.Distro
import com.lhzkml.spike.core.domain.model.RuntimeStage
import com.lhzkml.spike.core.domain.repository.LogRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runtime 生命周期编排器（方案 §26）。
 *
 * 它是**唯一**知道"启动 Runtime 要做哪些步骤"的地方；
 * 这些步骤不允许出现在 Activity / Composable / ViewModel 中。
 *
 * 启动状态机（方案 §27）：
 *   STOPPED
 *     → PREPARING_ROOTFS
 *     → STARTING_PROOT
 *     → VERIFYING_LINUX
 *     → VERIFYING_NODE
 *     → READY
 *   任一步失败 → FAILED
 *
 * 多发行版：启动时指定 [Distro]，之后诊断等操作沿用同一个发行版。
 */
class RuntimeController(
    private val paths: RuntimePaths,
    private val proot: ProotManager,
    private val rootFs: RootFsManager,
    private val logs: LogRepository,
) {

    companion object {
        private const val TAG = "Runtime"
    }

    /** 步骤回调：把阶段推进通知给状态源 */
    interface StageListener {
        fun onStage(stage: RuntimeStage, detail: String)
        fun onProgress(fraction: Float?, detail: String)
    }

    /** 已启动的长期 shell（后续 Pi Server 也挂在这里） */
    private var shellHandle: ProotManager.ProcessHandle? = null

    /** 当前活动的发行版（由 [start] 确定） */
    @Volatile
    var activeDistro: Distro = Distro.DEFAULT
        private set

    fun shellProcess(): ProotManager.ProcessHandle? = shellHandle

    /**
     * 启动指定发行版的 Runtime。
     *
     * 每一步都做真实校验，而不是假定成功 —— 这决定了 UI 上显示的是
     * "检查 Node" 这类具体阶段，而不是笼统的 "Loading"。
     */
    suspend fun start(distro: Distro, listener: StageListener): Result<Unit> {
        activeDistro = distro
        val root = paths.rootfsOf(distro)

        // ---- PRoot 二进制就位 ----
        listener.onStage(RuntimeStage.PREPARING_ROOTFS, "检查 PRoot")
        proot.verify().getOrElse {
            logs.error(TAG, "PRoot 校验失败：${it.message}")
            return Result.failure(it)
        }

        // ---- rootfs 就位 ----
        if (!rootFs.isInstalled(distro)) {
            logs.error(TAG, "${distro.displayName} rootfs 未安装")
            return Result.failure(
                IllegalStateException("${distro.displayName} 未安装，请先安装"),
            )
        }
        listener.onStage(RuntimeStage.PREPARING_ROOTFS, "${distro.displayName} 就绪")

        // 沙盒边界：未授权时清掉外部存储的残留挂载点（proot 创建 bind 目标是持久的）
        proot.ensureSandboxIsolation(root)

        // ---- 启动 PRoot ----
        listener.onStage(RuntimeStage.STARTING_PROOT, "启动 PRoot")
        val probe = proot.execute(
            guestCmd = listOf("/bin/echo", "proot-ready"),
            rootfs = root,
            timeoutSec = 30,
        )
        if (!probe.ok) {
            val msg = probe.exception ?: probe.stderr.ifBlank { "exit=${probe.exitCode}" }
            logs.error(TAG, "PRoot 启动失败：$msg")
            return Result.failure(IllegalStateException("PRoot 启动失败：$msg"))
        }
        logs.info(TAG, "PRoot 可用（${probe.durationMs}ms）")

        // ---- 验证 Linux（真实执行 guest 命令）----
        listener.onStage(RuntimeStage.VERIFYING_LINUX, "验证 Linux")
        val linux = proot.execute(
            guestCmd = listOf("/bin/sh", "-c", "cat /etc/os-release"),
            rootfs = root,
            timeoutSec = 30,
        )
        if (!linux.ok) {
            val msg = linux.exception ?: linux.stderr.ifBlank { "exit=${linux.exitCode}" }
            logs.error(TAG, "Linux 验证失败：$msg")
            return Result.failure(IllegalStateException("Linux 验证失败：$msg"))
        }
        val prettyName = linux.stdout.lineSequence()
            .firstOrNull { it.startsWith("PRETTY_NAME=") }
            ?.substringAfter('=')
            ?.trim('"')
            ?: distro.displayName
        logs.info(TAG, "Linux 就绪：$prettyName")
        listener.onStage(RuntimeStage.VERIFYING_LINUX, prettyName)

        // ---- 验证 Node（由发行版包管理器安装；未装则明确跳过）----
        listener.onStage(RuntimeStage.VERIFYING_NODE, "检查 Node")
        val nodeCheck = proot.execute(
            guestCmd = listOf(
                "/bin/sh", "-c",
                "command -v node >/dev/null 2>&1 && " +
                    "node -e 'console.log(process.platform, process.arch)' || echo NODE_ABSENT",
            ),
            rootfs = root,
            timeoutSec = 60,
        )
        val nodeOut = nodeCheck.stdout.trim()
        when {
            nodeOut.startsWith("NODE_ABSENT") || !nodeCheck.ok -> {
                logs.warn(TAG, "未安装 Node（可用 ${DistroSpec.of(distro).packageManagerPath} 自行安装），跳过验证")
                listener.onStage(RuntimeStage.VERIFYING_NODE, "Node 未安装（跳过）")
            }
            else -> {
                logs.info(TAG, "Node 就绪：$nodeOut")
                listener.onStage(RuntimeStage.VERIFYING_NODE, "Node $nodeOut")
                if (nodeOut != "linux arm64") {
                    logs.warn(TAG, "Node 平台断言未通过：$nodeOut")
                }
            }
        }

        listener.onStage(RuntimeStage.READY, "Runtime 就绪")
        logs.info(TAG, "${distro.displayName} 启动完成")
        return Result.success(Unit)
    }

    /** 停止 Runtime：销毁长驻 shell */
    suspend fun stop(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            shellHandle?.let { handle ->
                if (handle.isAlive()) {
                    proot.stop(handle)
                    logs.info(TAG, "已停止 shell 进程 id=${handle.id}")
                }
            }
            shellHandle = null
        }
    }

    /** 采集诊断信息（方案 §28） */
    suspend fun collectDiagnostics(distro: Distro = activeDistro): List<Pair<String, String>> =
        withContext(Dispatchers.IO) {
            val spec = DistroSpec.of(distro)
            val root = paths.rootfsOf(distro)
            val out = mutableListOf<Pair<String, String>>()

            out += "发行版" to distro.displayName
            out += "PRoot 二进制" to if (paths.prootBinary.isFile) "OK" else "缺失"
            out += "PRoot 可执行" to if (paths.prootBinary.canExecute()) "OK" else "否"
            out += "PROOT_LOADER" to if (paths.prootLoader.isFile) "OK" else "缺失"
            out += "RootFS 已安装" to if (rootFs.isInstalled(distro)) "OK" else "否"
            out += "RootFS 路径" to root.absolutePath
            out += "RootFS SHA256" to (rootFs.installMarkerSha(distro) ?: "-")
            out += "包管理器" to spec.packageManagerPath
            out += "nativeLibraryDir" to paths.nativeLibraryDir

            if (rootFs.isInstalled(distro)) {
                // 局部 suspend 函数：统一在 guest 内跑一条命令
                suspend fun guest(vararg cmd: String, timeout: Long = 30L) = proot.execute(
                    guestCmd = cmd.toList(),
                    rootfs = root,
                    timeoutSec = timeout,
                )

                val osRelease = guest("/bin/sh", "-c", "cat /etc/os-release")
                out += "Linux" to if (osRelease.ok) "OK" else "失败:" + osRelease.stderr.take(120)
                out += "系统标识" to (osRelease.stdout.lineSequence()
                    .firstOrNull { it.startsWith("PRETTY_NAME=") }
                    ?.substringAfter('=')
                    ?.trim('"') ?: "-")

                // 伪造内核是否生效（对齐 proot-distro 的 --kernel-release）
                val uname = guest("/bin/sh", "-c", "uname -r")
                out += "内核版本(release)" to uname.stdout.trim().ifBlank { "-" }

                val arch = guest("/bin/sh", "-c", "uname -m")
                out += "内核架构" to arch.stdout.trim().ifBlank { "-" }

                val node = guest(
                    "/bin/sh", "-c",
                    "command -v node >/dev/null 2>&1 && node -v || echo absent",
                    timeout = 60L,
                )
                out += "Node" to node.stdout.trim().ifBlank { "-" }
                out += "process.platform/arch" to guest(
                    "/bin/sh", "-c",
                    "command -v node >/dev/null 2>&1 && " +
                        "node -e 'console.log(process.platform, process.arch)' || echo absent",
                    timeout = 60L,
                ).stdout.trim().ifBlank { "-" }
            }
            out
        }
}
