package com.lhzkml.spike.presentation.diagnostics

import com.lhzkml.spike.domain.model.DiagnosticItem

/** Diagnostics 界面状态（方案 §28） */
data class DiagnosticsUiState(
    val items: List<DiagnosticItem> = emptyList(),
    val logs: List<String> = emptyList(),
    val busy: Boolean = false,
    val generatedAtMillis: Long = 0L,
)

/** Diagnostics 意图 */
sealed interface DiagnosticsIntent {
    data object Refresh : DiagnosticsIntent
    data object ClearLogs : DiagnosticsIntent
}
