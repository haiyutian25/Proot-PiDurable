package com.lhzkml.spike.feature.diagnostics.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lhzkml.spike.core.domain.model.DiagnosticItem

/**
 * Diagnostics 界面（方案 §28）。
 *
 * 展示设备/Runtime 检查结果与实时日志。数据全部来自 ViewModel。
 */
@Composable
fun DiagnosticsScreen(
    state: DiagnosticsUiState,
    onIntent: (DiagnosticsIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row {
            TextButton(onClick = { onIntent(DiagnosticsIntent.Refresh) }) { Text("重新检测") }
            TextButton(onClick = { onIntent(DiagnosticsIntent.ClearLogs) }) { Text("清空日志") }
            if (state.busy) {
                Text("检测中…", fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp))
            }
        }

        Card(modifier = Modifier.fillMaxWidth().weight(1f)) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                Text("检查项", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                state.items.forEach { item -> DiagnosticRow(item) }
            }
        }

        Card(modifier = Modifier.fillMaxWidth().weight(1f)) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("日志（${state.logs.size}）", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF101014))
                        .padding(6.dp),
                ) {
                    itemsIndexed(state.logs) { _, line ->
                        Text(
                            text = line,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = Color(0xFFCFCFCF),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticRow(item: DiagnosticItem) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            text = item.key,
            modifier = Modifier.fillMaxWidth(0.4f),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = item.value,
            fontSize = 13.sp,
            fontWeight = if (item.ok == true) FontWeight.Medium else FontWeight.Normal,
            color = when (item.ok) {
                true -> Color(0xFF4CAF50)
                false -> Color(0xFFF44336)
                null -> MaterialTheme.colorScheme.onSurface
            },
        )
    }
}
