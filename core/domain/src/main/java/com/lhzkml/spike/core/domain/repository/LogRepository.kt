package com.lhzkml.spike.core.domain.repository

import kotlinx.coroutines.flow.Flow

/** 一条 Runtime 日志 */
data class LogLine(
    val timestampMillis: Long,
    val level: Level,
    val tag: String,
    val message: String,
) {
    enum class Level { INFO, WARN, ERROR }

    fun formatted(): String {
        val t = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
            .format(java.util.Date(timestampMillis))
        return "$t ${level.name.first()} [$tag] $message"
    }
}

/**
 * 日志的单一来源。
 *
 * UI 不直接读文件 —— 它订阅这个 Flow（方案 §5：UI 不直接读取文件）。
 */
interface LogRepository {

    fun observeLogs(): Flow<List<LogLine>>

    fun log(level: LogLine.Level, tag: String, message: String)

    fun info(tag: String, message: String) = log(LogLine.Level.INFO, tag, message)

    fun warn(tag: String, message: String) = log(LogLine.Level.WARN, tag, message)

    fun error(tag: String, message: String) = log(LogLine.Level.ERROR, tag, message)

    fun clear()
}
