package com.lhzkml.spike.core.data.di

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.lhzkml.spike.core.data.logging.InMemoryLogRepository
import com.lhzkml.spike.core.data.runtime.GuestFixup
import com.lhzkml.spike.core.data.runtime.ProotManager
import com.lhzkml.spike.core.data.runtime.RootFsManager
import com.lhzkml.spike.core.data.runtime.RuntimeController
import com.lhzkml.spike.core.data.runtime.RuntimePaths
import com.lhzkml.spike.core.data.runtime.RuntimeRepositoryImpl
import com.lhzkml.spike.core.data.runtime.SysDataStubs
import com.lhzkml.spike.core.data.system.StorageAccess
import com.lhzkml.spike.core.data.system.SysInfoRepositoryImpl
import com.lhzkml.spike.core.data.terminal.TerminalSessionManager
import com.lhzkml.spike.core.domain.repository.LogRepository
import com.lhzkml.spike.core.domain.repository.RuntimeRepository
import com.lhzkml.spike.core.domain.repository.SysInfoRepository
import com.lhzkml.spike.core.domain.usecase.ExecuteInRuntimeUseCase
import com.lhzkml.spike.core.domain.usecase.GetDiagnosticsUseCase
import com.lhzkml.spike.core.domain.usecase.InstallRuntimeUseCase
import com.lhzkml.spike.core.domain.usecase.RemoveDistroUseCase
import com.lhzkml.spike.core.domain.usecase.RestartRuntimeUseCase
import com.lhzkml.spike.core.domain.usecase.SelectDistroUseCase
import com.lhzkml.spike.core.domain.usecase.StartRuntimeUseCase
import com.lhzkml.spike.core.domain.usecase.StopRuntimeUseCase
import com.lhzkml.spike.core.terminal.emulator.NativePty

/**
 * 依赖装配（手工 DI）。
 *
 * 装配关系严格遵循方案的分层：
 *   Presentation → UseCase → Repository(接口) → Data 实现 → ProotManager（PRoot 唯一入口）
 *
 * 构造过程位于冷启动关键路径，每一步都打点计时，便于定位启动耗时。
 */
class AppContainer(context: Context) {

    companion object {
        private const val TAG = "Startup"
    }

    private val appContext = context.applicationContext
    private val t0 = SystemClock.elapsedRealtime()

    private fun mark(label: String) {
        Log.i(TAG, "  $label @${SystemClock.elapsedRealtime() - t0}ms")
    }

    // ---- 进程加固：必须早于任何子进程 ----
    //
    // 沙盒与宿主 App 同 uid、同 SELinux 域，内核的 ptrace 规则会把两者当"自己人"：
    // 沙盒内进程可以直接读宿主的 /proc/<pid>/fd/N（例如 base.apk 的句柄）、maps、
    // environ，并对内存做 process_vm_readv / PTRACE_ATTACH —— 等于绕过 proot 的路径
    // 翻译读到 rootfs 之外。置 PR_SET_DUMPABLE=0 后，同 uid 且无 CAP_SYS_PTRACE 的
    // 进程访问这些节点一律被拒（真机对照实测：fd/maps/environ 由"可读"变为"被拒"）。
    //
    // 两点约束：① 必须在**宿主进程**里设 —— 沙盒内设会被 proot 伪造；② 必须早于任何
    // spawn，所以放在属性初始化最前面（Kotlin 按声明顺序执行）。
    // 局限：只保护 app 进程自身；沙盒内同 uid 的进程彼此仍互读（那是本架构的固有限制）。
    init {
        val hardened = NativePty.isAvailable() &&
            runCatching { NativePty.disableDumping() }.getOrDefault(false)
        Log.i(TAG, "  process hardened (dumpable=0): $hardened")
    }

    // ---- data 层基础设施 ----

    val paths: RuntimePaths = RuntimePaths(appContext).also { mark("RuntimePaths") }

    val logs: LogRepository = InMemoryLogRepository().also { mark("LogRepository") }

    private val prootManager = ProotManager(
        paths = paths,
        // 沙盒边界：没拿到完整（可写）访问时完全不绑定外部存储（guest 内连路径都不存在）。
        // 用 lambda 而不是快照值 —— 用户可能随时授权，每次构造 argv 都要按当时状态判定；
        // 权限变更时系统会重新挂载已有进程的命名空间，所以这里能立刻反映新状态。
        storageGranted = { StorageAccess.hasAccess(appContext) },
    ).also { mark("ProotManager") }

    /**
     * rootfs 安装后的修补（DNS / hosts）。
     *
     * Android 宿主没有 `/etc/resolv.conf`，不写这一步 guest 内 apt/apk 解析不了域名。
     */
    private val guestFixup = GuestFixup(
        dns = GuestFixup.AndroidDnsProvider(appContext),
        logs = logs,
        // 时区等调整要在 guest 内跑脚本（Alpine 需要 apk add tzdata），因此需要 ProotManager
        proot = prootManager,
    ).also { mark("GuestFixup") }

    private val rootFsManager = RootFsManager(
        paths = paths,
        logs = logs,
        fixup = guestFixup,
    ).also { mark("RootFsManager") }

    private val runtimeController = RuntimeController(
        paths = paths,
        proot = prootManager,
        rootFs = rootFsManager,
        logs = logs,
    ).also { mark("RuntimeController") }

    // ---- 仓库 ----

    val runtimeRepository: RuntimeRepository = RuntimeRepositoryImpl(
        paths = paths,
        proot = prootManager,
        rootFs = rootFsManager,
        controller = runtimeController,
        logs = logs,
    ).also { mark("RuntimeRepositoryImpl") }

    val sysInfoRepository: SysInfoRepository = SysInfoRepositoryImpl(appContext).also { mark("SysInfo") }

    /**
     * 终端会话管理器（长生命周期：跨页面与旋转存活）。
     *
     * 它复用 ProotManager 的 argv/env 构造，保证终端里的 proot 参数与
     * Runtime 执行命令时完全一致。
     */
    val terminalSessions = TerminalSessionManager(
        paths = paths,
        proot = prootManager,
        logs = logs,
        fixup = guestFixup,
    ).also { mark("TerminalSessionManager") }

    /** 供 Service 与诊断直接使用（仍属 data 层内部件，不暴露给 UI） */
    val proot: ProotManager get() = prootManager
    val rootFs: RootFsManager get() = rootFsManager
    val controller: RuntimeController get() = runtimeController

    /**
     * 共享存储是否已授权。
     *
     * ViewModel 不持有 Context，所以把这次查询收在这里 ——
     * 它最终决定 guest 内 `/sdcard` 是否可见（见 [ProotBinds.storage]）。
     */
    fun hasStorageAccess(): Boolean = StorageAccess.hasAccess(appContext)

    // ---- 用例 ----

    val selectDistro = SelectDistroUseCase(runtimeRepository).also { mark("useCases") }
    val installRuntime = InstallRuntimeUseCase(runtimeRepository)
    val removeDistro = RemoveDistroUseCase(runtimeRepository)
    val startRuntime = StartRuntimeUseCase(runtimeRepository)
    val stopRuntime = StopRuntimeUseCase(runtimeRepository)
    val restartRuntime = RestartRuntimeUseCase(runtimeRepository)
    val getDiagnostics = GetDiagnosticsUseCase(runtimeRepository)
    val executeInRuntime = ExecuteInRuntimeUseCase(runtimeRepository)

    init {
        // 生成伪造的 /proc 条目（供 SysDataStubs 绑定）。失败不阻断启动，
        // 只是少了几个 stub，guest 内会看到 Android 原生的 /proc 内容。
        if (!SysDataStubs.materialize(paths.sysdata)) {
            Log.w(TAG, "sysdata stub 写入失败，guest 将使用宿主原生 /proc")
        }
        mark("sysDataStubs")
        mark("AppContainer done")
    }
}
