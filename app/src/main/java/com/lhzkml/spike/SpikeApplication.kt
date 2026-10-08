package com.lhzkml.spike

import android.app.Application
import android.os.SystemClock
import android.util.Log
import com.lhzkml.spike.di.AppContainer

/**
 * 应用入口：持有依赖容器（手工 DI）。
 *
 * 容器在这里创建一次，Activity / Service 通过它取用同一批单例，
 * 保证 Runtime 状态只有一份来源。
 *
 * 注意：这里处于冷启动关键路径（ActivityThread 的 BIND_APPLICATION 消息），
 * 任何耗时 I/O 都会直接推迟首帧，因此容器构造必须保持轻量。
 */
class SpikeApplication : Application() {

    companion object {
        private const val TAG = "Startup"
    }

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        val t0 = SystemClock.elapsedRealtime()
        super.onCreate()
        val tSuper = SystemClock.elapsedRealtime()

        container = AppContainer(this)
        val tContainer = SystemClock.elapsedRealtime()

        Log.i(TAG, "onCreate: super=${tSuper - t0}ms container=${tContainer - tSuper}ms total=${tContainer - t0}ms")
    }
}
