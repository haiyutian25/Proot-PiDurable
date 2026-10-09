package com.lhzkml.spike.core.data.runtime

import com.lhzkml.spike.core.domain.model.Distro
import com.lhzkml.spike.core.domain.repository.LogRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * RootFS 管理器（方案 §13）。
 *
 * 负责：下载 → 校验 → 解压 → 完整性检查 → 删除。支持多发行版共存。
 *
 * 流程与实测结论（spike 阶段验证）：
 *  · 每个发行版一个**官方直链**，不做多镜像兜底（用户明确要求）
 *  · 先写 `.part`，校验通过后才改名 —— 中断的下载永不进入正式文件名
 *  · 支持 Range 续传以吸收瞬时 TLS 波动
 *  · 解压全程流式（app 堆上限约 192MB）
 *  · SHA256 硬编码在 [DistroSpec] 中，不在运行时拉取校验文件
 */
class RootFsManager(
    private val paths: RuntimePaths,
    private val logs: LogRepository,
    private val extractor: TarExtractor = TarExtractor(),
    /** 安装后的修补（DNS / hosts）。为空则跳过 —— 便于纯离线场景。 */
    private val fixup: GuestFixup? = null,
) {

    companion object {
        private const val TAG = "RootFs"
        private const val MAX_ATTEMPTS = 3
        private const val MIN_RESUME_BYTES = 64L * 1024
        private const val USER_AGENT = "proot-pi/0.1 (Android; rootfs-bootstrap)"

        /** 归档大小未知时的空间预估（Ubuntu Base 量级） */
        private const val FALLBACK_ARCHIVE_BYTES = 64L * 1024 * 1024

        /** 解压后额外需要的余量（rootfs 通常比归档大 2–3 倍） */
        private const val EXTRA_HEADROOM_BYTES = 256L * 1024 * 1024
    }

    interface Progress {
        /** 阶段名（用于 UI 展示） */
        fun onStage(stage: String, detail: String)
        /** 下载进度；total<=0 表示未知 */
        fun onDownload(downloaded: Long, total: Long)
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    fun rootFsPath(distro: Distro): File = paths.rootfsOf(distro)

    /** 指定发行版是否已安装（标记文件 + 关键路径双重确认） */
    fun isInstalled(distro: Distro): Boolean {
        if (!paths.markerOf(distro).isFile) return false
        val root = paths.rootfsOf(distro)
        return DistroSpec.of(distro).requiredPaths.all { File(root, it).exists() }
    }

    /** 当前已安装的发行版列表 */
    fun installedDistros(): List<Distro> = Distro.entries.filter { isInstalled(it) }

    /** 读取安装标记里记录的 SHA256（用于诊断展示） */
    fun installMarkerSha(distro: Distro): String? = runCatching {
        paths.markerOf(distro).readText()
            .lineSequence()
            .firstOrNull { it.startsWith("sha256=") }
            ?.removePrefix("sha256=")
    }.getOrNull()

    /** 校验指定发行版的已安装 rootfs */
    fun verifyInstallation(distro: Distro): Result<Unit> = runCatching {
        check(isInstalled(distro)) { "${distro.displayName} 尚未安装" }
    }

    // ------------------------------------------------------------------
    // 安装 / 删除
    // ------------------------------------------------------------------

    /** 安装指定发行版的 rootfs */
    suspend fun install(distro: Distro, progress: Progress): Result<Unit> = withContext(Dispatchers.IO) {
        val spec = DistroSpec.of(distro)
        runCatching {
            // ---- 0. 前置检查 ----
            val archiveEstimate = if (spec.archiveBytes > 0) spec.archiveBytes else FALLBACK_ARCHIVE_BYTES
            val needed = archiveEstimate + EXTRA_HEADROOM_BYTES
            val usable = paths.runtimeRoot.usableSpace
            check(usable == 0L || usable >= needed) {
                "存储空间不足：需要约 ${needed / 1048576} MB，可用 ${usable / 1048576} MB"
            }

            // ---- 1. 归档就位（已存在则复用）----
            val archive = paths.archiveOf(distro)
            if (archive.isFile && archive.length() > 0) {
                progress.onStage("download", "已存在归档，跳过下载")
                logs.info(TAG, "复用已有归档 ${archive.length()} bytes")
            } else {
                progress.onStage("download", "开始下载 ${distro.displayName}")
                logs.info(TAG, "下载来源：${spec.url}")
                acquire(distro, spec, progress)
            }

            // ---- 2. 校验 ----
            progress.onStage("verify", "校验 SHA256")
            val actual = sha256(archive)
            check(actual.equals(spec.sha256, ignoreCase = true)) {
                "SHA256 不匹配\n期望 ${spec.sha256}\n实际 $actual"
            }
            logs.info(TAG, "SHA256 通过：$actual")
            progress.onStage("verify", "SHA256 通过")

            // ---- 3. 解压 ----
            val root = paths.rootfsOf(distro)
            if (isInstalled(distro)) {
                progress.onStage("extract", "rootfs 已安装，跳过解压")
            } else {
                progress.onStage("extract", "解压 rootfs")
                root.mkdirs()
                val stats = extractor.extract(
                    archive,
                    root,
                    object : TarExtractor.Progress {
                        override fun onEntries(count: Long) {
                            progress.onStage("extract", "已解压 $count 个条目")
                        }

                        override fun onLog(line: String) = logs.info(TAG, line)
                    },
                )
                logs.info(
                    TAG,
                    "解压完成：files=${stats.files} dirs=${stats.dirs} links=${stats.links} " +
                        "skipped=${stats.skipped} paxHeaders=${stats.paxHeaders}",
                )

                // ---- 4. 兜底修正符号链接 ----
                val fixed = extractor.fixAbsoluteSymlinks(root)
                if (fixed > 0) logs.warn(TAG, "修正 $fixed 个绝对符号链接")
            }

            // ---- 4.5 安装后修补：DNS 与 hosts ----
            // Android 宿主没有 /etc/resolv.conf（DNS 由 netd 管），guest 内因此解析不了
            // 域名、apt/apk 装不了包。必须在每次安装后写入 —— 放在解压分支之外，
            // 这样「已装好但缺 resolv.conf」的老 rootfs 重跑安装也能修好。
            fixup?.let { fx ->
                progress.onStage("fixup", "配置 DNS 与 hosts")
                runCatching { fx.apply(root) }
                    .onSuccess { s -> logs.info(TAG, "fixup 完成，DNS=$s") }
                    .onFailure { logs.warn(TAG, "fixup 失败（不影响可用性）：${it.message}") }
            }

            // ---- 4.6 在 guest 内执行收尾脚本 ----
            // 对齐 Kai 的做法：安装完成后进沙盒跑命令，而不是只在宿主侧改文件。
            // 时区这类"需要发行版自身能力"的调整必须这么做 —— Alpine 得先 apk add tzdata，
            // 宿主侧写文件对它是无效的（见 GuestFixup.setupScript 的说明）。
            fixup?.let { fx ->
                progress.onStage("setup", "在 guest 内执行收尾脚本")
                runCatching { fx.ensureTimezoneInGuest(root, force = true) }
                    .onSuccess { ok -> logs.info(TAG, "guest 收尾脚本：${if (ok) "完成" else "未完全生效"}") }
                    .onFailure { logs.warn(TAG, "guest 收尾脚本失败（不影响可用性）：${it.message}") }
            }

            // ---- 5. 完整性检查 ----
            progress.onStage("validate", "检查 rootfs 完整性")
            spec.requiredPaths.forEach { rel ->
                check(File(root, rel).exists()) { "rootfs 缺少 $rel" }
            }

            paths.markerOf(distro).writeText(
                buildString {
                    appendLine("installed=${System.currentTimeMillis()}")
                    appendLine("distro=${distro.id}")
                    appendLine("sha256=${spec.sha256}")
                    appendLine("url=${spec.url}")
                },
            )
            progress.onStage("done", "${distro.displayName} 安装完成")
            logs.info(TAG, "${distro.displayName} 安装完成")
        }.onFailure { logs.error(TAG, "安装失败：${it.message}") }
    }

    /** 删除指定发行版的 rootfs 与归档 */
    suspend fun remove(distro: Distro): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            paths.rootfsOf(distro).deleteRecursively()
            paths.archiveOf(distro).delete()
            paths.markerOf(distro).delete()
            logs.info(TAG, "已删除 ${distro.displayName}")
        }
    }

    /**
     * 一次性清理旧布局（单发行版 Debian 12）的遗留数据。
     *
     * 旧版本把 `runtime/rootfs` 直接当 rootfs 使用，多发行版布局下该目录已是父目录；
     * 旧数据不再被任何代码引用，且占用约 300 MB。
     *
     * @return 释放的字节数（估算）
     */
    suspend fun cleanupLegacy(): Long = withContext(Dispatchers.IO) {
        var freed = 0L
        runCatching {
            val legacyRoot = paths.hasLegacyRootfs()
            val legacyFiles = paths.legacyArtifacts().filter { it.exists() }
            if (!legacyRoot && legacyFiles.isEmpty()) return@withContext 0L

            if (legacyRoot) {
                freed += paths.rootfsRoot.directorySize()
                paths.rootfsRoot.deleteRecursively()
            }
            legacyFiles.forEach { f ->
                freed += if (f.isFile) f.length() else f.directorySize()
                f.deleteRecursively()
            }
            logs.info(TAG, "已清理旧布局数据，释放约 ${freed / 1048576} MB")
        }
        freed
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /**
     * 下载归档。
     *
     * 单一 URL；最多 [MAX_ATTEMPTS] 次尝试，支持 Range 续传。
     * 重试与续传用于吸收瞬时 TLS 波动，**不是**备用链接机制。
     */
    private fun acquire(distro: Distro, spec: DistroSpec, progress: Progress) {
        val archive = paths.archiveOf(distro)
        val staged = File(archive.absolutePath + ".part")
        staged.parentFile?.mkdirs()
        var lastError: Exception? = null

        for (attempt in 1..MAX_ATTEMPTS) {
            if (attempt > 1) {
                logs.warn(TAG, "第 $attempt/$MAX_ATTEMPTS 次重试")
                progress.onStage("retry", "第 $attempt/$MAX_ATTEMPTS 次重试")
                runCatching { Thread.sleep(2_000L * (attempt - 1)) }
            }

            if (staged.isFile && staged.length() in 1 until MIN_RESUME_BYTES) staged.delete()
            val resume = if (staged.isFile) staged.length() else 0L

            try {
                val conn = (URL(spec.url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30_000
                    readTimeout = 60_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Accept-Encoding", "identity")
                    if (resume > 0L) setRequestProperty("Range", "bytes=$resume-")
                }
                try {
                    conn.connect()
                    val code = conn.responseCode
                    check(code in 200..299) { "HTTP $code" }

                    val appending = resume > 0L && code == 206
                    val total = when {
                        appending && conn.contentLengthLong > 0 -> conn.contentLengthLong + resume
                        conn.contentLengthLong > 0 -> conn.contentLengthLong
                        else -> spec.archiveBytes
                    }
                    if (appending) {
                        logs.info(TAG, "续传自 ${resume / 1048576} MB")
                        progress.onStage("download", "续传自 ${resume / 1048576} MB")
                    }

                    conn.inputStream.use { input ->
                        FileOutputStream(staged, appending).use { output ->
                            val buf = ByteArray(128 * 1024)
                            var done = if (appending) resume else 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                output.write(buf, 0, n)
                                done += n
                                progress.onDownload(done, total)
                            }
                        }
                    }
                } finally {
                    runCatching { conn.disconnect() }
                }

                // 校验通过才落位
                val actual = sha256(staged)
                if (!actual.equals(spec.sha256, ignoreCase = true)) {
                    if (resume > 0L) {
                        logs.warn(TAG, "续传内容校验失败，重新下载")
                        staged.delete()
                        lastError = SecurityException("续传拼接内容校验失败")
                        continue
                    }
                    throw SecurityException("SHA256 不匹配：$actual")
                }
                if (archive.exists()) archive.delete()
                check(staged.renameTo(archive)) { "无法重命名 .part" }
                logs.info(TAG, "下载完成 ${archive.length()} bytes")
                return
            } catch (e: Exception) {
                lastError = e
                logs.warn(TAG, "${e.javaClass.simpleName}: ${e.message}")
                if (staged.isFile && staged.length() < MIN_RESUME_BYTES) staged.delete()
            }
        }
        throw lastError ?: java.io.IOException("下载失败")
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/** 递归统计目录占用（用于清理时给出释放量） */
internal fun File.directorySize(): Long {
    if (!exists()) return 0L
    if (isFile) return length()
    var sum = 0L
    listFiles()?.forEach { sum += it.directorySize() }
    return sum
}
