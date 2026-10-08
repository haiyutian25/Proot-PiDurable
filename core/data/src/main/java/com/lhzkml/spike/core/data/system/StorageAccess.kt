package com.lhzkml.spike.core.data.system

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * 共享存储授权。
 *
 * `ProotBinds.storage()` 依赖这里的结果 —— 没授权时它返回空，guest 内不会出现
 * 一个读不了的 `/sdcard`。
 *
 * 各版本差异（这也是它必须分叉的原因）：
 *  · **API ≤ 28**：`READ_EXTERNAL_STORAGE` 走普通运行时授权即可。
 *  · **API 29**：配合 `requestLegacyExternalStorage` 沿用旧存储模型，同上授权。
 *  · **API ≥ 30**：普通授权拿不到共享存储的完整访问，必须 `MANAGE_EXTERNAL_STORAGE`
 *    并引导用户去系统设置页手动开启 —— 代码无法直接弹窗授予。
 *
 * 本项目不考虑 Play 上架，因此不做"仅媒体文件"的降级路径。
 */
object StorageAccess {

    /** 当前是否已能完整访问共享存储 */
    fun hasAccess(context: Context): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
            Environment.isExternalStorageManager()
        else ->
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
    }

    /**
     * 发起授权。
     *
     * @return true 表示已弹出系统对话框（结果走 `onRequestPermissionsResult`）；
     *         false 表示跳到了系统设置页，或无需授权 —— 两种情况都要在 `onResume`
     *         里用 [hasAccess] 复查，因为跳设置页不会给结果回调。
     */
    fun request(activity: Activity, requestCode: Int): Boolean {
        if (hasAccess(activity)) return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.fromParts("package", activity.packageName, null),
            )
            runCatching { activity.startActivity(intent) }
                .onFailure {
                    // 个别 ROM 没有这个 App 专属页，退到列表页
                    runCatching {
                        activity.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                }
            false
        } else {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                requestCode,
            )
            true
        }
    }
}
