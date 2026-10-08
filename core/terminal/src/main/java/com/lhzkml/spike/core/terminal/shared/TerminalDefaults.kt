package com.lhzkml.spike.core.terminal.shared

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

    /**
     * guest 内 LANG 值。
     *
     * **不能写成 `C.UTF-8`**：Ubuntu 26.04 的 coreutils 是 uutils（Rust）实现，其 `ls`
     * 按 locale **名字**判断能否直接输出非 ASCII 文件名，只认 `xx_YY.codeset` 形态 ——
     * 用 `C.UTF-8` 会把中文文件名输出成八进制转义。
     *
     * 另注意 uutils 让 `LC_ALL` 优先于 `LANG`，且 Ubuntu 的
     * `/etc/profile.d/01-locale-fix.sh` 会改写非法 locale 值；因此 `en_US.UTF-8`
     * 必须真正合法 —— 由 `GuestFixup.ensureLocale` 复制 glibc 的 `C.utf8` 数据来保证。
     *
     * 实际生效值见 `ProotManager.buildEnv()`；本常量只为需要单独引用时保持一致。
     */
    const val DEFAULT_LANG = "en_US.UTF-8"
}
