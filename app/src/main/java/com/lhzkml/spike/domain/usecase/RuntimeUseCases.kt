package com.lhzkml.spike.domain.usecase

import com.lhzkml.spike.domain.model.CommandResult
import com.lhzkml.spike.domain.model.Diagnostics
import com.lhzkml.spike.domain.model.Distro
import com.lhzkml.spike.domain.repository.RuntimeRepository

/**
 * Runtime 相关用例（方案 §8 usecase 包）。
 *
 * 每个用例只做一件事，ViewModel 通过它们间接操作 Repository。
 * Domain 层不依赖 Android，也不接触 ProcessBuilder / 文件系统细节。
 */

class SelectDistroUseCase(private val repository: RuntimeRepository) {
    suspend operator fun invoke(distro: Distro): Result<Unit> = repository.selectDistro(distro)
}

class InstallRuntimeUseCase(private val repository: RuntimeRepository) {
    suspend operator fun invoke(distro: Distro): Result<Unit> = repository.install(distro)
}

class RemoveDistroUseCase(private val repository: RuntimeRepository) {
    suspend operator fun invoke(distro: Distro): Result<Unit> = repository.remove(distro)
}

class StartRuntimeUseCase(private val repository: RuntimeRepository) {
    suspend operator fun invoke(): Result<Unit> = repository.start()
}

class StopRuntimeUseCase(private val repository: RuntimeRepository) {
    suspend operator fun invoke(): Result<Unit> = repository.stop()
}

class RestartRuntimeUseCase(private val repository: RuntimeRepository) {
    suspend operator fun invoke(): Result<Unit> = repository.restart()
}

class GetDiagnosticsUseCase(private val repository: RuntimeRepository) {
    suspend operator fun invoke(): Diagnostics = repository.diagnostics()
}

class ExecuteInRuntimeUseCase(private val repository: RuntimeRepository) {
    suspend operator fun invoke(command: List<String>): CommandResult = repository.execute(command)
}
