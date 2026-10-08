package com.lhzkml.spike.feature.terminal.impl

import com.lhzkml.spike.core.domain.model.Distro

/**
 * 终端页面的 UI 状态。
 *
 * 终端的"内容"（屏幕缓冲区）由 TerminalView 自己持有，
 * 这里只描述工具条与按键栏需要的元信息。
 */
data class TerminalUiState(
    /** 会话所在的发行版（跟随 Runtime 的选中项） */
    val distro: Distro = Distro.DEFAULT,
    /** 会话是否在运行 */
    val running: Boolean = false,
    /** 是否用上了真 PTY（false 表示退化为管道模式） */
    val isPty: Boolean = false,
    /** 会话结束时的退出码 */
    val exitCode: Int? = null,
    /** 该发行版是否已安装 */
    val installed: Boolean = false,
    /** Ctrl 修饰键是否处于锁定态（按下后下一次按键带上 Ctrl） */
    val ctrlLatched: Boolean = false,
    /** Alt 修饰键是否处于锁定态 */
    val altLatched: Boolean = false,
    val error: String? = null,
) {
    /** 终端可用：已安装且会话在运行 */
    val usable: Boolean get() = installed && running

    /** 模式标签 */
    val modeLabel: String get() = if (isPty) "PTY" else "管道"
}
