package com.lhzkml.spike.core.data.runtime

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
     * 共享存储：`/storage`、`/sdcard`、`/mnt/sdcard`。
     *
     * 对齐 `bindings.py::storage_bindings()`。
     *
     * **调用前提：已获得存储授权。** 未授权时 `ProotManager` 根本不会调它 ——
     * 这是刻意的沙盒边界，不是可省的便利功能：
     *
     *  · 宿主上 `/storage` 是 `drwxr-xr-x`、`/storage/emulated/0` 对 other 位是 `--x`，
     *    也就是说**任何进程都能 `cd` 穿越进去**，无需任何授权。
     *  · 所以「绑上去、等内核拒绝」是拦不住穿越的 —— 未授权时必须一个都不绑，
     *    guest 内才会连 `/sdcard`、`/storage` 这些路径都不存在。
     *
     * 授权之后，能读到的内容由 sdcardfs 的 `derive_gid`/`multiuser` 按进程权限
     * 动态判定（有权限者派生为 `sdcard_rw`(1015) 组）。
     *
     * 三个别名一并指向同一目标，保证 guest 内路径行为一致。
     */
    fun storage(): List<String> {
        val bind = mutableListOf<String>()
        val storage = File("/storage")

        if (storage.isDirectory) {
            bind += "--bind=/storage"
            val emulated0 = File("/storage/emulated/0")
            if (emulated0.isDirectory) {
                bind += "--bind=/storage/emulated/0:/sdcard"
                bind += "--bind=/storage/emulated/0:/mnt/sdcard"
            }
            return bind
        }

        // 退路：个别 ROM 没有 /storage，逐个试常见别名，命中一个即可
        for (path in listOf("/storage/self/primary", "/storage/emulated/0", "/sdcard")) {
            if (!File(path).isDirectory) continue
            bind += "--bind=$path:/mnt/sdcard"
            bind += "--bind=$path:/sdcard"
            bind += "--bind=$path:/storage/emulated/0"
            bind += "--bind=$path:/storage/self/primary"
            break
        }
        return bind
    }

    /**
     * 遮蔽 Android 的 IPC 设备节点：`/dev/binder`、`/dev/hwbinder`、`/dev/vndbinder`。
     *
     * **为什么要遮**：沙盒进程与宿主 App 同 uid、同 SELinux 域，能打开这些节点就等于
     * **以 App 的身份与系统服务通信** —— 拿不到超出 app 的权限（不是提权），但"沙盒里的
     * 代码能代 App 说话"本身就不该留着；而且将来若给 app 加了敏感权限（定位、联系人、
     * 通知读取…），沙盒会顺着 binder 一并继承。
     *
     * 真机实测（未遮蔽时，guest 内 `ls -l`）：`/dev/binder` = `10, 55`、`/dev/hwbinder`
     * = `10, 54`（真实设备号，可打开），`/dev/vndbinder` 已经是 `Permission denied`。
     *
     * **怎么遮**：proot 的 `-b` 是**后绑覆盖先绑**（本项目 `SysDataStubs` 的伪造 /proc
     * 条目就依赖这条语义），所以在 `-b /dev` 之后把节点指向 `/dev/null` 即可。遮蔽后
     * guest 内看到的设备号变成 `1, 3`，`open` 拿到的只是空设备，任何 binder ioctl 必然
     * `ENOTTY` —— 对任何 binder 使用者等价于设备不可用。
     *
     * 为什么不改成"白名单逐个绑设备节点"：那会牵动 /dev 的整体绑定方式，各类程序对
     * /dev 的依赖很杂、破坏面大；而它并不改变信任边界（其余节点本来就被 SELinux 与
     * 文件权限挡在 app uid 之外）。
     *
     * 只遮蔽真机上**存在**的节点 —— proot 对不存在的 bind 源会直接报错。
     */
    fun maskAndroidIpcDevices(): List<String> =
        ANDROID_IPC_DEVICES.filter { File(it).exists() }.map { "--bind=/dev/null:$it" }

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

    /**
     * 清掉 rootfs 内可能残留的外部存储挂载点。
     *
     * 为什么需要：proot 处理 `--bind=src:dst` 时，**若 dst 在 rootfs 内不存在就会创建它**，
     * 而且创建是写进 rootfs 的、**持久保留**。于是「先授权、后撤销」会留下一批空目录
     * （实测 `/sdcard`、`/storage`、`/mnt/sdcard` 权限 `d---------`），
     * 让 guest 以为外部存储还在。虽然里面没有任何内容（`/storage/emulated/0` 确实不存在），
     * 但沙盒隔绝应当连路径痕迹都不留。
     *
     * **只删空目录**：非空说明有真实内容，宁可保留也不能误删。
     */
    fun cleanupStorageStubs(rootfs: File) {
        for (rel in STORAGE_STUBS) {
            val dir = File(rootfs, rel)
            if (!dir.isDirectory) continue
            if (dir.list()?.isEmpty() == true) runCatching { dir.delete() }
        }
    }

    /** Android 的 binder 家族设备节点（真机上存在的才会被遮蔽）。 */
    private val ANDROID_IPC_DEVICES = listOf("/dev/binder", "/dev/hwbinder", "/dev/vndbinder")

    private val STORAGE_STUBS = listOf(
        "sdcard", "storage", "mnt/sdcard", "mnt/runtime",
        "storage/emulated", "storage/emulated/0", "storage/self",
    )

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
