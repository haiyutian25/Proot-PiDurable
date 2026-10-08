package com.lhzkml.spike.data.runtime

import java.io.File

/**
 * Android 侧路径绑定规则 —— 对齐 proot-distro 的 `commands/login/bindings.py`
 * 与 `proot_cmd.py::_add_dalvik_cache_binds`。
 *
 * proot-distro 是 Python CLI 且依赖 Termux 环境（`IS_TERMUX` / `TERMUX_PREFIX`），
 * 无法直接放进 APK 使用，所以这里只移植它的**规则**。
 *
 * 与 proot-distro 的一处有意差异：它用 `stat` 的 world-execute 位（mode 1/5/7）
 * 判断目录能否被 guest 的**非 root 用户**穿过；我们全程用 `-0`（fake root），
 * 且内核校验的是 app 的真实 uid，因此改用 `canRead()/canExecute()` 判断更贴近实际。
 */
object ProotBinds {

    /**
     * 共享存储：`/sdcard`、`/mnt/sdcard`、`/storage` 等。
     *
     * 对齐 `bindings.py::storage_bindings()`。仅在 app 已获得存储权限
     * （`/storage` 可读）时才会产出绑定，否则返回空列表 —— 没权限时强行绑定
     * 会让 guest 内看到一个不可读的 `/sdcard`，反而误导。
     */
    fun storage(): List<String> {
        val bind = mutableListOf<String>()
        val storage = File("/storage")
        if (storage.canRead()) {
            bind += "--bind=/storage"
            val emulated0 = File("/storage/emulated/0")
            if (emulated0.canRead()) {
                bind += "--bind=/storage/emulated/0:/sdcard"
                bind += "--bind=/storage/emulated/0:/mnt/sdcard"
            }
        } else {
            // 退路：逐项尝试常见别名，命中一个即可
            for (path in listOf("/storage/self/primary", "/storage/emulated/0", "/sdcard")) {
                if (!File(path).canRead()) continue
                bind += "--bind=$path:/mnt/sdcard"
                bind += "--bind=$path:/sdcard"
                bind += "--bind=$path:/storage/emulated/0"
                bind += "--bind=$path:/storage/self/primary"
                break
            }
        }
        return bind
    }

    /**
     * Android 系统路径：`/apex`、`/system`、`/vendor` 及 linker 配置。
     *
     * 对齐 `bindings.py::system_bindings()`。部分程序（尤其是动态链接器与
     * APEX 里的库）会去这些路径找东西，绑上可减少奇怪的缺库报错。
     *
     * **结果缓存**：这些路径在进程生命周期内不会变，而探测会在被 SELinux 拒绝的
     * 路径（如本机的 `/odm`）上产生 audit 日志 —— 每次构造 argv 都探一遍会刷屏，
     * 所以只探一次。
     */
    val system: List<String> by lazy { probeSystem() }

    /**
     * Dalvik/ART 缓存。
     *
     * 对齐 `_add_dalvik_cache_binds`。这些是 Android 系统缓存而非 app 私有数据，
     * 正常型 guest 也一并绑定；对纯 Linux 用户基本无感，但绑上无害。
     * 同样缓存（理由见 [system]）。
     */
    val dalvikCache: List<String> by lazy { probeDalvikCache() }

    private fun probeSystem(): List<String> = SYSTEM_PATHS.mapNotNull { raw ->
        val file = File(raw)
        val real = runCatching { file.canonicalFile }.getOrNull() ?: return@mapNotNull null
        val usable = when {
            real.isDirectory -> real.canRead() && real.canExecute()
            real.isFile -> real.canRead()
            else -> false
        }
        if (usable) "--bind=${real.absolutePath}" else null
    }

    private fun probeDalvikCache(): List<String> = DALVIK_PATHS.mapNotNull { raw ->
        val dir = File(raw)
        if (dir.isDirectory && dir.canRead() && dir.canExecute()) "--bind=$raw" else null
    }

    private val SYSTEM_PATHS = listOf(
        "/apex", "/odm", "/product", "/system", "/system_ext", "/vendor",
        "/linkerconfig/ld.config.txt",
        "/linkerconfig/com.android.art/ld.config.txt",
        "/plat_property_contexts", "/property_contexts",
    )

    private val DALVIK_PATHS = listOf(
        "/data/app",
        "/data/dalvik-cache",
        "/data/misc/apexdata/com.android.art/dalvik-cache",
    )
}
