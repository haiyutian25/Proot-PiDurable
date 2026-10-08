package com.lhzkml.spike.data.runtime

import android.content.Context
import com.lhzkml.spike.domain.model.Distro
import java.io.File

/**
 * Runtime 的目录布局。
 *
 * 全部位于 app 私有目录；proot 二进制的唯一合法执行位置是 nativeLibraryDir。
 *
 * 多发行版布局：
 * ```
 * runtime/
 * ├── rootfs/
 * │   ├── alpine/          ← 每个发行版独立目录，可共存
 * │   └── ubuntu/
 * ├── rootfs-alpine.tar.gz ← 归档按发行版分开，各自校验
 * ├── rootfs-ubuntu.tar.gz
 * ├── .installed-alpine    ← 安装标记按发行版分开
 * ├── .installed-ubuntu
 * ├── tmp/                 ← PROOT_TMP_DIR
 * └── lib/                 ← libtalloc 别名等
 * ```
 */
class RuntimePaths(context: Context) {

    private val app = context.applicationContext

    /** APK 的 jniLibs 由系统释放到此目录 —— Android 只允许在此 exec */
    val nativeLibraryDir: String = app.applicationInfo.nativeLibraryDir

    val runtimeRoot: File = File(app.filesDir, "runtime").apply { mkdirs() }

    /** 下载/解压过程中的临时目录（proot 也用它作 PROOT_TMP_DIR） */
    val tmp: File = File(runtimeRoot, "tmp").apply { mkdirs() }

    /** guest 内 /dev/shm 的绑定源（Android 没有 /dev/shm，用私有目录顶上） */
    val shm: File = File(runtimeRoot, "shm").apply { mkdirs() }

    /** 私有库目录：用于 libtalloc.so.2 别名 */
    val lib: File = File(runtimeRoot, "lib").apply { mkdirs() }

    val cache: File = File(app.cacheDir, "runtime").apply { mkdirs() }

    // ---- 多发行版 rootfs ----

    /** 所有发行版 rootfs 的父目录 */
    val rootfsRoot: File = File(runtimeRoot, "rootfs")

    // ---- 选中态持久化 ----

    private val prefs = app.getSharedPreferences("runtime", Context.MODE_PRIVATE)

    /** 当前选中发行版的 id（未设置过则返回 null，由上层回落到默认值） */
    var selectedDistroId: String?
        get() = prefs.getString(KEY_SELECTED_DISTRO, null)
        set(value) = prefs.edit().putString(KEY_SELECTED_DISTRO, value).apply()


    /** 指定发行版的 rootfs 目录 */
    fun rootfsOf(distro: Distro): File = File(rootfsRoot, distro.id)

    /** 指定发行版的归档（下载产物，校验通过后才落到这个名字） */
    fun archiveOf(distro: Distro): File = File(runtimeRoot, "rootfs-${distro.id}.tar.gz")

    /** 指定发行版的安装完成标记 */
    fun markerOf(distro: Distro): File = File(runtimeRoot, ".installed-${distro.id}")

    // ---- proot 家族（均在 nativeLibraryDir） ----

    val prootBinary: File get() = File(nativeLibraryDir, "libproot.so")
    val prootLoader: File get() = File(nativeLibraryDir, "libproot-loader.so")
    val prootLoader32: File get() = File(nativeLibraryDir, "libproot-loader32.so")

    // ---- 旧布局（单发行版 Debian 12）遗留物 ----

    /**
     * 旧布局把 `rootfs/` 目录直接当作 rootfs 使用（其下直接是 bin/ etc/）。
     * 多发行版布局下 `rootfs/` 是父目录，其下才是 `<distro>/bin/…`。
     *
     * 该判断用于一次性清理：旧数据既不再被使用，还会白占约 300 MB。
     */
    fun hasLegacyRootfs(): Boolean = File(rootfsRoot, "bin/sh").exists()

    fun legacyArtifacts(): List<File> = listOf(
        File(runtimeRoot, "rootfs.tar.gz"),
        File(runtimeRoot, ".installed"),
    )

    private companion object {
        const val KEY_SELECTED_DISTRO = "selected_distro"
    }
}
