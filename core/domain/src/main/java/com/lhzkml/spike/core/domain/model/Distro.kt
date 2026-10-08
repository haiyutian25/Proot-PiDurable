package com.lhzkml.spike.core.domain.model

/**
 * 可选的 Linux 发行版。
 *
 * 这里只描述业务标识；rootfs 的来源地址、校验值等技术细节属于 data 层
 * （见 `data/runtime/DistroSpec.kt`），不得泄漏到 domain 层（方案 §4）。
 */
enum class Distro(
    val id: String,
    val displayName: String,
    /** 展示用的一句话说明（含体积与 libc 类型，便于用户判断） */
    val summary: String,
) {
    ALPINE(
        id = "alpine",
        displayName = "Alpine 3.24.2",
        summary = "约 4 MB · musl libc · apk",
    ),
    UBUNTU(
        id = "ubuntu",
        displayName = "Ubuntu Base 26.04.1",
        summary = "约 30 MB · glibc · apt",
    ),
    ;

    companion object {
        /** 默认发行版 */
        val DEFAULT: Distro = ALPINE

        /** 由持久化的 id 还原；未知 id 回落到默认值 */
        fun fromId(id: String?): Distro = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
