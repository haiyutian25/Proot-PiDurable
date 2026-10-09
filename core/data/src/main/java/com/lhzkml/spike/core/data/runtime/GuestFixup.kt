package com.lhzkml.spike.core.data.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import com.lhzkml.spike.core.domain.repository.LogRepository
import java.io.File
import java.util.TimeZone

/**
 * rootfs 的准备与修补。四项各自独立、幂等，可安全重复调用。
 *
 *  · [writeResolvConf] / [writeHosts] —— 对齐 proot-distro 的 `helpers/rootfs.py`
 *  · [writeAptSandboxOff] —— Android 特有（app 无法 `setuid(_apt)`）
 *  · [ensureLocale] —— Android + uutils 特有（见该方法说明）
 *  · [ensureTimezoneInGuest] —— Android 特有，**在 guest 内跑脚本**（见该方法说明）
 *
 * **为什么必须写 DNS**：Android 的 DNS 由 netd 管理，**宿主上根本没有 `/etc/resolv.conf`**
 * （实测确认）。而 guest 内的 glibc / musl 只会去读 `/etc/resolv.conf`，
 * 于是 `apt` / `apk` 解析不了域名、装不了包。proot-distro 的解决办法是安装完
 * 直接把这个文件写死进 rootfs，这里照做。
 *
 * 虽然名义上是"安装后"修补，但 [ensureLocale] 在**每次终端启动**时也会被调用一次
 * （代价仅两次 `isDirectory`，已就绪时立即返回）—— 这样修 bug 之前装好的 rootfs
 * 无需重装即可恢复。
 */
class GuestFixup(
    private val dns: DnsProvider,
    private val logs: LogRepository,
    /**
     * 用于**在 guest 内执行收尾脚本**（时区等需要发行版自身能力的调整）。
     * 为 null 时跳过脚本 —— 便于纯离线/测试场景。
     */
    private val proot: ProotManager? = null,
) {

    /**
     * 写入 `/etc/resolv.conf`、`/etc/hosts`，并关闭 apt 的 sandbox。
     *
     * @return 实际写入的 DNS 列表（便于日志与排障）
     */
    fun apply(rootfs: File): List<String> {
        val servers = dns.servers()
        writeResolvConf(rootfs, servers)
        writeHosts(rootfs)
        writeAptSandboxOff(rootfs)
        ensureLocale(rootfs)
        // 时区改走「guest 内脚本」（Alpine 需要 apk add tzdata 才谈得上设时区），
        // 它是 suspend 且可能联网，由安装流程单独 await 调用。
        return servers
    }

    /**
     * 让 `en_US.UTF-8` 成为**真正合法**的 locale。
     *
     * **为什么必须做**：Ubuntu 26.04 的 coreutils 换成了 uutils（Rust 实现），它的 `ls`
     * 在决定"能否直接输出非 ASCII 文件名"时**只看 locale 名字是否形如 `xx_YY.codeset`**：
     * `en_US.UTF-8` 认，而 `C.UTF-8` / `C.utf8` / `POSIX.UTF-8` 一律不认 —— 于是把中文
     * 文件名按八进制转义输出。真机矩阵实测（强制 `--quoting-style=shell-escape`）：
     *
     * ```
     *   LC_ALL=en_US.UTF-8  -> 正常      LC_ALL=C.UTF-8      -> 转义
     *   LC_ALL=zh_CN.UTF-8  -> 正常      LC_ALL=POSIX.UTF-8  -> 转义
     *   LC_ALL=en_US.utf8   -> 正常      LC_ALL=x.UTF-8      -> 转义
     * ```
     *
     * 麻烦在于 Ubuntu Base 里 `en_US.UTF-8` **并不存在**（`locale -a` 只有
     * C / C.utf8 / POSIX），于是 `/etc/profile.d/01-locale-fix.sh` 里那句
     * `eval $(/usr/bin/locale-check C.UTF-8)` 会在**每个登录 shell** 中把非法值
     * 改写成 `LC_ALL=C.UTF-8`，uutils 就又认不出了：
     *
     * ```
     *   bash: warning: setlocale: LC_ALL: cannot change locale (en_US.UTF-8)
     *   root@localhost: # ls /tmp/loc
     *   ''$'\344\270\255\346\226\207\346\226\207\344\273\266''.txt'
     * ```
     *
     * 想从环境变量层面绕开是行不通的：`locale-check` 专门改写非法值，而 uutils 又让
     * `LC_ALL` 优先于 `LANG`（实测 `LC_ALL=C.UTF-8 + LANG=en_US.UTF-8` 仍是转义）。
     *
     * **根本解法是让 `en_US.UTF-8` 合法**。glibc 的 `C.utf8` 是编译好的真数据，
     * 直接复制一份改名为 `en_US.UTF-8` 即可 —— 复制后 `locale charmap` 返回 `UTF-8`，
     * `locale-check` 不再改写，bash 警告消失，uutils 也正常。真机实测（交互式登录 shell）：
     *
     * ```
     *   LANG=[en_US.UTF-8]  LC_ALL=[en_US.UTF-8]
     *   ls /tmp/loc -> readme.md  中文文件.txt  测试目录_abc
     * ```
     *
     * 幂等：已存在目标目录则直接返回，因此终端每次启动都可以廉价地调一次 ——
     * 这样「装好之后才修好这个 bug」的老 rootfs 无需重装即可恢复。
     *
     * Alpine 用 musl + busybox `ls`，不做这种名字匹配，也没有 `usr/lib/locale`，
     * 此处自然跳过。
     *
     * @return 是否已就绪（本来就合法，或本次补齐成功）
     */
    fun ensureLocale(rootfs: File): Boolean {
        val dir = File(rootfs, LOCALE_DIR)
        if (!dir.isDirectory) return false

        val target = File(dir, UTF8_LOCALE)
        if (target.isDirectory) return true

        val src = UTF8_LOCALE_SOURCES.map { File(dir, it) }.firstOrNull { it.isDirectory }
        if (src == null) {
            logs.warn(TAG, "未找到可复制的 UTF-8 locale（找过 $UTF8_LOCALE_SOURCES），跳过")
            return false
        }

        return runCatching {
            src.copyRecursively(target, overwrite = true)
            normalizePerms(target)
        }.fold(
            onSuccess = {
                logs.info(TAG, "已补齐 $UTF8_LOCALE（源 ${src.name}）：修复 uutils ls 的中文八进制转义")
                true
            },
            onFailure = { e ->
                // 半成品目录比缺失更糟（glibc 可能读到损坏数据），失败就清掉。
                runCatching { target.deleteRecursively() }
                logs.warn(TAG, "补齐 $UTF8_LOCALE 失败（不影响其他功能）：${e.message}")
                false
            },
        )
    }

    /**
     * 让 guest 的时区跟随 **Android 系统设置** —— 做法是**在 guest 内跑一段脚本**
     * （对齐 Kai：安装完成后在沙盒里执行命令，而不是只在宿主侧改文件）。
     *
     * **为什么必须进 guest 跑**：Alpine 的 minirootfs **不含 tzdata**
     * （`/usr/share/zoneinfo` 整个不存在），宿主侧怎么写文件都没用 —— 只能在 guest 内
     * 用它自己的包管理器（`apk add tzdata`）装上，才谈得上设时区。脚本见 [setupScript]。
     *
     * **取值来自系统而不是写死**：`TimeZone.getDefault().id` 就是 Android 的
     * `persist.sys.timezone`（真机实测 `Asia/Shanghai`）。
     *
     * **什么时候跑**：
     *  · 安装 rootfs 后必定跑一次（`force = true`，带进度）
     *  · 每次终端启动时**只在真的不一致才跑** —— 先读 `/etc/timezone` 比一下，
     *    一致就直接返回，不付 proot 起进程的代价。这样用户在系统设置里改了时区、
     *    或跨时区旅行，新会话就自动跟上。
     *
     * 脚本还会把自身写到 guest 的 `/usr/local/bin/proot-pi-setup.sh`，所以你也可以在
     * 终端里直接手动重跑：`sh /usr/local/bin/proot-pi-setup.sh`。
     *
     * 另有 `ProotManager.buildEnv()` 传的 `TZ` 环境变量，两者互补：TZ 覆盖「优先看环境
     * 变量」的程序，文件覆盖「只读 /etc/localtime」的程序。
     *
     * @param force 为 true 时跳过"已一致"的快速判断，无条件执行脚本
     * @return 是否已配置（或本来就已经正确）
     */
    suspend fun ensureTimezoneInGuest(rootfs: File, force: Boolean = false): Boolean {
        val prootManager = proot
        if (prootManager == null) {
            logs.warn(TAG, "未注入 ProotManager，跳过 guest 收尾脚本")
            return false
        }

        val zoneId = TimeZone.getDefault().id
        if (!SAFE_ZONE_ID.matches(zoneId)) {
            // `GMT+08:00` 这类名字没有对应的 zoneinfo 文件，且含 `:` 会破坏脚本结构
            logs.warn(TAG, "时区标识不可用（$zoneId），跳过 guest 收尾脚本")
            return false
        }
        if (!force && readZone(rootfs) == zoneId) return true

        // 脚本落点必须是 rootfs 内的**真实**目录：rootfs 里的绝对符号链接
        // （例如 `/usr/local -> /usr`）一旦被宿主的 File API 顺着解析，就会写穿到宿主系统上。
        // canonicalPath 会解开符号链接，用它做归属校验 —— 与 Kai 的 safeChild 同一思路。
        val target = File(rootfs, GUEST_SCRIPT_REL)
        target.parentFile?.mkdirs()
        val rootCanon = rootfs.canonicalPath
        if (!target.canonicalPath.startsWith("$rootCanon/")) {
            logs.warn(TAG, "收尾脚本落点解析到 rootfs 之外（${target.canonicalPath}），跳过")
            return false
        }
        target.writeText(setupScript(zoneId))

        val result = prootManager.execute(
            guestCmd = listOf("/bin/sh", GUEST_SCRIPT_PATH),
            rootfs = rootfs,
            workDir = "/",
            timeoutSec = GUEST_SETUP_TIMEOUT_SEC,
        )
        result.stdout.trim().lines().filter { it.isNotBlank() }.forEach { logs.info(TAG, it) }
        if (result.stderr.isNotBlank()) {
            logs.warn(TAG, "收尾脚本 stderr：${result.stderr.trim().take(300)}")
        }

        // 以落盘结果为准，而不是脚本的退出码 —— 与包管理那套判据一致。
        val applied = readZone(rootfs)
        if (applied != zoneId) {
            logs.warn(TAG, "收尾脚本执行后 /etc/timezone=$applied，期望 $zoneId")
            return false
        }
        logs.info(TAG, "时区已由 guest 脚本设为 $zoneId（脚本 exit=${result.exitCode}）")
        return true
    }

    private fun readZone(rootfs: File): String? = runCatching {
        File(rootfs, "etc/timezone").takeIf { it.isFile }?.readText()?.trim()
    }.getOrNull()

    /**
     * guest 收尾脚本的内容。
     *
     * **刻意不使用任何 shell 变量展开 —— 通篇没有 `$`**：时区值由宿主直接写死进去，
     * 于是这个 Kotlin 模板不需要任何转义（否则每处 shell 变量都得写成 `${'$'}`），
     * 脚本本身也没有引号/展开陷阱。[zone] 已通过 [SAFE_ZONE_ID] 校验。
     *
     * 做成"脚本文件 + 在 guest 内执行"而不是把命令拼成一行，是为了让用户能随时
     * `cat` / 手动重跑它，排障成本最低。
     */
    private fun setupScript(zone: String): String = """
        |#!/bin/sh
        |# proot-pi-setup —— 由 PRoot-Pi 在 rootfs 安装完成后、于 guest 内执行。
        |# 可手动重跑：  sh /usr/local/bin/proot-pi-setup.sh
        |#
        |# 为什么放在 guest 内执行（而不是宿主直接改文件）：
        |#   Alpine 的 minirootfs 不带 tzdata（没有 /usr/share/zoneinfo），
        |#   只能在 guest 内用它自己的包管理器装上，才谈得上设时区。
        |#
        |# 时区值由宿主读 Android 的 persist.sys.timezone 后写死在下方；脚本内不做变量展开。
        |set -u
        |
        |echo '[setup] 目标时区: $zone'
        |
        |# 1) 缺 zone 数据时按发行版补装（只有 Alpine 会走这条）
        |if [ ! -f /usr/share/zoneinfo/$zone ]; then
        |    if command -v apk >/dev/null 2>&1; then
        |        echo '[setup] 缺少 tzdata，尝试 apk add tzdata'
        |        apk add --no-cache tzdata >/dev/null 2>&1 || echo '[setup] tzdata 安装失败（可能需要网络）'
        |    fi
        |fi
        |
        |# 2) 仍然没有就明确放弃，不留半吊子配置（写个不存在的时区只会静默退回 UTC）
        |if [ ! -f /usr/share/zoneinfo/$zone ]; then
        |    echo '[setup] 缺少 /usr/share/zoneinfo/$zone，未设置时区'
        |    exit 0
        |fi
        |
        |# 3) 发行版自带的工具优先（Alpine 的 busybox setup-timezone）
        |if command -v setup-timezone >/dev/null 2>&1; then
        |    setup-timezone -z $zone >/dev/null 2>&1 || true
        |fi
        |
        |# 4) 兜底落盘：Debian/Ubuntu 的时区就是这两个文件；幂等
        |printf '$zone\n' > /etc/timezone
        |ln -sfn /usr/share/zoneinfo/$zone /etc/localtime
        |
        |echo '[setup] 时区已设为 $zone，当前时间：'
        |date
        |exit 0
    """.trimMargin()

    /**
     * 把复制出来的目录树放开到「所有人可读」（目录另加可执行，否则无法遍历）。
     *
     * `File.copyRecursively` 不保留权限，产出是 `700`/`600`，而 rootfs 里其余文件
     * 是 tar 解出的 `644`/`755`。只按 owner 可读虽然当下也能工作（proot 内各种伪造
     * uid 在内核看来都是 app uid，即 owner），但那是**隐式依赖**；对齐成一致权限后，
     * rootfs 里任意文件的可读性判断都遵循同一条规则。
     */
    private fun normalizePerms(dir: File) {
        dir.walk().forEach { f ->
            runCatching {
                f.setReadable(true, false)
                if (f.isDirectory) f.setExecutable(true, false)
            }
        }
    }

    /**
     * 关闭 apt 的 sandbox。
     *
     * Debian/Ubuntu 的 apt 以 root 运行时，会 `setuid(_apt)`（uid 42）再写
     * `/var/lib/apt/lists/`（该目录下的 `lock` 属 `root:root` 640、`partial` 属 `_apt` 700）。
     * 但 **app 进程（uid 10076）没有 `setuid(42)` 的能力** —— proot 的 `-0` 只能伪造
     * `getuid()` 的返回值，改不了内核的 setuid 判定。于是 apt 以 `_apt` 身份去打开
     * 属 root 的 `lock`，被内核拒掉：
     *
     * ```
     * E: Could not open lock file /var/lib/apt/lists/lock - open (13: Permission denied)
     * E: Unable to lock directory /var/lib/apt/lists/
     * ```
     *
     * 让 apt 统一以 root 身份操作即可 —— 这些目录本来就在 app 私有存储里，
     * 除本 app 外无人能访问，去掉沙箱不带来额外的暴露面。
     * 真机实测：写入本文件前 `apt update` 失败，写入后 4 个源全部 `Hit`。
     *
     * Alpine 用 apk、没有 `/etc/apt`，故此处自然跳过。
     */
    private fun writeAptSandboxOff(rootfs: File) {
        val confDir = File(rootfs, "etc/apt/apt.conf.d")
        if (!confDir.isDirectory) return
        File(confDir, APT_NO_SANDBOX_FILE).writeText(APT_NO_SANDBOX)
        logs.info(TAG, "已关闭 apt sandbox（$APT_NO_SANDBOX_FILE）")
    }

    private fun writeResolvConf(rootfs: File, servers: List<String>) {
        val etc = File(rootfs, "etc")
        if (!etc.isDirectory) {
            logs.warn(TAG, "缺少 etc 目录，跳过 resolv.conf：$rootfs")
            return
        }
        val file = File(etc, "resolv.conf")
        // rootfs 里可能是指向 systemd-resolved 之类的绝对符号链接；先摘掉再写普通文件，
        // 否则会顺着链接写到 rootfs 之外（对齐 proot-distro 用 O_NOFOLLOW 的用意）。
        if (java.nio.file.Files.isSymbolicLink(file.toPath())) {
            runCatching { file.delete() }
        }
        file.writeText(servers.joinToString("") { "nameserver $it\n" })
        logs.info(TAG, "已写入 /etc/resolv.conf：${servers.joinToString(", ")}")
    }

    private fun writeHosts(rootfs: File) {
        val etc = File(rootfs, "etc")
        if (!etc.isDirectory) return
        val file = File(etc, "hosts")
        if (java.nio.file.Files.isSymbolicLink(file.toPath())) {
            runCatching { file.delete() }
        }
        file.writeText(HOSTS)
        logs.info(TAG, "已写入 /etc/hosts")
    }

    /**
     * DNS 服务器提供者。
     *
     * 抽成接口是为了让 [RootFsManager] 不直接依赖 Android 框架，
     * 也便于测试时替换为固定值。
     */
    interface DnsProvider {
        fun servers(): List<String>
    }

    /**
     * 用 [ConnectivityManager] 取当前网络的 DNS（官方 API，不需要隐藏的
     * `SystemProperties`）。
     *
     * **重要：取到系统 DNS 时绝不追加公共 DNS。**
     * resolver 会按顺序尝试每个 nameserver，而 `8.8.8.8` 在国内常被劫持，
     * 返回的 CDN 节点连不通 —— 真机实测：`192.168.128.29 + 8.8.8.8` 会让
     * `apk update` 报 `TLS: unspecified error`，而只留系统 DNS 则完全正常
     * （28554 个包可见）。所以公共 DNS 只作为"一条都取不到"时的最后兜底。
     */
    class AndroidDnsProvider(context: Context) : DnsProvider {

        private val appContext = context.applicationContext

        override fun servers(): List<String> {
            val found = runCatching {
                val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE)
                    as? ConnectivityManager ?: return@runCatching emptyList()
                // 收集**所有**网络的 DNS，而不只是 activeNetwork。
                // 真机实测：本机 activeNetwork 是 VPN（tun0），其 DNS 172.19.0.2
                // 只在 VPN 活跃时有效；底层的 WiFi DNS 才是稳定可达的那个。
                // 两者都写进去，VPN 断线时 resolver 仍能落到 WiFi 的 DNS。
                cm.allNetworks.flatMap { net ->
                    runCatching {
                        cm.getLinkProperties(net)?.dnsServers
                            ?.mapNotNull { it.hostAddress?.substringBefore('%') }
                            .orEmpty()
                    }.getOrDefault(emptyList())
                }
            }.getOrDefault(emptyList())

            if (found.isEmpty()) return FALLBACK_DNS

            // IPv4 优先：部分 Android 内核下 guest 内 IPv6 出网不稳，
            // 而 resolver 是按顺序尝试的，把可达性更高的放前面。
            val ordered = found.filter { !it.contains(':') } + found.filter { it.contains(':') }
            return ordered.distinct().take(MAX_SERVERS)
        }
    }

    companion object {
        private const val TAG = "Fixup"

        /** 取不到系统 DNS 时的回落值，与 proot-distro 的默认一致。 */
        private val FALLBACK_DNS = listOf("8.8.8.8", "8.8.4.4")

        /** resolver 通常只读前 3 个 nameserver，多写无益。 */
        private const val MAX_SERVERS = 3

        /** glibc 的 locale 数据目录（Alpine 用 musl，没有这个目录）。 */
        private const val LOCALE_DIR = "usr/lib/locale"

        /** 目标 locale 名。必须是 `xx_YY.codeset` 形态，uutils 才认。 */
        private const val UTF8_LOCALE = "en_US.UTF-8"

        /** 可作复制来源的已编译 UTF-8 locale（按顺序取第一个存在的）。 */
        private val UTF8_LOCALE_SOURCES = listOf("C.utf8", "C.UTF-8")

        /** guest 收尾脚本在 rootfs 内的落点（同样硬编码进脚本，供用户手动重跑）。 */
        private const val GUEST_SCRIPT_REL = "usr/local/bin/proot-pi-setup.sh"
        private const val GUEST_SCRIPT_PATH = "/usr/local/bin/proot-pi-setup.sh"

        /** 收尾脚本超时：可能要 `apk add tzdata`（联网装包）。 */
        private const val GUEST_SETUP_TIMEOUT_SEC = 300L

        /**
         * 可安全写进脚本的时区标识。
         *
         * 排除 `:` —— `GMT+08:00` 这类名字没有对应的 zoneinfo 文件（设了也是白设），
         * 且冒号会破坏脚本结构。真机实测正常情况下是 `Asia/Shanghai`。
         */
        private val SAFE_ZONE_ID = Regex("^[A-Za-z0-9_+/-]{1,64}$")

        /**
         * 关闭 apt sandbox 的配置文件。
         *
         * apt 按文件名顺序读 `apt.conf.d/`，用 `99-` 前缀确保排在发行版自带配置之后，
         * 这样我们的设置才不会被覆盖。
         */
        private const val APT_NO_SANDBOX_FILE = "99proot-pi-no-sandbox"

        private const val APT_NO_SANDBOX =
            "// 由 PRoot-Pi 写入 —— 详见 GuestFixup.writeAptSandboxOff 的说明。\n" +
                "// Android app 无法 setuid(_apt)，不禁用沙箱则 apt update 必然 Permission denied。\n" +
                "APT::Sandbox::User \"root\";\n"

        private val HOSTS = """
            # 由 PRoot-Pi 在安装时写入（对齐 proot-distro 的 write_hosts）
            127.0.0.1   localhost.localdomain localhost
            ::1         localhost.localdomain localhost ip6-localhost ip6-loopback
            fe00::0     ip6-localnet
            ff00::0     ip6-mcastprefix
            ff02::1     ip6-allnodes
            ff02::2     ip6-allrouters
            ff02::3     ip6-allhosts
        """.trimIndent() + "\n"
    }
}
