package com.lhzkml.spike.feature.terminal.impl

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lhzkml.spike.core.data.di.AppContainer
import com.lhzkml.spike.core.terminal.emulator.TerminalSession
import com.lhzkml.spike.core.terminal.emulator.TerminalSessionClient
import com.lhzkml.spike.core.terminal.view.TerminalView
import com.lhzkml.spike.core.terminal.view.TerminalViewAppearance
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 终端页面的 ViewModel。
 *
 * 职责：
 *  · 持有会话与终端视图的绑定关系（会话本身由 [AppContainer] 的
 *    TerminalSessionManager 持有，因此跨页面/旋转存活）
 *  · 把会话回调转发给终端视图
 *  · 处理页面意图
 *
 * 它**不**解析终端输出、不渲染字符 —— 那是 TerminalView/TerminalEmulator 的事。
 */
class TerminalViewModel(private val container: AppContainer) : ViewModel() {

    companion object {
        /** 终端文字四周留白（dp），与 Noxs 的默认设置一致 */
        private const val DEFAULT_PADDING_DP = 4f
    }

    private val _uiState = MutableStateFlow(TerminalUiState())
    val uiState: StateFlow<TerminalUiState> = _uiState.asStateFlow()

    /** 当前绑定的终端视图（Compose 创建；Activity 重建时会更换实例） */
    private var view: TerminalView? = null

    private val client = object : TerminalSessionClient {
        override fun onTextChanged(session: TerminalSession) {
            view?.onSessionOutputChanged(session)
        }

        override fun onTitleChanged(session: TerminalSession) = Unit

        override fun onBell(session: TerminalSession) = Unit

        override fun onSessionFinished(session: TerminalSession) {
            _uiState.update { it.copy(running = false, exitCode = session.exitCode) }
        }
    }

    init {
        // 终端跟随 Runtime 当前选中的发行版
        viewModelScope.launch {
            container.runtimeRepository.observeState().collect { s ->
                _uiState.update {
                    it.copy(distro = s.selectedDistro, installed = s.selectedInstalled)
                }
            }
        }
    }

    /** Compose 创建出终端视图后调用一次 */
    fun onViewReady(v: TerminalView) {
        view = v
        // 应用外观（主题 / 字体 / 内边距 / 光标）。
        // TerminalView 内部有默认外观，但**不会**主动应用 —— Noxs 在 attach 前后必调
        // applyAppearance，这里对齐该行为，否则字体、内边距等设置不生效。
        v.applyAppearance(TerminalViewAppearance(paddingDp = DEFAULT_PADDING_DP))
        // 视图内部自动解锁修饰键时，同步回 UI 状态
        v.onLatchesCleared = { onLatchesCleared() }
        // 初始同步一次，避免重建后 latch 显示与视图不一致
        v.ctrlLatch = _uiState.value.ctrlLatched
        v.altLatch = _uiState.value.altLatched
        attach()
    }

    /** Compose 释放终端视图（页面销毁 / 切换 Tab） */
    fun onViewReleased() {
        view = null
    }

    // ---- 修饰键锁定态（按键栏）------------------------------------------

    /** 按键栏：切换 Ctrl 锁定态 */
    fun onToggleCtrl() {
        _uiState.update { it.copy(ctrlLatched = !it.ctrlLatched) }
    }

    /** 按键栏：切换 Alt 锁定态 */
    fun onToggleAlt() {
        _uiState.update { it.copy(altLatched = !it.altLatched) }
    }

    /**
     * 终端视图内部消费了修饰键（按键已发出并自动解锁），同步回 UI。
     *
     * 这是 View → ViewModel 的唯一回路：latch 的**真值**在 TerminalView 里
     * （它决定按键如何编码），Compose 只是镜像它以便驱动高亮重绘。
     */
    fun onLatchesCleared() {
        if (_uiState.value.ctrlLatched || _uiState.value.altLatched) {
            _uiState.update { it.copy(ctrlLatched = false, altLatched = false) }
        }
    }

    fun onIntent(intent: TerminalIntent) {
        when (intent) {
            TerminalIntent.Restart -> restart()
            TerminalIntent.ClearScreen -> clearScreen()
            TerminalIntent.Paste -> view?.pasteFromClipboard()
            TerminalIntent.HideKeyboard -> hideKeyboard()
            TerminalIntent.ToggleCtrl -> onToggleCtrl()
            TerminalIntent.ToggleAlt -> onToggleAlt()
            TerminalIntent.ClearError -> _uiState.update { it.copy(error = null) }
        }
    }

    // ------------------------------------------------------------------

    private fun attach() {
        val v = view ?: return
        val target = _uiState.value.distro

        container.terminalSessions.ensureSession(target, client).fold(
            onSuccess = { session ->
                v.attach(session)
                // 首次绑定时主动刷新一屏，避免空白
                v.post { v.onSessionOutputChanged(session) }
                _uiState.update {
                    it.copy(
                        running = session.isRunning,
                        isPty = session.isPty,
                        exitCode = null,
                        error = null,
                    )
                }
            },
            onFailure = { e ->
                _uiState.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
            },
        )
    }

    private fun restart() {
        val v = view ?: return
        val target = _uiState.value.distro
        container.terminalSessions.restart(target, client).fold(
            onSuccess = { session ->
                v.attach(session)
                v.post { v.onSessionOutputChanged(session) }
                _uiState.update {
                    it.copy(
                        running = session.isRunning,
                        isPty = session.isPty,
                        exitCode = null,
                        error = null,
                    )
                }
            },
            onFailure = { e ->
                _uiState.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
            },
        )
    }

    private fun clearScreen() {
        val v = view ?: return
        val session = v.session ?: return
        session.emulator.clearScreen()
        v.onSessionOutputChanged(session)
        v.invalidate()
    }

    private fun hideKeyboard() {
        view?.clearFocus()
    }

    override fun onCleared() {
        // 会话由 AppContainer 持有，这里只断开视图引用
        view = null
        super.onCleared()
    }
}
