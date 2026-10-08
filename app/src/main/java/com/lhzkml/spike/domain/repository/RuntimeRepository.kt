package com.lhzkml.spike.domain.repository

import com.lhzkml.spike.domain.model.CommandResult
import com.lhzkml.spike.domain.model.Diagnostics
import com.lhzkml.spike.domain.model.Distro
import com.lhzkml.spike.domain.model.RuntimeState
import kotlinx.coroutines.flow.Flow

/**
 * Runtime 仓库（方案 §9）。
 *
 * Domain 层只描述业务：这里不出现 Context / ProcessBuilder / 文件路径 / PRoot 命令细节。
 *
 * 多发行版语义：
 *  · 同一时刻只有一个「选中发行版」，[install] / [start] / [execute] 都以它为目标
 *  · 多个发行版可以同时处于已安装状态，互不影响
 */
interface RuntimeRepository {

    /** 单一状态源：UI 与 Service 都观察它 */
    fun observeState(): Flow<RuntimeState>

    /** 读取当前快照（非阻塞） */
    fun currentState(): RuntimeState

    /** 选择发行版（持久化） */
    suspend fun selectDistro(distro: Distro): Result<Unit>

    /** 安装指定发行版的 rootfs（下载 → 校验 → 解压），并自动选中它 */
    suspend fun install(distro: Distro): Result<Unit>

    /** 删除指定发行版的 rootfs 与归档 */
    suspend fun remove(distro: Distro): Result<Unit>

    /** 启动 Runtime（PRoot → Linux → Node 验证），目标是当前选中发行版 */
    suspend fun start(): Result<Unit>

    /** 停止 Runtime */
    suspend fun stop(): Result<Unit>

    /** 重启 */
    suspend fun restart(): Result<Unit> = run {
        stop().getOrNull()
        start()
    }

    /** 采集诊断信息 */
    suspend fun diagnostics(): Diagnostics

    /** 在 Runtime 内执行一条命令（供高级诊断使用） */
    suspend fun execute(command: List<String>): CommandResult

    /** 清空日志缓冲 */
    fun clearLogs()
}
