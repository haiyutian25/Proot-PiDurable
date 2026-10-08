package com.lhzkml.spike.domain.model

/**
 * Runtime 状态（方案 §23）。
 *
 * 这是全 App 的核心状态，UI 只观察它，不自行探测进程存活。
 */
enum class RuntimeStatus {
    /** 尚未安装 rootfs */
    NOT_INSTALLED,

    /** 安装中（下载 / 校验 / 解压） */
    INSTALLING,

    /** 已安装但未启动 */
    STOPPED,

    /** 启动中，具体阶段见 [RuntimeStage] */
    STARTING,

    /** 已启动并就绪 */
    RUNNING,

    /** 停止中 */
    STOPPING,

    /** 安装或启动失败 */
    ERROR;

    val displayName: String
        get() = when (this) {
            NOT_INSTALLED -> "未安装"
            INSTALLING -> "安装中"
            STOPPED -> "已停止"
            STARTING -> "启动中"
            RUNNING -> "运行中"
            STOPPING -> "停止中"
            ERROR -> "错误"
        }
}

/**
 * 启动过程的分阶段状态（方案 §27）。
 *
 * 让 UI 能显示 "启动 PRoot" / "验证 Node" 这类具体阶段，而不是笼统的 "Loading"。
 */
enum class RuntimeStage {
    IDLE,
    PREPARING_ROOTFS,
    STARTING_PROOT,
    VERIFYING_LINUX,
    VERIFYING_NODE,
    READY,
    FAILED;

    val label: String
        get() = when (this) {
            IDLE -> "空闲"
            PREPARING_ROOTFS -> "准备 RootFS"
            STARTING_PROOT -> "启动 PRoot"
            VERIFYING_LINUX -> "验证 Linux"
            VERIFYING_NODE -> "验证 Node"
            READY -> "就绪"
            FAILED -> "失败"
        }
}
