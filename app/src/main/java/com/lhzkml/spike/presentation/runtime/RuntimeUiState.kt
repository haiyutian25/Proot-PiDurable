package com.lhzkml.spike.presentation.runtime

import com.lhzkml.spike.domain.model.Distro
import com.lhzkml.spike.domain.model.RuntimeStage
import com.lhzkml.spike.domain.model.RuntimeStatus

/**
 * Runtime Screen 的 UI 状态（方案 §6）。
 *
 * UI 只渲染这个对象，不持有任何业务状态。
 */
data class RuntimeUiState(
    val status: RuntimeStatus = RuntimeStatus.NOT_INSTALLED,
    val stage: RuntimeStage = RuntimeStage.IDLE,
    val detail: String? = null,
    val progress: Float? = null,
    val error: String? = null,

    /** 可选发行版（顺序即展示顺序） */
    val distros: List<Distro> = Distro.entries.toList(),
    /** 当前选中的发行版 */
    val selectedDistro: Distro = Distro.DEFAULT,
    /** 已安装的发行版集合 */
    val installedDistros: Set<Distro> = emptySet(),

    val rootfsSha256: String? = null,

    /** 最近一次命令执行输出（诊断用） */
    val lastCommand: String? = null,
    val lastOutput: String? = null,
) {
    /** 当前选中发行版是否已安装 */
    val rootfsInstalled: Boolean get() = selectedDistro in installedDistros

    /** 安装按钮：空闲即可（已装则语义为"重装"） */
    val canInstall: Boolean get() = !isBusy && status != RuntimeStatus.RUNNING

    /** 删除按钮：已安装且空闲且未运行 */
    val canRemove: Boolean get() = !isBusy && rootfsInstalled && status != RuntimeStatus.RUNNING

    /** 启动按钮：已安装 rootfs 且空闲且未运行 */
    val canStart: Boolean
        get() = !isBusy && rootfsInstalled && status != RuntimeStatus.RUNNING

    val canStop: Boolean get() = !isBusy && status == RuntimeStatus.RUNNING

    val canRestart: Boolean get() = !isBusy && rootfsInstalled

    /** 是否有操作正在进行（UI 用来禁用交互） */
    val isBusy: Boolean
        get() = status == RuntimeStatus.INSTALLING ||
            status == RuntimeStatus.STARTING ||
            status == RuntimeStatus.STOPPING
}
