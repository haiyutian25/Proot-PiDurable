package com.lhzkml.spike.core.domain.model

/**
 * Runtime 的单一状态源（Single Source of Truth）。
 *
 * 由 RuntimeRepository 持有并暴露为 Flow，ViewModel / Service 都观察同一份。
 */
data class RuntimeState(
    val status: RuntimeStatus = RuntimeStatus.NOT_INSTALLED,
    val stage: RuntimeStage = RuntimeStage.IDLE,
    /** 当前进行中的操作描述，用于 UI 展示（例如 "解压中 4200/6655"） */
    val detail: String? = null,
    /** 0f..1f；null 表示不确定进度 */
    val progress: Float? = null,
    val error: String? = null,
    /** 安装完成后记录 rootfs 的校验值，便于诊断 */
    val rootfsSha256: String? = null,
    /** Runtime 启动的 Unix 时间戳（毫秒） */
    val startedAtMillis: Long? = null,
    /** 当前选中的发行版（持久化） */
    val selectedDistro: Distro = Distro.DEFAULT,
    /** 已安装的发行版集合 */
    val installedDistros: Set<Distro> = emptySet(),
) {
    val isBusy: Boolean
        get() = status == RuntimeStatus.INSTALLING ||
            status == RuntimeStatus.STARTING ||
            status == RuntimeStatus.STOPPING

    /** 当前选中的发行版是否已安装 */
    val selectedInstalled: Boolean get() = selectedDistro in installedDistros
}

/** 一次命令执行的结果（宿主侧或 guest 侧共用） */
data class CommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val durationMs: Long,
    val timedOut: Boolean = false,
    val exception: String? = null,
) {
    val ok: Boolean get() = !timedOut && exception == null && exitCode == 0
}

/** Diagnostics 页面所需的一项检查结果 */
data class DiagnosticItem(
    val key: String,
    val value: String,
    val ok: Boolean? = null,
)

/** Diagnostics 汇总（方案 §28） */
data class Diagnostics(
    val items: List<DiagnosticItem> = emptyList(),
    val generatedAtMillis: Long = 0L,
)
