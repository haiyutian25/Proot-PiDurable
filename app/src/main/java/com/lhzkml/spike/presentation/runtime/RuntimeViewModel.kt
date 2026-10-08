package com.lhzkml.spike.presentation.runtime

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lhzkml.spike.di.AppContainer
import com.lhzkml.spike.domain.model.RuntimeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Runtime Screen 的 ViewModel（方案 §6 / §7）。
 *
 * 职责边界：
 *  · 接收 [RuntimeIntent] → 调 UseCase → 把结果折叠进 [RuntimeUiState]
 *  · 通过观察 Repository 的单一状态源同步 UI
 *  · **不**直接操作 PRoot / 进程 / 文件系统
 */
class RuntimeViewModel(private val container: AppContainer) : ViewModel() {

    private val _uiState = MutableStateFlow(RuntimeUiState())
    val uiState: StateFlow<RuntimeUiState> = _uiState.asStateFlow()

    init {
        // 订阅单一状态源：Runtime 状态变化自动反映到 UI
        viewModelScope.launch {
            container.runtimeRepository.observeState().collect { state ->
                _uiState.update { it.merge(state) }
            }
        }
    }

    fun onIntent(intent: RuntimeIntent) {
        when (intent) {
            is RuntimeIntent.SelectDistro -> launchOperation {
                container.selectDistro(intent.distro)
            }

            is RuntimeIntent.Install -> launchOperation {
                container.installRuntime(intent.distro)
            }

            is RuntimeIntent.Remove -> launchOperation {
                container.removeDistro(intent.distro)
            }

            RuntimeIntent.Start -> launchOperation { container.startRuntime() }
            RuntimeIntent.Stop -> launchOperation { container.stopRuntime() }
            RuntimeIntent.Restart -> launchOperation { container.restartRuntime() }
            RuntimeIntent.Refresh -> refresh()
            RuntimeIntent.ClearError -> _uiState.update { it.copy(error = null) }
            is RuntimeIntent.RunCommand -> runCommand(intent.command)
        }
    }

    /** 把仓库状态合并进 UI 状态（UI 侧字段如 lastOutput 不受影响） */
    private fun RuntimeUiState.merge(s: RuntimeState): RuntimeUiState = copy(
        status = s.status,
        stage = s.stage,
        detail = s.detail,
        progress = s.progress,
        error = s.error,
        selectedDistro = s.selectedDistro,
        installedDistros = s.installedDistros,
        rootfsSha256 = s.rootfsSha256,
    )

    private fun launchOperation(block: suspend () -> Result<Unit>) {
        viewModelScope.launch {
            val result = block()
            result.exceptionOrNull()?.let { e ->
                _uiState.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    private fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.merge(container.runtimeRepository.currentState()) }
        }
    }

    private fun runCommand(raw: String) {
        val args = splitCommandLine(raw)
        if (args.isEmpty()) return
        viewModelScope.launch {
            val result = container.executeInRuntime(args)
            _uiState.update {
                it.copy(
                    lastCommand = args.joinToString(" "),
                    lastOutput = buildString {
                        appendLine("exit=${result.exitCode}  ${result.durationMs}ms")
                        if (result.timedOut) appendLine("(超时)")
                        result.exception?.let { e -> appendLine("exception: $e") }
                        if (result.stdout.isNotBlank()) {
                            appendLine("--- stdout ---")
                            appendLine(result.stdout)
                        }
                        if (result.stderr.isNotBlank()) {
                            appendLine("--- stderr ---")
                            appendLine(result.stderr)
                        }
                    }.trimEnd(),
                )
            }
        }
    }
}

/**
 * 命令行拆分：支持双引号与单引号分组，并支持反斜杠转义。
 *
 * 直接按空格 split 会把 `"/bin/sh -c \"echo hello\""` 拆坏（引号被当成普通字符），
 * 这里做最小可用的 shell 风格解析。
 */
internal fun splitCommandLine(input: String): List<String> {
    val out = mutableListOf<String>()
    val current = StringBuilder()
    var quote: Char? = null
    var escaped = false
    var started = false

    for (ch in input.trim()) {
        when {
            escaped -> {
                current.append(ch)
                escaped = false
                started = true
            }

            ch == '\\' && quote != '\'' -> escaped = true

            quote != null -> {
                if (ch == quote) quote = null else current.append(ch)
                started = true
            }

            ch == '"' || ch == '\'' -> {
                quote = ch
                started = true
            }

            ch.isWhitespace() -> {
                if (started) {
                    out += current.toString()
                    current.setLength(0)
                    started = false
                }
            }

            else -> {
                current.append(ch)
                started = true
            }
        }
    }
    if (started) out += current.toString()
    return out
}
