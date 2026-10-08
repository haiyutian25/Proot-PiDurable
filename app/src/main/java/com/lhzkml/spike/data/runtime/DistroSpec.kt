package com.lhzkml.spike.data.runtime

import com.lhzkml.spike.domain.model.Distro

/**
 * 发行版的技术规格：**官方直链** + 硬编码 SHA256 + 完整性检查项。
 *
 * 来源原则（用户明确要求）：
 *  · 只用发行版官方站点，不用 Docker 镜像、不用第三方镜像站
 *  · SHA256 硬编码在代码里（不是运行时拉校验文件），避免校验链被替换
 *  · 每个发行版固定一个版本号，不做 latest 漂移
 *
 * 已核实的官方校验值（2026-10-08 取自官方 `.sha256` / `SHA256SUMS`）：
 *  · Alpine 3.24.2 aarch64 minirootfs
 *    https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64/
 *  · Ubuntu Base 26.04.1 base arm64
 *    https://cdimage.ubuntu.com/ubuntu-base/releases/26.04.1/release/
 */
data class DistroSpec(
    val distro: Distro,
    /** 官方直链 */
    val url: String,
    /** 官方 SHA256（小写十六进制） */
    val sha256: String,
    /** 归档字节数；0 表示未知，运行时以 HTTP Content-Length 为准 */
    val archiveBytes: Long,
    /** 解压后必须存在的路径（安装完整性检查） */
    val requiredPaths: List<String>,
    /** 包管理器路径（用于诊断展示，属于发行版侧元数据） */
    val packageManagerPath: String,
) {

    /** 人可读的归档大小 */
    val sizeLabel: String
        get() = if (archiveBytes > 0) {
            if (archiveBytes >= 1024 * 1024) {
                "%.1f MB".format(archiveBytes / 1048576.0)
            } else {
                "%.1f KB".format(archiveBytes / 1024.0)
            }
        } else {
            "—"
        }

    companion object {

        /** Alpine Linux 3.24.2 aarch64 minirootfs（4.0 MB） */
        val ALPINE = DistroSpec(
            distro = Distro.ALPINE,
            url = "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64/" +
                "alpine-minirootfs-3.24.2-aarch64.tar.gz",
            sha256 = "9bf70a7f18ea44094cbb5f70c58f9af129c8214745743db0e68e5502cc2ce773",
            archiveBytes = 4_028_030L,
            requiredPaths = listOf("bin/sh", "sbin/apk", "etc/os-release"),
            packageManagerPath = "/sbin/apk",
        )

        /** Ubuntu Base 26.04.1 arm64（官方 cdimage） */
        val UBUNTU = DistroSpec(
            distro = Distro.UBUNTU,
            url = "https://cdimage.ubuntu.com/ubuntu-base/releases/26.04.1/release/" +
                "ubuntu-base-26.04.1-base-arm64.tar.gz",
            sha256 = "5a1906794ced63a71a8119c3f211ef5f0bbe0a243001b4bbd41fdf80c5b219fd",
            archiveBytes = 35_092_106L,
            requiredPaths = listOf("bin/sh", "usr/bin/dpkg", "etc/os-release"),
            packageManagerPath = "/usr/bin/dpkg",
        )

        private val ALL = listOf(ALPINE, UBUNTU)

        fun of(distro: Distro): DistroSpec =
            ALL.firstOrNull { it.distro == distro } ?: ALPINE

        fun all(): List<DistroSpec> = ALL
    }
}
