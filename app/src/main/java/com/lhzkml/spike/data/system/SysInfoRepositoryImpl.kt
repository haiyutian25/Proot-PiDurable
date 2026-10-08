package com.lhzkml.spike.data.system

import android.content.Context
import android.os.Build
import com.lhzkml.spike.domain.repository.SysInfo
import com.lhzkml.spike.domain.repository.SysInfoRepository

/** 设备与宿主侧信息（data 层负责读取 Android API） */
class SysInfoRepositoryImpl(private val context: Context) : SysInfoRepository {

    override fun sysInfo(): SysInfo {
        val app = context.applicationContext
        val info = app.applicationInfo
        val stat = android.os.StatFs(app.filesDir.absolutePath)
        return SysInfo(
            androidRelease = Build.VERSION.RELEASE ?: "?",
            sdkInt = Build.VERSION.SDK_INT,
            abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "?",
            abiList = Build.SUPPORTED_ABIS?.toList() ?: emptyList(),
            model = Build.MODEL ?: "?",
            manufacturer = Build.MANUFACTURER ?: "?",
            appUid = android.os.Process.myUid(),
            nativeLibraryDir = info.nativeLibraryDir,
            filesDir = app.filesDir.absolutePath,
            cacheDir = app.cacheDir.absolutePath,
            availableBytes = stat.availableBytes,
            totalBytes = stat.totalBytes,
        )
    }
}
