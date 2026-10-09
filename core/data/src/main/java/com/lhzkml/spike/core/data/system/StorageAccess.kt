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
 * `ProotManager` 的 `storageGranted` 依赖这里的结果 —— 没拿到**完整**访问时
 * [ProotBinds.storage] 一个都不绑，guest 内不会出现一个半残的 `/sdcard`。
 *
 * 各版本差异（这也是它必须分叉的原因）：
 *  · **API ≤ 28**：`READ` + `WRITE_EXTERNAL_STORAGE` 都要运行时授权（见 [hasAccess]）。
 *  · **API 29**：配合 `requestLegacyExternalStorage` 沿用旧存储模型，同上授权。
 *  · **API ≥ 30**：普通授权拿不到共享存储的完整访问，必须 `MANAGE_EXTERNAL_STORAGE`
 *    并引导用户去系统设置页手动开启 —— 代码无法直接弹窗授予。
 *
 * 本项目不考虑 Play 上架，因此不做"仅媒体文件"的降级路径。
 */
object StorageAccess {

    /**
     * 当前是否已能**完整**访问共享存储（可读且可写）。
     *
     * **为什么这里必须查 WRITE，而不能只看 READ**：Android 是按 uid 的权限给
     * `/storage/emulated` **挂不同变体**的。本机（Android 9）实测三个变体同时存在，
     * 系统按权限挑一个挂到 `/storage/emulated`：
     *
     * ```
     *   只授 READ   → /mnt/runtime/read/emulated    gid=9997,mask=23  ← 只读
     *   授了 WRITE  → /mnt/runtime/write/emulated   gid=9997,mask=7   ← 可写
     *   shell/root  → /mnt/runtime/default/emulated gid=1015,mask=6
     * ```
     *
     * 所以"只授 READ"的后果不是"权限少一点"，而是**guest 内的 `/sdcard` 变成只读**：
     * `ls` 正常、一写就 `Permission denied`。真机上这就是报上来的现象
     * —— "外部目录权限打开了，却只能可读不可写"，用户几乎无法自行归因。
     *
     * 另外实测（同一进程、未重启）：`pm grant WRITE` 之后系统会**立刻重新挂载**
     * 该进程命名空间里的这一项（`mask=23` → `mask=7`）。也就是说 Android 9 会在权限
     * 变更时更新已有进程的视图，"重启 App 也没用"的真实原因不是需要重启，
     * 而是 WRITE 从来没有被授予过。
     */
    fun hasAccess(context: Context): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
            Environment.isExternalStorageManager()
        else ->
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
    }

    /**
     * 发起授权。
     *
     * API ≤ 29 **必须同时申请 READ 与 WRITE**：两者同属 STORAGE 权限组，系统对话框
     * 一次授予整组；只申请 READ 就会得到上面说的只读视图（见 [hasAccess]）。
     *
     * 对于"已经授过 READ、当时申请里没有 WRITE"的老用户：该组已授权时系统通常会
     * 直接补授、不再弹窗，点一次「授予」即可恢复可写；若 WRITE 曾被永久拒绝，
     * 则需到系统设置页手动打开。
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
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ),
                requestCode,
            )
            true
        }
    }
}
