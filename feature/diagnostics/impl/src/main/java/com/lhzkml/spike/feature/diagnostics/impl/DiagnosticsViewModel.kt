package com.lhzkml.spike.feature.diagnostics.impl

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lhzkml.spike.core.data.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Diagnostics ViewModel（方案 §28）。
 *
 * 所有数据都经 UseCase / Repository 获取；UI 不执行 shell。
 */
class DiagnosticsViewModel(private val container: AppContainer) : ViewModel() {

    private val _uiState = MutableStateFlow(DiagnosticsUiState())
    val uiState: StateFlow<DiagnosticsUiState> = _uiState.asStateFlow()

    init {
        // 日志来自 LogRepository 的 Flow，UI 不读文件
        viewModelScope.launch {
            container.logs.observeLogs().collect { lines ->
                _uiState.update { it.copy(logs = lines.map { l -> l.formatted() }) }
            }
        }
        refresh()
    }

    fun onIntent(intent: DiagnosticsIntent) {
        when (intent) {
            DiagnosticsIntent.Refresh -> refresh()
            DiagnosticsIntent.ClearLogs -> container.logs.clear()
        }
    }

    private fun refresh() {
        _uiState.update { it.copy(busy = true) }
        viewModelScope.launch {
            val diagnostics = runCatching { container.getDiagnostics() }
                .getOrElse { com.lhzkml.spike.core.domain.model.Diagnostics() }
            _uiState.update {
                it.copy(
                    items = diagnostics.items,
                    busy = false,
                    generatedAtMillis = diagnostics.generatedAtMillis,
                )
            }
        }
    }
}
