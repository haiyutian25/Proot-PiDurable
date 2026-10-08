package com.lhzkml.spike.feature.terminal.impl

/** 终端页面能发出的意图 */
sealed interface TerminalIntent {
    /** 重建会话（结束当前 shell 并重开） */
    data object Restart : TerminalIntent

    /** 清屏（只清屏幕缓冲区，不动 shell 与文件系统） */
    data object ClearScreen : TerminalIntent

    /** 从系统剪贴板粘贴到终端 */
    data object Paste : TerminalIntent

    /** 按键栏：切换 Ctrl 锁定态 */
    data object ToggleCtrl : TerminalIntent

    /** 按键栏：切换 Alt 锁定态 */
    data object ToggleAlt : TerminalIntent

    /** 关闭软键盘 */
    data object HideKeyboard : TerminalIntent

    /** 清除错误提示 */
    data object ClearError : TerminalIntent
}
