package com.lhzkml.spike.data.runtime

import com.lhzkml.spike.domain.model.CommandResult
import com.lhzkml.spike.domain.model.DiagnosticItem
import com.lhzkml.spike.domain.model.Diagnostics
import com.lhzkml.spike.domain.model.Distro
import com.lhzkml.spike.domain.model.RuntimeStage
import com.lhzkml.spike.domain.model.RuntimeState
import com.lhzkml.spike.domain.model.RuntimeStatus
import com.lhzkml.spike.domain.repository.LogRepository
import com.lhzkml.spike.domain.repository.RuntimeRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Runtime 仓库实现 —— **单一状态源**（方案 §24）。
 *
 * 只有这里持有 RuntimeState。Activity / ViewModel / Service 都通过
 * [observeState] 观察同一份状态，避免多份状态互相不同步。
 */
class RuntimeRepositoryImpl(
    private val paths: RuntimePaths,
    private val proot: ProotManager,
    private val rootFs: RootFsManager,
    private val controller: RuntimeController,
    private val logs: LogRepository,
) : RuntimeRepository {

    companion object {
        private const val TAG = "Repository"
    }

    private val state = MutableStateFlow(RuntimeState())

    /** 串行化所有会改动状态的操作，避免并发安装/启动 */
    private val mutex = Mutex()

    init {
        val selected = Distro.fromId(paths.selectedDistroId)
        val installed = rootFs.installedDistros().toSet()
        state.value = RuntimeState(
            status = if (selected in installed) RuntimeStatus.STOPPED else RuntimeStatus.NOT_INSTALLED,
            selectedDistro = selected,
            installedDistros = installed,
            rootfsSha256 = rootFs.installMarkerSha(selected),
        )
    }

    override fun observeState(): Flow<RuntimeState> = state.asStateFlow()

    override fun currentState(): RuntimeState = state.value

    // ------------------------------------------------------------------

    override suspend fun selectDistro(distro: Distro): Result<Unit> = mutex.withLock {
        if (state.value.isBusy) {
            return Result.failure(IllegalStateException("操作进行中，暂不能切换发行版"))
        }
        // 运行中切换发行版没有意义（Runtime 已绑定某个 rootfs），先停再切
        if (state.value.status == RuntimeStatus.RUNNING) {
            logs.warn(TAG, "Runtime 运行中，先停止再切换发行版")
            controller.stop()
        }
        paths.selectedDistroId = distro.id
        val installed = state.value.installedDistros
        update {
            it.copy(
                selectedDistro = distro,
                status = if (distro in installed) RuntimeStatus.STOPPED else RuntimeStatus.NOT_INSTALLED,
                stage = RuntimeStage.IDLE,
                detail = "已切换到 ${distro.displayName}",
                progress = null,
                error = null,
                startedAtMillis = null,
                rootfsSha256 = rootFs.installMarkerSha(distro),
            )
        }
        logs.info(TAG, "切换发行版：${distro.displayName}")
        Result.success(Unit)
    }

    override suspend fun install(distro: Distro): Result<Unit> = mutex.withLock {
        if (state.value.isBusy) {
            return Result.failure(IllegalStateException("已有操作正在进行"))
        }

        // 旧布局（单发行版 Debian 12）遗留数据在此一次性清理
        rootFs.cleanupLegacy()

        paths.selectedDistroId = distro.id
        update {
            it.copy(
                selectedDistro = distro,
                status = RuntimeStatus.INSTALLING,
                stage = RuntimeStage.PREPARING_ROOTFS,
                detail = "准备安装 ${distro.displayName}",
                progress = null,
                error = null,
            )
        }
        logs.info(TAG, "开始安装 ${distro.displayName}")

        val result = rootFs.install(distro, object : RootFsManager.Progress {
            override fun onStage(stage: String, detail: String) {
                update { it.copy(stage = RuntimeStage.PREPARING_ROOTFS, detail = detail, progress = null) }
            }

            override fun onDownload(downloaded: Long, total: Long) {
                val fraction = if (total > 0) {
                    (downloaded.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
                } else null
                val mb = downloaded / 1048576.0
                val totalMb = if (total > 0) "%.1f MiB".format(total / 1048576.0) else "?"
                update {
                    it.copy(
                        stage = RuntimeStage.PREPARING_ROOTFS,
                        detail = "下载 %.1f / %s".format(mb, totalMb),
                        progress = fraction,
                    )
                }
            }
        })

        result.fold(
            onSuccess = {
                update {
                    it.copy(
                        status = RuntimeStatus.STOPPED,
                        stage = RuntimeStage.IDLE,
                        detail = "${distro.displayName} 安装完成",
                        progress = null,
                        error = null,
                        installedDistros = rootFs.installedDistros().toSet(),
                        rootfsSha256 = rootFs.installMarkerSha(distro),
                    )
                }
                logs.info(TAG, "${distro.displayName} 安装成功")
            },
            onFailure = { e ->
                update {
                    it.copy(
                        status = RuntimeStatus.ERROR,
                        stage = RuntimeStage.FAILED,
                        detail = null,
                        progress = null,
                        error = e.message ?: e.javaClass.simpleName,
                    )
                }
                logs.error(TAG, "安装失败：${e.message}")
            },
        )
        result
    }

    override suspend fun remove(distro: Distro): Result<Unit> = mutex.withLock {
        if (state.value.isBusy) {
            return Result.failure(IllegalStateException("已有操作正在进行"))
        }
        if (state.value.status == RuntimeStatus.RUNNING &&
            state.value.selectedDistro == distro
        ) {
            controller.stop()
        }
        val result = rootFs.remove(distro)
        val installed = rootFs.installedDistros().toSet()
        update {
            it.copy(
                installedDistros = installed,
                status = if (it.selectedDistro in installed) RuntimeStatus.STOPPED
                else RuntimeStatus.NOT_INSTALLED,
                stage = RuntimeStage.IDLE,
                detail = "${distro.displayName} 已删除",
                progress = null,
                startedAtMillis = null,
                rootfsSha256 = if (it.selectedDistro in installed) {
                    rootFs.installMarkerSha(it.selectedDistro)
                } else null,
            )
        }
        result
    }

    override suspend fun start(): Result<Unit> = mutex.withLock {
        if (state.value.isBusy) {
            return Result.failure(IllegalStateException("已有操作正在进行"))
        }
        val distro = state.value.selectedDistro
        if (!rootFs.isInstalled(distro)) {
            val msg = "${distro.displayName} 未安装"
            update { it.copy(status = RuntimeStatus.ERROR, stage = RuntimeStage.FAILED, error = msg) }
            logs.error(TAG, msg)
            return Result.failure(IllegalStateException(msg))
        }

        update {
            it.copy(
                status = RuntimeStatus.STARTING,
                stage = RuntimeStage.PREPARING_ROOTFS,
                detail = "正在启动 ${distro.displayName}",
                progress = null,
                error = null,
            )
        }
        logs.info(TAG, "开始启动 ${distro.displayName}")

        val result = controller.start(distro, object : RuntimeController.StageListener {
            override fun onStage(stage: RuntimeStage, detail: String) {
                update { it.copy(stage = stage, detail = detail) }
            }

            override fun onProgress(fraction: Float?, detail: String) {
                update { it.copy(progress = fraction, detail = detail) }
            }
        })

        result.fold(
            onSuccess = {
                update {
                    it.copy(
                        status = RuntimeStatus.RUNNING,
                        stage = RuntimeStage.READY,
                        detail = "Runtime 就绪",
                        progress = null,
                        error = null,
                        startedAtMillis = System.currentTimeMillis(),
                    )
                }
                logs.info(TAG, "启动成功")
            },
            onFailure = { e ->
                update {
                    it.copy(
                        status = RuntimeStatus.ERROR,
                        stage = RuntimeStage.FAILED,
                        detail = null,
                        progress = null,
                        error = e.message ?: e.javaClass.simpleName,
                    )
                }
                logs.error(TAG, "启动失败：${e.message}")
            },
        )
        result
    }

    override suspend fun stop(): Result<Unit> = mutex.withLock {
        logs.info(TAG, "停止 Runtime")
        update { it.copy(status = RuntimeStatus.STOPPING, detail = "正在停止") }
        val result = controller.stop()
        val installed = state.value.installedDistros
        update {
            it.copy(
                status = if (it.selectedDistro in installed) RuntimeStatus.STOPPED
                else RuntimeStatus.NOT_INSTALLED,
                stage = RuntimeStage.IDLE,
                detail = "已停止",
                progress = null,
                startedAtMillis = null,
            )
        }
        result
    }

    override suspend fun diagnostics(): Diagnostics {
        val distro = state.value.selectedDistro
        val items = mutableListOf<DiagnosticItem>()

        items += DiagnosticItem("PRoot", if (paths.prootBinary.canExecute()) "OK" else "不可用",
            paths.prootBinary.canExecute())
        items += DiagnosticItem("PROOT_LOADER", if (paths.prootLoader.isFile) "OK" else "缺失",
            paths.prootLoader.isFile)
        items += DiagnosticItem("RootFS", if (rootFs.isInstalled(distro)) "OK" else "未安装",
            rootFs.isInstalled(distro))
        items += DiagnosticItem("RootFS SHA256", rootFs.installMarkerSha(distro) ?: "-", null)
        items += DiagnosticItem("已安装发行版",
            state.value.installedDistros.joinToString { it.displayName }.ifBlank { "-" }, null)
        items += DiagnosticItem("Runtime 状态", state.value.status.name, null)
        items += DiagnosticItem("启动阶段", state.value.stage.label, null)

        controller.collectDiagnostics(distro).forEach { (k, v) ->
            items += DiagnosticItem(k, v, null)
        }

        return Diagnostics(items = items, generatedAtMillis = System.currentTimeMillis())
    }

    override suspend fun execute(command: List<String>): CommandResult {
        val distro = state.value.selectedDistro
        logs.info(TAG, "执行（${distro.id}）：${command.joinToString(" ")}")
        val result = proot.execute(
            guestCmd = command,
            rootfs = paths.rootfsOf(distro),
        )
        logs.info(TAG, "exit=${result.exitCode} ${result.durationMs}ms")
        return result
    }

    override fun clearLogs() = logs.clear()

    private inline fun update(block: (RuntimeState) -> RuntimeState) {
        state.value = block(state.value)
    }
}
