package com.lhzkml.spike.terminal.shared

/**
 * 终端模块的常量（精简自 Noxs 的 NoxsConstants，只保留终端本身需要的项）。
 *
 * Ported from Noxs — Apache License 2.0.
 */
object TerminalDefaults {

    /** 回滚缓冲区行数 */
    const val DEFAULT_SCROLLBACK = 10000

    /** guest 内 TERM 值 */
    const val TERM_VALUE = "xterm-256color"

    /** guest 内 LANG 值 */
    const val DEFAULT_LANG = "C.UTF-8"
}
