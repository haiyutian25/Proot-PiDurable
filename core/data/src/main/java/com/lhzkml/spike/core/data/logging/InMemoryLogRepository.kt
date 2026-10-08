package com.lhzkml.spike.core.data.logging

import com.lhzkml.spike.core.domain.repository.LogLine
import com.lhzkml.spike.core.domain.repository.LogRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 内存日志仓库。
 *
 * UI 通过 Flow 观察日志，不直接读文件（方案 §5）。
 * 采用环形缓冲，避免长时间运行导致内存增长。
 */
class InMemoryLogRepository(
    private val capacity: Int = 2_000,
) : LogRepository {

    private val buffer = MutableStateFlow<List<LogLine>>(emptyList())

    override fun observeLogs(): Flow<List<LogLine>> = buffer.asStateFlow()

    override fun log(level: LogLine.Level, tag: String, message: String) {
        val line = LogLine(
            timestampMillis = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message,
        )
        // 同步写 logcat，便于 adb 侧排障
        runCatching {
            when (level) {
                LogLine.Level.INFO -> android.util.Log.i("Spike/$tag", message)
                LogLine.Level.WARN -> android.util.Log.w("Spike/$tag", message)
                LogLine.Level.ERROR -> android.util.Log.e("Spike/$tag", message)
            }
        }
        buffer.update { current ->
            val next = current + line
            if (next.size > capacity) next.takeLast(capacity) else next
        }
    }

    override fun clear() {
        buffer.value = emptyList()
    }
}
