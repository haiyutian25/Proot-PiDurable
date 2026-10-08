package com.lhzkml.spike.presentation.terminal

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 终端按键栏（Compose 实现）。
 *
 * 取代原先移植自 Noxs 的 `TerminalExtraKeysBar`（Android View）。原设计只放
 * 「软键盘给不了的键」（ESC / 回车 / Tab / Ctrl / Alt / 方向键 / 翻页 / 中断）。
 *
 * **但在真机上不能只依赖软键盘**，实测两类问题：
 *  1. 设备输入法（讯飞）会把 `/ - . ~ |` 改写成中文标点 —— 打 `/` 得到 `、`，
 *     路径与选项根本敲不出来；
 *  2. 输入法切到数字/符号布局后**没有空格键**，`ls /` 会被打成 `ls/`。
 * 因此符号与空格也必须由按键栏兜住。
 *
 * **布局：单行横向滚动**。原先排成四行，占用纵向空间过多（软键盘弹出时尤其明显）。
 * 现在所有键排成一行、放不下就左右滑动 —— 这样只剩一行高度，且能容纳更多键。
 *
 * 实现要点：`horizontalScroll` 给子项的是**无限宽度约束**，所以键宽不能用
 * `weight`（weight 在有界宽度下才有意义），必须给固定/最小宽度。
 *
 * Ctrl / Alt 是"锁定"语义：按下后下一次按键会带上修饰符，随后自动解锁。
 * 锁定时高亮显示 —— 状态由 TerminalViewModel 持有（单一来源），
 * 因为 Compose 需要它驱动重组。
 */
@Composable
fun TerminalKeyBar(
    ctrlLatched: Boolean,
    altLatched: Boolean,
    /** 发送原始字节（如 ESC、^C） */
    onByte: (ByteArray) -> Unit,
    /** 发送特殊键（走 KeyHandler 映射，随 appCursorKeys 模式变化） */
    onSpecial: (Int) -> Unit,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(KeyBarBackground)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // 顺序刻意排成「常用在前」—— 单行放不下，靠左的键最先露出来。
        // 1) 空格（终端最高频，必须最先可见）
        KeyButton(label = "空格", minWidth = 72.dp) { onByte(" ".toByteArray()) }
        // 2) 执行 / 退出 / 补全 / 中断
        KeyButton(label = "⏎") { onSpecial(KeyEvent.KEYCODE_ENTER) }
        KeyButton(label = "ESC") { onByte(byteArrayOf(0x1b)) }
        KeyButton(label = "⇥") { onSpecial(KeyEvent.KEYCODE_TAB) }
        KeyButton(label = "^C", danger = true) { onByte(byteArrayOf(0x03)) }
        // 3) 路径与选项必需的符号
        KeyButton(label = "/") { onByte("/".toByteArray()) }
        KeyButton(label = "-") { onByte("-".toByteArray()) }
        KeyButton(label = ".") { onByte(".".toByteArray()) }
        KeyButton(label = "~") { onByte("~".toByteArray()) }
        KeyButton(label = "_") { onByte("_".toByteArray()) }
        KeyButton(label = "|") { onByte("|".toByteArray()) }
        KeyButton(label = "*") { onByte("*".toByteArray()) }
        // 4) shell 常用字符
        KeyButton(label = "=") { onByte("=".toByteArray()) }
        KeyButton(label = "$") { onByte("$".toByteArray()) }
        KeyButton(label = "\"") { onByte("\"".toByteArray()) }
        KeyButton(label = "'") { onByte("'".toByteArray()) }
        KeyButton(label = "?") { onByte("?".toByteArray()) }
        // 5) 修饰键（锁定态）
        KeyButton(label = "CTRL", latched = ctrlLatched, onClick = onToggleCtrl)
        KeyButton(label = "ALT", latched = altLatched, onClick = onToggleAlt)
        // 6) 方向键
        KeyButton(label = "←") { onSpecial(KeyEvent.KEYCODE_DPAD_LEFT) }
        KeyButton(label = "↑") { onSpecial(KeyEvent.KEYCODE_DPAD_UP) }
        KeyButton(label = "↓") { onSpecial(KeyEvent.KEYCODE_DPAD_DOWN) }
        KeyButton(label = "→") { onSpecial(KeyEvent.KEYCODE_DPAD_RIGHT) }
        // 7) 行首尾与翻页
        KeyButton(label = "HOME") { onSpecial(KeyEvent.KEYCODE_MOVE_HOME) }
        KeyButton(label = "END") { onSpecial(KeyEvent.KEYCODE_MOVE_END) }
        KeyButton(label = "PGUP") { onSpecial(KeyEvent.KEYCODE_PAGE_UP) }
        KeyButton(label = "PGDN") { onSpecial(KeyEvent.KEYCODE_PAGE_DOWN) }
    }
}

/**
 * 单个按键。
 *
 * 宽度用 [minWidth] 而不是 `weight` —— 本栏处在 `horizontalScroll` 内，
 * 子项拿到的是无限宽度约束，weight 无效。
 */
@Composable
private fun KeyButton(
    label: String,
    latched: Boolean = false,
    danger: Boolean = false,
    minWidth: Dp = 56.dp,
    onClick: () -> Unit,
) {
    val container = when {
        latched -> MaterialTheme.colorScheme.primaryContainer
        danger -> DangerContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val content = when {
        latched -> MaterialTheme.colorScheme.onPrimaryContainer
        danger -> DangerContent
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = Modifier
            .widthIn(min = minWidth)
            .height(40.dp)
            .padding(horizontal = 4.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(container)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = content,
            fontWeight = if (latched) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

private val KeyBarBackground = Color(0xFF141418)
private val DangerContainer = Color(0xFF3A2226)
private val DangerContent = Color(0xFFEF9A9A)
