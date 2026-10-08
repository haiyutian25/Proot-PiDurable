package com.lhzkml.spike.core.domain.repository

/** 设备与宿主侧信息（domain 层只暴露纯数据） */
data class SysInfo(
    val androidRelease: String,
    val sdkInt: Int,
    val abi: String,
    val abiList: List<String>,
    val model: String,
    val manufacturer: String,
    val appUid: Int,
    val nativeLibraryDir: String,
    val filesDir: String,
    val cacheDir: String,
    val availableBytes: Long,
    val totalBytes: Long,
)

interface SysInfoRepository {
    fun sysInfo(): SysInfo
}
