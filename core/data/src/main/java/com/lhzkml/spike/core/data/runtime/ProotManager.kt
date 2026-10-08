package com.lhzkml.spike.core.data.runtime

import com.lhzkml.spike.core.domain.model.CommandResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * PRoot 的唯一入口（方案 §11 / §12）。
 *
 * 所有 Linux 侧操作都必须经由此类；UI / ViewModel / UseCase 不得直接构造 ProcessBuilder。
 *
 * ## 参数来源
 *
 * **基础部分**来自 spike 真机实测：
 *  · proot 只能从 nativeLibraryDir 执行（/data 为 nosuid，且 Android 只允许该目录 exec）
 *  · PROOT_LOADER 必须显式指定，否则 proot 会去找硬编码的 Termux 路径
 *  · PATH 必须显式传入 guest，否则 guest 内所有命令 not found
 *  · --link2symlink 必需（app 存储不支持硬链接，dpkg/apk 依赖它）
 *  · --kill-on-exit 必需（宿主退出时清理 guest 进程）
 *
 * **扩展部分**移植自 proot-distro（termux/proot-distro）的权威参数模板
 * `commands/login/proot_cmd.py` —— Termux 生态中长期验证过的组合：
 *  · `--sysvipc`（System V IPC 支持）
 *  · `-L`（修正 lstat，消除 dpkg 的符号链接告警）
 *  · `/dev/urandom → /dev/random`、`/dev/fd`、`/dev/std*`、`/dev/shm` 等绑定
 *    （Android 的 /dev 与常规 Linux 差异较大）
 *  · Android 域路径绑定（见 [ProotBinds]）：共享存储 `/sdcard`、
 *    系统路径 `/apex` `/system` `/vendor` `/linkerconfig`、Dalvik 缓存
 *
 * **未默认启用**：`--kernel-release=…`（伪造内核版本）。proot-distro 把它放在
 * `if not minimal:` 分支，即仅非 minimal 模式才伪造；我们默认取"真实内核"这条路
 * —— `uname -r` 返回手机真实内核，避免用户看到无关版本号而困惑。
 * 真机实测不伪造时 glibc / bash / apt / dpkg 均正常。如遇某发行版要求更高内核号，
 * 打开 [Options.fakeKernel] 即可。
 *
 * 这些扩展通过 [Options] 逐项可关，便于排障时定位到具体参数。
 *
 * proot 与依赖库（libtalloc.so / libandroid-shmem.so）以 jniLibs 形式随 APK 发布，
 * 由系统释放在 nativeLibraryDir —— 这是 Android 允许 app 执行二进制的唯一位置。
 */
class ProotManager(
    private val paths: RuntimePaths,
    /**
     * 共享存储是否已授权。
     *
     * **默认未授权**（安全默认）：未授权时完全不绑定外部存储 —— 不只是不能读写，
     * 而是 guest 内**看不到也进不去** `/sdcard`、`/storage`、`/mnt/sdcard`。
     *
     * 为什么必须这样：宿主上 `/storage/emulated/0` 的权限位是 `drwxrwx--x`，
     * other 位带 `x`，**任何进程都能 cd 穿越**（实测确认）。也就是说仅靠
     * 「绑定但期待内核拒绝」是拦不住穿越的 —— 必须在绑定层就摘干净，
     * 才能让 proot 沙盒在未授权时彻底碰不到外部存储。
     */
    private val storageGranted: () -> Boolean = { false },
) {

    /** 进程句柄：用于管理长驻的 guest 进程 */
    interface ProcessHandle {
        /** 进程标识（Android 的 Process 不暴露子进程 pid，这里用对象标识代替） */
        val id: Int
        fun isAlive(): Boolean
        fun destroy()
        /** 等待退出；返回是否在超时前退出 */
        fun waitFor(timeoutMs: Long): Boolean
    }

    /**
     * 启动 guest 前确保沙盒边界干净。
     *
     * 未授权时清掉 rootfs 内可能残留的外部存储挂载点 —— proot 创建 bind 目标是持久的，
     * 所以「先授权、后撤销」会留下空目录（见 [ProotBinds.cleanupStorageStubs]）。
     * 由调用方在创建会话/执行命令前调用；已授权时是空操作。
     */
    fun ensureSandboxIsolation(rootfs: File) {
        if (storageGranted()) return
        ProotBinds.cleanupStorageStubs(rootfs)
    }

    /** 校验 proot 二进制是否就位（安装前的自检） */
    fun verify(): Result<Unit> = runCatching {
        check(paths.prootBinary.isFile) { "proot 二进制缺失：${paths.prootBinary.absolutePath}" }
        check(paths.prootBinary.canExecute()) { "proot 二进制不可执行" }
        check(paths.prootLoader.isFile) { "proot loader 缺失" }
    }

    /** 构造 proot 进程环境变量 */
    fun buildEnv(extra: Map<String, String> = emptyMap()): Map<String, String> {
        val env = linkedMapOf(
            // proot 及其依赖（libtalloc.so / libandroid-shmem.so）同在 nativeLibraryDir
            "LD_LIBRARY_PATH" to paths.nativeLibraryDir,
            "PROOT_LOADER" to paths.prootLoader.absolutePath,
            "PROOT_LOADER_32" to paths.prootLoader32.absolutePath,
            // 老内核兼容
            "PROOT_NO_SECCOMP" to "1",
            "PROOT_IGNORE_MISSING_BINDINGS" to "1",
            "PROOT_TMP_DIR" to paths.tmp.absolutePath,
            // guest 侧环境（PATH 必须显式给，否则 guest 内命令全 not found）
            "PATH" to GUEST_PATH,
            "TMPDIR" to "/tmp",
            "HOME" to "/root",
            "TERM" to "xterm-256color",
            // 必须是 en_US.UTF-8，**不能用 C.UTF-8**。
            //
            // Ubuntu 26.04 的 coreutils 换成了 uutils（Rust 实现），其 ls 按 locale
            // **名字**判断能否直接输出非 ASCII 文件名，只认 `xx_YY.codeset` 形态 ——
            // 用 `C.UTF-8` / `C.utf8` 会把中文名输出成八进制转义。真机矩阵实测：
            //
            //   LC_ALL=en_US.UTF-8 → 正常      LC_ALL=C.UTF-8     → 转义
            //   LC_ALL=zh_CN.UTF-8 → 正常      LC_ALL=POSIX.UTF-8 → 转义
            //
            // 两个必须同时满足的前提（否则这里传了也没用）：
            //  1. uutils 让 `LC_ALL` **优先于** LANG（实测 LC_ALL=C.UTF-8 + LANG=en_US.UTF-8
            //     仍是转义），所以两个都要给成 en_US.UTF-8；
            //  2. `en_US.UTF-8` 必须**真实存在**。Ubuntu Base 出厂只有 C / C.utf8 / POSIX，
            //     而不存在的 locale 会被 `/etc/profile.d/01-locale-fix.sh` 里那句
            //     `eval $(locale-check C.UTF-8)` 在每个登录 shell 改写成 `LC_ALL=C.UTF-8`，
            //     于是又转义了。→ 由 [GuestFixup.ensureLocale] 复制 glibc 的 C.utf8 数据
            //     使这个名字真正合法，从而 profile 不再改写、警告也消失。
            //
            // Alpine 用 musl + busybox ls，不做这种名字匹配，不受影响。
            "LANG" to "en_US.UTF-8",
            "LC_ALL" to "en_US.UTF-8",
            // 让 256 色终端也能被程序识别为真彩色
            "COLORTERM" to "truecolor",
            // Ubuntu/Debian 的 /root/.bashrc 只在 TERM=xterm-color 时自动启用彩色提示符，
            // 而 xterm-256color 不在其匹配列表（判据为 `case "$TERM" in xterm-color)`），
            // 于是拿到的是无色 PS1。该文件随后会用 `[ -n "$force_color_prompt" ]` 判断，
            // **读的就是环境变量** —— 因此传这个变量即可走彩色分支，无需改动 rootfs。
            "force_color_prompt" to "yes",
        )
        env.putAll(extra)
        return env
    }

    /**
     * PRoot 扩展开关。
     *
     * 默认全开（对齐 proot-distro 的默认行为）；排障时可逐项关闭以定位问题参数。
     */
    data class Options(
        val sysvipc: Boolean = true,
        /**
         * 是否伪造 guest 看到的内核版本（`--kernel-release`）。
         *
         * **默认关闭**，让 `uname -r` 返回真机内核 —— 这与 proot-distro 的 minimal 模式一致
         * （它把该参数放在 `if not minimal:` 分支里，即默认路径是伪造、minimal 路径才真实）。
         * 我们选择默认真实：可避免用户看到一个与手机无关的版本号而困惑。
         *
         * 真机实测（Android 9 / 内核 3.18.120 + Ubuntu Base）：不伪造时 glibc、bash、
         * apt、dpkg 全部正常，无 "kernel too old"。若将来某发行版要求更高的内核号，
         * 打开此开关即可（见 [FAKE_KERNEL_RELEASE]）。
         */
        val fakeKernel: Boolean = false,
        val fixLstat: Boolean = true,
        val devCompat: Boolean = true,
        val shareShm: Boolean = true,
        /** 绑定共享存储（`/sdcard` 等）。需 app 已获存储权限，无权限时自动为空。 */
        val storage: Boolean = true,
        /** 绑定 Android 系统路径（`/apex`、`/system`、`/vendor`、linker 配置）。 */
        val systemPaths: Boolean = true,
        /** 绑定 Dalvik/ART 缓存目录。 */
        val dalvikCache: Boolean = true,
        /**
         * 绑定伪造的 /proc 与 /proc/sys 条目（见 [SysDataStubs]）。
         *
         * 有意**不含 `/proc/version`** —— 那会与「uname -r 显示真机内核」矛盾。
         */
        val sysDataStubs: Boolean = true,
    ) {
        companion object {
            val DEFAULT = Options()
        }
    }

    /**
     * 构造 proot argv（不含具体命令）。
     *
     * @param rootfs 目标发行版的 rootfs 目录（必须显式传入，不再有隐式默认值）
     * @param workDir guest 内的工作目录
     * @param guestCmd 要在 guest 内执行的命令
     */
    fun buildArgv(
        rootfs: File,
        workDir: String = "/",
        guestCmd: List<String>,
        options: Options = Options.DEFAULT,
    ): List<String> = buildList {
        add(paths.prootBinary.absolutePath)

        // ---- 1. proot 扩展（对齐 proot-distro 的 _add_proot_extensions）----
        add("--kill-on-exit")
        add("--link2symlink")
        if (options.sysvipc) add("--sysvipc")
        if (options.fakeKernel) add("--kernel-release=$FAKE_KERNEL_RELEASE")
        if (options.fixLstat) add("-L")

        // ---- 2. 身份：-0 即 --change-id=0:0（fake root）----
        add("-0")

        // ---- 3. rootfs / cwd / 基线绑定 ----
        add("-r"); add(rootfs.absolutePath)
        add("-b"); add("/dev")
        add("-b"); add("/proc")
        add("-b"); add("/sys")

        // ---- 4. Android 设备兼容绑定（对齐 proot-distro 的 _add_termux_dev_binds）----
        if (options.devCompat) {
            // 部分 Android 的 /dev/random 会阻塞，用 urandom 顶替
            add("--bind=/dev/urandom:/dev/random")
            if (!File("/dev/fd").exists()) add("--bind=/proc/self/fd:/dev/fd")
            listOf(0 to "stdin", 1 to "stdout", 2 to "stderr").forEach { (fd, name) ->
                if (!File("/dev/$name").exists() && File("/proc/self/fd/$fd").exists()) {
                    add("--bind=/proc/self/fd/$fd:/dev/$name")
                }
            }
        }

        // ---- 5. /dev/shm（Android 无此目录，用 app 私有目录顶上）----
        if (options.shareShm) add("--bind=${paths.shm.absolutePath}:/dev/shm")

        // ---- 6. 伪造的 /proc 条目（对齐 proot-distro 的 fake_sysdata_bindings）----
        // 放在 /dev /proc /sys 之后，覆盖生效；详见 SysDataStubs 的说明。
        if (options.sysDataStubs) addAll(SysDataStubs.bindArgs(paths.sysdata))

        // ---- 7. Android 域路径（对齐 proot-distro 的 non-minimal 绑定集合）----
        // 共享存储单独把守：未授权时一个都不绑 —— 否则 guest 能 cd 穿越进去
        // （见 storageGranted 的说明）。这是沙盒边界，不是可选的便利功能。
        if (options.storage && storageGranted()) addAll(ProotBinds.storage())
        if (options.systemPaths) addAll(ProotBinds.system)
        if (options.dalvikCache) addAll(ProotBinds.dalvikCache)

        add("-w"); add(workDir)
        addAll(guestCmd)
    }

    /** 执行一条命令并等待结束，收集 stdout/stderr/exit code */
    suspend fun execute(
        guestCmd: List<String>,
        rootfs: File,
        workDir: String = "/",
        extraEnv: Map<String, String> = emptyMap(),
        timeoutSec: Long = DEFAULT_TIMEOUT_SEC,
        options: Options = Options.DEFAULT,
    ): CommandResult = withContext(Dispatchers.IO) {
        runProcess(buildArgv(rootfs, workDir, guestCmd, options), extraEnv, timeoutSec)
    }

    /** 启动一个长驻 guest 进程（例如未来的 Pi Server） */
    suspend fun start(
        guestCmd: List<String>,
        rootfs: File,
        workDir: String = "/",
        extraEnv: Map<String, String> = emptyMap(),
    ): ProcessHandle = withContext(Dispatchers.IO) {
        val argv = buildArgv(rootfs, workDir, guestCmd)
        val pb = ProcessBuilder(argv)
        pb.environment().clear()
        pb.environment().putAll(buildEnv(extraEnv))
        pb.redirectErrorStream(false)
        val process = pb.start()
        // 及时排空输出，避免管道写满导致 guest 阻塞
        Thread { runCatching { process.inputStream.bufferedReader().forEachLine { } } }.start()
        Thread { runCatching { process.errorStream.bufferedReader().forEachLine { } } }.start()

        object : ProcessHandle {
            override val id: Int = System.identityHashCode(process)
            override fun isAlive(): Boolean = runCatching { process.isAlive }.getOrDefault(false)
            override fun destroy() {
                runCatching { process.destroy() }
                if (!runCatching { process.waitFor(3, TimeUnit.SECONDS) }.getOrDefault(false)) {
                    runCatching { process.destroyForcibly() }
                }
            }

            override fun waitFor(timeoutMs: Long): Boolean =
                runCatching { process.waitFor(timeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        }
    }

    /** 停止一个由 [start] 启动的进程 */
    suspend fun stop(handle: ProcessHandle): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { handle.destroy() }
    }

    // ------------------------------------------------------------------

    private fun runProcess(
        argv: List<String>,
        extraEnv: Map<String, String>,
        timeoutSec: Long,
    ): CommandResult {
        val started = System.currentTimeMillis()
        return try {
            val pb = ProcessBuilder(argv)
            pb.environment().clear()
            pb.environment().putAll(buildEnv(extraEnv))

            val process = pb.start()
            val out = StringBuilder()
            val err = StringBuilder()
            val outThread = Thread {
                runCatching { process.inputStream.bufferedReader().forEachLine { out.appendLine(it) } }
            }
            val errThread = Thread {
                runCatching { process.errorStream.bufferedReader().forEachLine { err.appendLine(it) } }
            }
            outThread.start(); errThread.start()

            val finished = process.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!finished) {
                runCatching { process.destroyForcibly() }
                outThread.join(2_000); errThread.join(2_000)
                return CommandResult(
                    exitCode = -1,
                    stdout = out.toString().trimEnd(),
                    stderr = err.toString().trimEnd(),
                    durationMs = System.currentTimeMillis() - started,
                    timedOut = true,
                    exception = "超时（${timeoutSec}s）",
                )
            }
            outThread.join(3_000); errThread.join(3_000)
            CommandResult(
                exitCode = process.exitValue(),
                stdout = out.toString().trimEnd(),
                stderr = err.toString().trimEnd(),
                durationMs = System.currentTimeMillis() - started,
            )
        } catch (t: Throwable) {
            CommandResult(
                exitCode = -1,
                stdout = "",
                stderr = "",
                durationMs = System.currentTimeMillis() - started,
                exception = "${t.javaClass.simpleName}: ${t.message}",
            )
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_SEC = 120L

        /** guest 内的 PATH —— 必须显式传入，否则 guest 内命令全部 not found */
        private const val GUEST_PATH =
            "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/opt/node/bin"

        /**
         * 伪造的内核标识，格式与 proot-distro 的 `--kernel-release` 一致：
         * 用 `\` 分隔 utsname 的六个字段
         * （sysname \ nodename \ release \ version \ machine \ domainname）。
         *
         * 动机：Android 内核版本号（如 3.18.120）会让部分 Linux 程序以
         * "FATAL: kernel too old" 拒绝启动，glibc 也会据此做运行时特性判断。
         */
        private const val FAKE_KERNEL_RELEASE =
            "\\Linux\\localhost\\6.17.0-proot-pi\\" +
                "#1 SMP PREEMPT_DYNAMIC Fri, 10 Oct 2025 00:00:00 +0000\\" +
                "aarch64\\localdomain\\-1\\"
    }
}
