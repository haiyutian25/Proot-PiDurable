package com.lhzkml.spike.data.terminal

import android.os.Handler
import android.os.Looper
import com.lhzkml.spike.data.runtime.ProotManager
import com.lhzkml.spike.data.runtime.RuntimePaths
import com.lhzkml.spike.domain.model.Distro
import com.lhzkml.spike.domain.repository.LogRepository
import com.lhzkml.spike.terminal.emulator.TerminalSession
import com.lhzkml.spike.terminal.emulator.TerminalSessionClient
import java.io.File

/**
 * 终端会话管理：把 PTY 会话绑定到某个发行版的 rootfs 上。
 *
 * 关键约定（与 ProotManager 保持一致，不重复定义参数）：
 *  · 交互式 shell 的 argv/env **复用 [ProotManager]**，避免两处参数漂移
 *  · PTY 由原生桥创建（`libterm-pty.so`），子进程直接 execve proot 二进制
 *  · cwd 必须传**宿主侧**的真实目录（原生层 chdir 后 execve）
 *
 * 会话是长生命周期的，因此由 AppContainer 持有，跨页面/旋转存活。
 */
class TerminalSessionManager(
    private val paths: RuntimePaths,
    private val proot: ProotManager,
    private val logs: LogRepository,
) {

    companion object {
        private const val TAG = "Terminal"
    }

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var session: TerminalSession? = null

    @Volatile
    private var distro: Distro? = null

    fun current(): TerminalSession? = session

    fun currentDistro(): Distro? = distro

    /**
     * 取当前会话；若会话不在运行、或目标发行版变了，则重建。
     *
     * 返回的会话已处于运行状态，可直接交给 TerminalView 绑定。
     */
    fun ensureSession(target: Distro, client: TerminalSessionClient): Result<TerminalSession> {
        val existing = session
        if (existing != null && existing.isRunning && distro == target) {
            return Result.success(existing)
        }
        existing?.let { runCatching { it.kill() } }
        session = null
        return create(target, client)
    }

    /** 强制重建会话（用户点"重启终端"） */
    fun restart(target: Distro, client: TerminalSessionClient): Result<TerminalSession> {
        session?.let { runCatching { it.kill() } }
        session = null
        return create(target, client)
    }

    /** 关闭会话（页面销毁或用户主动结束） */
    fun close() {
        session?.let { runCatching { it.kill() } }
        session = null
        distro = null
    }

    // ------------------------------------------------------------------

    private fun create(
        target: Distro,
        client: TerminalSessionClient,
    ): Result<TerminalSession> = runCatching {
        val root = paths.rootfsOf(target)
        check(root.isDirectory) { "${target.displayName} 尚未安装" }
        proot.verify().getOrThrow()

        // guest 内工作目录：优先 /root，缺失则退回 /
        val guestCwd = if (File(root, "root").isDirectory) "/root" else "/"

        // 交互式 shell：**必须优先 bash**。
        // bash 自带 readline，方向键 / HOME / END / 历史 / Tab 补全才能正常工作，
        // 且登录时会加载 /etc/profile 得到彩色提示符；
        // 退而求其次的 /bin/sh 在 Ubuntu 上是 dash（无 readline），
        // 这些转义序列会被原样回显成 ^[[A 之类的文本。
        // Alpine 默认不带 bash，其 /bin/sh 是 busybox ash（有行编辑，可用）。
        val guestShell = if (File(root, "bin/bash").exists()) {
            listOf("/bin/bash", "--login")
        } else {
            listOf("/bin/sh", "-l")
        }

        // argv/env 与 Runtime 执行命令共用同一套构造
        val argv = proot.buildArgv(
            rootfs = root,
            workDir = guestCwd,
            guestCmd = guestShell,
        )
        val env = proot.buildEnv().map { (k, v) -> "$k=$v" }

        logs.info(
            TAG,
            "启动终端：${target.displayName}（shell=${guestShell.joinToString(" ")}，cwd=$guestCwd）",
        )

        val s = TerminalSession(label = target.displayName, client = client)
        // 输出/标题等回调统一切到主线程，避免在 reader 线程碰 UI
        s.mainThreadDispatcher = { r -> main.post(r) }
        s.start(
            cmd = argv.toTypedArray(),
            env = env.toTypedArray(),
            // 进程的宿主侧工作目录用 tmp（与 Noxs 一致）；
            // guest 内的实际 cwd 由上面 proot 的 -w 指定，二者是不同层面。
            cwd = paths.tmp.absolutePath,
            preferPty = true,
        )

        session = s
        distro = target
        logs.info(TAG, "终端就绪：pty=${s.isPty} pid=${s.pid}")
        s
    }
}
