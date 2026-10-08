package com.lhzkml.spike.feature.runtime.impl
import com.lhzkml.spike.core.domain.model.Distro

/**
 * UI 能发出的全部意图（方案 §6）。
 *
 * UI 只能产生这些意图，不能直接调用 Repository / PRoot / ProcessBuilder。
 */
sealed interface RuntimeIntent {
    /** 选择发行版 */
    data class SelectDistro(val distro: Distro) : RuntimeIntent

    /** 安装指定发行版的 rootfs */
    data class Install(val distro: Distro) : RuntimeIntent

    /** 删除指定发行版的 rootfs */
    data class Remove(val distro: Distro) : RuntimeIntent

    /** 启动 Runtime（当前选中发行版） */
    data object Start : RuntimeIntent

    /** 停止 Runtime */
    data object Stop : RuntimeIntent

    /** 重启 Runtime */
    data object Restart : RuntimeIntent

    /** 刷新状态 */
    data object Refresh : RuntimeIntent

    /**
     * 重新读取共享存储授权状态。
     *
     * 发起授权本身由 UI 层调 [com.lhzkml.spike.core.data.system.StorageAccess.request] 完成
     * （它需要 Activity，而 ViewModel 不应持有）；授权返回或从系统设置页回来后
     * 由 UI 发出本意图，让状态回到唯一来源。
     */
    data object RefreshStorage : RuntimeIntent

    /** 执行一条自定义命令（诊断用） */
    data class RunCommand(val command: String) : RuntimeIntent

    /** 清除错误 */
    data object ClearError : RuntimeIntent
}
