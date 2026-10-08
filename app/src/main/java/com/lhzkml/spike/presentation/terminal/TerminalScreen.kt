package com.lhzkml.spike.presentation.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.lhzkml.spike.terminal.view.TerminalView

/**
 * 终端页面（方案 §29 的延伸）。
 *
 * 结构（全部由 Compose 布局，只有终端画布是 Android View）：
 * ```
 * ┌ 工具条（Compose）：发行版 · 模式 · 状态 · 粘贴/清屏/重启
 * ├ 终端画布（AndroidView 包 TerminalView）
 * └ 按键栏（Compose：TerminalKeyBar）
 * ```
 *
 * 关于 TerminalView：它是自绘控件（Canvas 渲染 + 触摸手势 + IME 输入），
 * 这些能力 Compose 没有等价物，因此在 [AndroidView] 中承载。
 * 需要注意的只有两点 —— 焦点属性必须显式打开，以及它不能与 Compose 共享状态，
 * 所以修饰键锁定态用「View 为真值 + Compose 镜像」的方式同步（见下方 LaunchedEffect）。
 *
 * 页面自身不持有会话：会话在 [com.lhzkml.spike.data.terminal.TerminalSessionManager]，
 * 因此切换 Tab / 旋转屏幕都不会丢掉 shell。
 */
@Composable
fun TerminalScreen(
    state: TerminalUiState,
    onViewReady: (TerminalView) -> Unit,
    onViewReleased: () -> Unit,
    onIntent: (TerminalIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 终端画布引用。
    // 用普通数组而非 MutableState 存放：AndroidView 的 factory 在 composition
    // 期间执行，在那里写 state 会导致重组与测量错乱（实测工具条会被挤没）。
    val terminalRef = remember { arrayOfNulls<TerminalView>(1) }

    Column(
        modifier = modifier
            .fillMaxSize()
            // 键盘弹出时整体上移，保证 ⏎ / ESC / CTRL 等键仍可点
            .imePadding(),
    ) {
        TerminalToolbar(state, onIntent)

        AndroidView(
            factory = { ctx ->
                TerminalView(ctx).apply {
                    // 焦点属性必须显式设置：Noxs 在 XML 里用
                    // android:focusable/focusableInTouchMode 打开输入通道，
                    // 代码创建时若遗漏，点按不会弹软键盘。
                    isFocusable = true
                    isFocusableInTouchMode = true
                    terminalRef[0] = this
                    onViewReady(this)
                }
            },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                // TerminalView 的 onDraw 会用 drawColor() 铺满整个画布；
                // Compose 的 AndroidView 默认不裁剪，会让它画到自己的边界之外、
                // 盖住位于其**之前**绘制的 Compose 工具条。必须显式裁剪。
                .clipToBounds(),
        )

        TerminalKeyBar(
            ctrlLatched = state.ctrlLatched,
            altLatched = state.altLatched,
            onByte = { terminalRef[0]?.sendBytes(it) },
            onSpecial = { terminalRef[0]?.sendSpecialKey(it) },
            onToggleCtrl = { onIntent(TerminalIntent.ToggleCtrl) },
            onToggleAlt = { onIntent(TerminalIntent.ToggleAlt) },
        )
    }

    // 修饰键锁定态：ViewModel 是 UI 侧的镜像，TerminalView 才是真值。
    // 用户点按键栏 → state 变化 → 此处写回 View；View 内部消费后通过
    // onLatchesCleared 回调把状态清回 ViewModel（闭环，无重复触发）。
    LaunchedEffect(state.ctrlLatched, state.altLatched) {
        terminalRef[0]?.let { v ->
            if (v.ctrlLatch != state.ctrlLatched) v.ctrlLatch = state.ctrlLatched
            if (v.altLatch != state.altLatched) v.altLatch = state.altLatched
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            terminalRef[0] = null
            onViewReleased()
        }
    }
}

@Composable
private fun TerminalToolbar(state: TerminalUiState, onIntent: (TerminalIntent) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(ToolbarBackground),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${state.distro.displayName} · ${state.modeLabel}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ToolbarContent,
                )
                Text(
                    text = statusText(state),
                    fontSize = 11.sp,
                    color = statusColor(state),
                )
            }

            TextButton(onClick = { onIntent(TerminalIntent.Paste) }) { Text("粘贴", fontSize = 13.sp) }
            TextButton(onClick = { onIntent(TerminalIntent.ClearScreen) }) { Text("清屏", fontSize = 13.sp) }
            TextButton(onClick = { onIntent(TerminalIntent.Restart) }) { Text("重启", fontSize = 13.sp) }
        }
    }
}

private fun statusText(state: TerminalUiState): String = when {
    state.error != null -> state.error
    !state.installed -> "尚未安装该系统，请先在 Runtime 页安装"
    state.running -> "运行中（shell 可在后台保持）"
    state.exitCode != null -> "会话已结束，退出码 ${state.exitCode}"
    else -> "会话未启动"
}

private fun statusColor(state: TerminalUiState): Color = when {
    state.error != null -> Color(0xFFF44336)
    !state.installed -> Color(0xFFFFA726)
    state.running -> Color(0xFF4CAF50)
    else -> Color(0xFF9E9E9E)
}

// 终端页配色：与纯黑的终端画布拉开层次，保证工具条 / 按键栏始终可辨
private val ToolbarBackground = Color(0xFF26262C)
private val ToolbarContent = Color(0xFFE6E1E5)
