package com.lhzkml.spike.presentation.terminal

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
 * 因此符号与空格必须由按键栏兜住：第三行符号、第四行空格 + shell 常用字符。
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
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(KeyBarBackground)
            .padding(horizontal = 4.dp, vertical = 3.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        // 第一行：退出 / 执行 / 补全 / 中断 / 行首尾 / 上翻
        Row(modifier = Modifier.fillMaxWidth()) {
            KeyButton(label = "ESC") { onByte(byteArrayOf(0x1b)) }
            KeyButton(label = "⏎") { onSpecial(KeyEvent.KEYCODE_ENTER) }
            KeyButton(label = "⇥") { onSpecial(KeyEvent.KEYCODE_TAB) }
            KeyButton(label = "^C", danger = true) { onByte(byteArrayOf(0x03)) }
            KeyButton(label = "HOME") { onSpecial(KeyEvent.KEYCODE_MOVE_HOME) }
            KeyButton(label = "END") { onSpecial(KeyEvent.KEYCODE_MOVE_END) }
            KeyButton(label = "PGUP") { onSpecial(KeyEvent.KEYCODE_PAGE_UP) }
        }

        // 第二行：修饰键（锁定）+ 方向键 + 下翻
        Row(modifier = Modifier.fillMaxWidth()) {
            KeyButton(label = "CTRL", latched = ctrlLatched, onClick = onToggleCtrl)
            KeyButton(label = "ALT", latched = altLatched, onClick = onToggleAlt)
            KeyButton(label = "←") { onSpecial(KeyEvent.KEYCODE_DPAD_LEFT) }
            KeyButton(label = "↑") { onSpecial(KeyEvent.KEYCODE_DPAD_UP) }
            KeyButton(label = "↓") { onSpecial(KeyEvent.KEYCODE_DPAD_DOWN) }
            KeyButton(label = "→") { onSpecial(KeyEvent.KEYCODE_DPAD_RIGHT) }
            KeyButton(label = "PGDN") { onSpecial(KeyEvent.KEYCODE_PAGE_DOWN) }
        }

        // 第三行：常用符号。
        // 手机自带输入法会把 / - . ~ | 之类替换成中文标点（实测输入 / 得到 、），
        // 导致路径与选项根本敲不出来，所以这些符号必须在按键栏里能直接发。
        Row(modifier = Modifier.fillMaxWidth()) {
            KeyButton(label = "/") { onByte("/".toByteArray()) }
            KeyButton(label = "-") { onByte("-".toByteArray()) }
            KeyButton(label = ".") { onByte(".".toByteArray()) }
            KeyButton(label = "~") { onByte("~".toByteArray()) }
            KeyButton(label = "|") { onByte("|".toByteArray()) }
            KeyButton(label = "_") { onByte("_".toByteArray()) }
            KeyButton(label = "*") { onByte("*".toByteArray()) }
        }

        // 第四行：空格 + shell 常用字符。
        // 空格是终端里最高频的字符，而输入法切到数字/符号布局后没有空格键
        // （实测把 `ls /` 打成了 `ls/`），所以这里给空格两格宽度兜底。
        Row(modifier = Modifier.fillMaxWidth()) {
            KeyButton(label = "空格", weight = 2f) { onByte(" ".toByteArray()) }
            KeyButton(label = "=") { onByte("=".toByteArray()) }
            KeyButton(label = "$") { onByte("$".toByteArray()) }
            KeyButton(label = "\"") { onByte("\"".toByteArray()) }
            KeyButton(label = "'") { onByte("'".toByteArray()) }
            KeyButton(label = "?") { onByte("?".toByteArray()) }
        }
    }
}

@Composable
private fun RowScope.KeyButton(
    label: String,
    latched: Boolean = false,
    danger: Boolean = false,
    /** 占几格宽（同一行的 weight 之和决定分配比例） */
    weight: Float = 1f,
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
            .weight(weight)
            .height(40.dp)
            .padding(horizontal = 2.dp)
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
