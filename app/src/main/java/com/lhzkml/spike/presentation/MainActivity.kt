package com.lhzkml.spike.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lhzkml.spike.SpikeApplication
import com.lhzkml.spike.di.AppContainer
import com.lhzkml.spike.presentation.diagnostics.DiagnosticsIntent
import com.lhzkml.spike.presentation.diagnostics.DiagnosticsScreen
import com.lhzkml.spike.presentation.diagnostics.DiagnosticsViewModel
import com.lhzkml.spike.presentation.runtime.RuntimeScreen
import com.lhzkml.spike.presentation.runtime.RuntimeViewModel
import com.lhzkml.spike.presentation.terminal.TerminalScreen
import com.lhzkml.spike.presentation.terminal.TerminalViewModel

/**
 * 应用入口。Compose 负责渲染，逻辑全部在 ViewModel。
 *
 * 注意这里没有任何 Runtime 操作 —— 严格遵循 UDF。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as SpikeApplication).container

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                SpikeApp(container)
            }
        }
    }
}

private enum class Tab(val title: String) {
    RUNTIME("Runtime"),
    TERMINAL("终端"),
    DIAGNOSTICS("诊断"),
}

@Composable
private fun SpikeApp(container: AppContainer) {
    var tab by remember { mutableStateOf(Tab.RUNTIME) }

    val factory = remember(container) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
                modelClass.isAssignableFrom(RuntimeViewModel::class.java) ->
                    RuntimeViewModel(container) as T
                modelClass.isAssignableFrom(DiagnosticsViewModel::class.java) ->
                    DiagnosticsViewModel(container) as T
                modelClass.isAssignableFrom(TerminalViewModel::class.java) ->
                    TerminalViewModel(container) as T
                else -> throw IllegalArgumentException("未知 ViewModel: $modelClass")
            }
        }
    }

    val runtimeViewModel: RuntimeViewModel = viewModel(factory = factory)
    val diagnosticsViewModel: DiagnosticsViewModel = viewModel(factory = factory)
    val terminalViewModel: TerminalViewModel = viewModel(factory = factory)

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = {},
                        label = { Text(t.title) },
                    )
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (tab) {
                Tab.RUNTIME -> {
                    val state by runtimeViewModel.uiState.collectAsStateWithLifecycle()
                    RuntimeScreen(
                        state = state,
                        onIntent = runtimeViewModel::onIntent,
                    )
                }

                Tab.TERMINAL -> {
                    val state by terminalViewModel.uiState.collectAsStateWithLifecycle()
                    TerminalScreen(
                        state = state,
                        onViewReady = terminalViewModel::onViewReady,
                        onViewReleased = terminalViewModel::onViewReleased,
                        onIntent = terminalViewModel::onIntent,
                    )
                }

                Tab.DIAGNOSTICS -> {
                    val state by diagnosticsViewModel.uiState.collectAsStateWithLifecycle()
                    DiagnosticsScreen(
                        state = state,
                        onIntent = diagnosticsViewModel::onIntent,
                    )
                }
            }
        }
    }
}
