package com.lhzkml.spike.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.lhzkml.spike.SpikeApplication
import com.lhzkml.spike.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Runtime 的宿主服务（方案 §25）。
 *
 * 它**不是** UI，也不是业务逻辑中心 —— 只负责：
 *  · 让 Runtime 相关长任务在前台服务中存活（避免被系统回收）
 *  · 把 Runtime 状态镜像到通知
 *
 * 状态仍然只有一份：来自 RuntimeRepository 的单一状态源。
 */
class RuntimeService : Service() {

    companion object {
        private const val CHANNEL_ID = "runtime"
        private const val NOTIFICATION_ID = 2001

        const val ACTION_START_RUNTIME = "com.lhzkml.spike.START_RUNTIME"
        const val ACTION_STOP_RUNTIME = "com.lhzkml.spike.STOP_RUNTIME"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var container: AppContainer
    private var stateJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        container = (application as SpikeApplication).container
        createChannel()
        startForegroundSafely("spike", "Runtime 服务已就绪")
        observeRuntimeState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_RUNTIME -> scope.launch { container.startRuntime() }
            ACTION_STOP_RUNTIME -> scope.launch { container.stopRuntime() }
        }
        return START_STICKY
    }

    /** 观察单一状态源并同步到通知（Service 只做镜像，不改业务状态） */
    private fun observeRuntimeState() {
        stateJob = scope.launch {
            container.runtimeRepository.observeState().collectLatest { state ->
                val text = buildString {
                    append(state.status.displayName)
                    state.detail?.let { append(" · ").append(it) }
                }
                notify("spike · Runtime", text)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stateJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Runtime", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun buildNotification(title: String, text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .build()

    private fun startForegroundSafely(title: String, text: String) {
        // 从后台启动前台服务可能被系统拒绝，这里容错
        runCatching { startForeground(NOTIFICATION_ID, buildNotification(title, text)) }
    }

    private fun notify(title: String, text: String) {
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification(title, text))
        }
    }
}
