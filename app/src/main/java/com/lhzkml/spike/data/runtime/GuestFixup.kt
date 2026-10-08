package com.lhzkml.spike.data.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import com.lhzkml.spike.domain.repository.LogRepository
import java.io.File

/**
 * rootfs 安装后的修补。
 *
 * 前两项对齐 proot-distro 的 `helpers/rootfs.py`（`write_resolv_conf` / `write_hosts`）；
 * 第三项是 Android 特有的必要修正（见下）。
 *
 * **为什么必须写 DNS**：Android 的 DNS 由 netd 管理，**宿主上根本没有 `/etc/resolv.conf`**
 * （实测确认）。而 guest 内的 glibc / musl 只会去读 `/etc/resolv.conf`，
 * 于是 `apt` / `apk` 解析不了域名、装不了包。proot-distro 的解决办法是安装完
 * 直接把这个文件写死进 rootfs，这里照做。
 */
class GuestFixup(
    private val dns: DnsProvider,
    private val logs: LogRepository,
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
        return servers
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
