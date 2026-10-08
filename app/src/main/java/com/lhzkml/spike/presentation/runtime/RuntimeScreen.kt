package com.lhzkml.spike.presentation.runtime

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lhzkml.spike.domain.model.Distro
import com.lhzkml.spike.domain.model.RuntimeStage
import com.lhzkml.spike.domain.model.RuntimeStatus

/**
 * Runtime 界面（方案 §29）。
 *
 * 只做两件事：渲染 [RuntimeUiState]、发出 [RuntimeIntent]。
 * 不启动 PRoot、不读文件、不发 HTTP。
 */
@Composable
fun RuntimeScreen(
    state: RuntimeUiState,
    onIntent: (RuntimeIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusCard(state)

        DistroCard(state, onIntent)

        ActionCard(state, onIntent)

        if (state.rootfsInstalled) {
            CommandCard(state, onIntent)
        }

        state.error?.let { ErrorCard(it) { onIntent(RuntimeIntent.ClearError) } }
    }
}

@Composable
private fun StatusCard(state: RuntimeUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = state.status.displayName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = statusColor(state.status),
                )
                if (state.status == RuntimeStatus.INSTALLING ||
                    state.status == RuntimeStatus.STARTING
                ) {
                    Spacer(Modifier.padding(horizontal = 6.dp))
                    CircularProgressIndicator(
                        modifier = Modifier.height(18.dp).fillMaxWidth(0.06f),
                        strokeWidth = 2.dp,
                    )
                }
            }

            KeyValue("发行版", state.selectedDistro.displayName)
            KeyValue("RootFS", if (state.rootfsInstalled) "已安装" else "未安装")
            KeyValue(
                "已装系统",
                state.installedDistros.joinToString { it.displayName }.ifBlank { "—" },
            )
            state.rootfsSha256?.let { KeyValue("SHA256", it.take(16) + "…") }
            KeyValue("阶段", state.stage.label)
            state.detail?.let { KeyValue("当前", it) }

            state.progress?.let { p ->
                LinearProgressIndicator(
                    progress = { p },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun DistroCard(state: RuntimeUiState, onIntent: (RuntimeIntent) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("发行版", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "rootfs 来源为发行版官方站点，SHA256 在应用内固定校验",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            state.distros.forEach { distro ->
                DistroRow(
                    distro = distro,
                    selected = distro == state.selectedDistro,
                    installed = distro in state.installedDistros,
                    enabled = !state.isBusy,
                    onClick = { onIntent(RuntimeIntent.SelectDistro(distro)) },
                )
            }

            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onIntent(RuntimeIntent.Install(state.selectedDistro)) },
                    enabled = state.canInstall,
                ) { Text(if (state.rootfsInstalled) "重装" else "安装") }

                OutlinedButton(
                    onClick = { onIntent(RuntimeIntent.Remove(state.selectedDistro)) },
                    enabled = state.canRemove,
                ) { Text("删除") }
            }
        }
    }
}

@Composable
private fun DistroRow(
    distro: Distro,
    selected: Boolean,
    installed: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null, enabled = enabled)
            Spacer(Modifier.width(4.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = distro.displayName,
                    fontSize = 14.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
                Text(
                    text = distro.summary,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (installed) {
                Text(text = "已安装", fontSize = 11.sp, color = Color(0xFF4CAF50))
            }
        }
    }
}

@Composable
private fun ActionCard(state: RuntimeUiState, onIntent: (RuntimeIntent) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("操作", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onIntent(RuntimeIntent.Start) },
                    enabled = state.canStart,
                ) { Text("启动") }

                OutlinedButton(
                    onClick = { onIntent(RuntimeIntent.Stop) },
                    enabled = state.canStop,
                ) { Text("停止") }

                OutlinedButton(
                    onClick = { onIntent(RuntimeIntent.Restart) },
                    enabled = state.canRestart,
                ) { Text("重启") }
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = { onIntent(RuntimeIntent.Refresh) }) { Text("刷新") }
        }
    }
}

@Composable
private fun CommandCard(state: RuntimeUiState, onIntent: (RuntimeIntent) -> Unit) {
    var input by remember { mutableStateOf("/bin/sh -c \"echo hello\"") }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("在 Runtime 内执行", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Node 等软件可在 guest 内自行安装（${state.selectedDistro.displayName} 的包管理器：${
                    packageManagerHint(state.selectedDistro)
                }）",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("参数（不含 proot 前缀）") },
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { onIntent(RuntimeIntent.RunCommand(input)) }) { Text("执行") }

            state.lastCommand?.let { cmd ->
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "$ $cmd",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = state.lastOutput.orEmpty(),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    maxLines = 30,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onErrorContainer,
                fontSize = 12.sp,
            )
            TextButton(onClick = onDismiss) { Text("知道了") }
        }
    }
}

@Composable
private fun KeyValue(key: String, value: String) {
    Row {
        Text(
            text = key,
            modifier = Modifier.fillMaxWidth(0.28f),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, fontSize = 13.sp)
    }
}

private fun packageManagerHint(distro: Distro): String = when (distro) {
    Distro.ALPINE -> "apk add"
    Distro.UBUNTU -> "apt install"
}

private fun statusColor(status: RuntimeStatus): Color = when (status) {
    RuntimeStatus.RUNNING -> Color(0xFF4CAF50)
    RuntimeStatus.ERROR -> Color(0xFFF44336)
    RuntimeStatus.NOT_INSTALLED -> Color(0xFF9E9E9E)
    RuntimeStatus.INSTALLING, RuntimeStatus.STARTING, RuntimeStatus.STOPPING -> Color(0xFFFFA726)
    RuntimeStatus.STOPPED -> Color(0xFF78909C)
}
