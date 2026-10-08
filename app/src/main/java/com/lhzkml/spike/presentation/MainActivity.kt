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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lhzkml.spike.SpikeApplication
import com.lhzkml.spike.di.AppContainer
import com.lhzkml.spike.presentation.diagnostics.DiagnosticsIntent
import com.lhzkml.spike.presentation.diagnostics.DiagnosticsScreen
import com.lhzkml.spike.presentation.diagnostics.DiagnosticsViewModel
import com.lhzkml.spike.presentation.runtime.RuntimeIntent
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

    // 存储授权在 API ≥ 30 是「跳系统设置页」，没有结果回调 ——
    // 用户从设置页返回时会走 onResume，所以在这里复查一次，让状态回到单一来源。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, runtimeViewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                runtimeViewModel.onIntent(RuntimeIntent.RefreshStorage)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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
