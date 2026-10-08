package com.lhzkml.spike.core.data.runtime

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream

/**
 * 最小 tar(gz) 解压器。
 *
 * 为什么不用现成库：Android 没有内置 tar，而引入第三方库只为解一个 rootfs 不划算；
 * 且需要在此处做「绝对符号链接改写」这一 rootfs 专用处理。
 *
 * 关键点：
 *  · 全程流式，绝不把归档读入内存（app 堆上限约 192MB，rootfs 解压后近 300MB）
 *  · 绝对符号链接必须改写为相对链接，否则会指向宿主路径（如 /usr/bin/mawk）
 *  · 目录穿越（.. / 绝对路径）一律拒绝
 */
class TarExtractor {

    interface Progress {
        fun onEntries(count: Long)
        fun onLog(line: String)
    }

    data class Stats(
        val files: Long,
        val dirs: Long,
        val links: Long,
        /** 真正被丢弃的条目（设备节点 / FIFO / 非法路径） */
        val skipped: Long,
        /** PAX 扩展头数量 —— 不是失败，单独计数以免与 [skipped] 混淆 */
        val paxHeaders: Long,
    )

    fun extract(archive: File, destDir: File, progress: Progress): Stats {
        var files = 0L
        var dirs = 0L
        var links = 0L
        var skipped = 0L
        var paxHeaders = 0L
        var count = 0L

        FileInputStream(archive).use { fin ->
            GZIPInputStream(fin, 64 * 1024).use { gz ->
                var pendingLongName: String? = null
                var pendingPax: Map<String, String>? = null
                val header = ByteArray(512)

                while (true) {
                    if (!readFully(gz, header, 512)) break
                    if (header.all { it == 0.toByte() }) break // 两个全零块表示结束

                    val nameRaw = str(header, 0, 100)
                    val modeStr = str(header, 100, 8).trim()
                    val sizeOct = str(header, 124, 12).trim()
                    val typeFlag = header[156].toInt().toChar()
                    val linkNameRaw = str(header, 157, 100)
                    val prefix = str(header, 345, 155)
                    val headerSize = if (sizeOct.isEmpty()) 0L else sizeOct.toLongOrNull(8) ?: 0L

                    // GNU 长名扩展（'L' 路径 / 'K' 链接目标）
                    if (typeFlag == 'L' || typeFlag == 'K') {
                        val buf = ByteArray(headerSize.toInt())
                        readFully(gz, buf, buf.size)
                        skipBytes(gz, padding(headerSize))
                        val text = String(buf, Charsets.UTF_8).trimEnd('\u0000')
                        if (typeFlag == 'L') pendingLongName = text
                        continue
                    }

                    // PAX 扩展头：GNU tar ≥1.35 的默认格式，每个条目带一个
                    // （Ubuntu Base 的 rootfs 即是如此，常只含 atime/ctime）。
                    // 但长路径 / 超大文件会用它承载 path / linkpath / size，必须应用，
                    // 否则会解出被 100 字符截断的文件名或读错数据长度。
                    if (typeFlag == 'x' || typeFlag == 'g') {
                        val buf = ByteArray(headerSize.toInt())
                        readFully(gz, buf, buf.size)
                        skipBytes(gz, padding(headerSize))
                        if (typeFlag == 'x') {
                            pendingPax = parsePax(String(buf, Charsets.UTF_8))
                        }
                        paxHeaders++
                        continue
                    }

                    val pax = pendingPax
                    pendingPax = null

                    val rawName = pax?.get("path")
                        ?: pendingLongName
                        ?: if (prefix.isNotEmpty()) "$prefix/$nameRaw" else nameRaw
                    pendingLongName = null
                    val effectiveLink = pax?.get("linkpath") ?: linkNameRaw
                    val size = pax?.get("size")?.toLongOrNull() ?: headerSize

                    val mode = modeStr.toIntOrNull(8) ?: 0b111101101
                    val safeName = sanitize(rawName)

                    if (safeName == null) {
                        // 非法路径：跳过其数据块，保持流位置正确
                        skipBytes(gz, size + padding(size))
                        skipped++
                        continue
                    }

                    val target = File(destDir, safeName)

                    when (typeFlag) {
                        '5' -> {
                            target.mkdirs()
                            applyMode(target, mode)
                            dirs++
                        }

                        '2' -> {
                            target.parentFile?.mkdirs()
                            val linkTarget = rewriteLink(safeName, effectiveLink)
                            if (target.exists() || target.isDirectory) target.delete()
                            makeSymlink(target, linkTarget)
                            links++
                        }

                        '1' -> {
                            // Android app 存储无法创建硬链接，退化为复制
                            target.parentFile?.mkdirs()
                            sanitize(effectiveLink)?.let { lt ->
                                val src = File(destDir, lt)
                                if (src.exists()) {
                                    try {
                                        java.nio.file.Files.createLink(target.toPath(), src.toPath())
                                    } catch (_: Throwable) {
                                        runCatching { src.copyTo(target, overwrite = true) }
                                    }
                                }
                            }
                            applyMode(target, mode)
                            links++
                        }

                        '0', '\u0000', '7' -> {
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { out -> copyN(gz, out, size) }
                            skipBytes(gz, padding(size))
                            applyMode(target, mode)
                            files++
                        }

                        else -> {
                            // 设备节点 / FIFO 在 app 内无法创建：跳过数据
                            skipBytes(gz, size + padding(size))
                            skipped++
                        }
                    }

                    count++
                    if (count % 500L == 0L) progress.onEntries(count)
                }
            }
        }

        progress.onEntries(count)
        return Stats(files, dirs, links, skipped, paxHeaders)
    }

    /**
     * 解析 PAX 扩展头记录。
     *
     * 格式为若干条 `"<长度> <key>=<value>\n"`，长度是整个记录（含长度数字与空格）
     * 的字节数。只取我们关心的键即可，未知键忽略。
     */
    private fun parsePax(text: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        var i = 0
        while (i < text.length) {
            val sp = text.indexOf(' ', i)
            if (sp < 0) break
            val len = text.substring(i, sp).toIntOrNull() ?: break
            if (len <= 0 || i + len > text.length) break
            val record = text.substring(sp + 1, i + len).trimEnd('\n')
            val eq = record.indexOf('=')
            if (eq > 0) out[record.substring(0, eq)] = record.substring(eq + 1)
            i += len
        }
        return out
    }

    /** 扫描并修正已解压树中的绝对符号链接（兜底） */
    fun fixAbsoluteSymlinks(root: File): Int {
        var fixed = 0
        val stack = ArrayDeque<File>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            for (child in dir.listFiles() ?: continue) {
                val p = child.toPath()
                if (java.nio.file.Files.isSymbolicLink(p)) {
                    val link = runCatching {
                        java.nio.file.Files.readSymbolicLink(p).toString()
                    }.getOrNull() ?: continue
                    if (!link.startsWith("/")) continue
                    val rel = root.toPath().relativize(p).toString().replace('\\', '/')
                    runCatching {
                        java.nio.file.Files.delete(p)
                        makeSymlink(child, rewriteLink(rel, link))
                        fixed++
                    }
                } else if (child.isDirectory) {
                    stack.addLast(child)
                }
            }
        }
        return fixed
    }

    // ------------------------------------------------------------------

    /**
     * 目录穿越防护 + 路径规范化。
     *
     * 必须剔除 `.` 段：Alpine 的 minirootfs 条目普遍以 `./` 开头（`./bin/sh`），
     * 若保留该段，[rewriteLink] 计算相对链接时会把它当成一级真实目录，
     * 从而多上溯一层（`bin/sh -> ../../bin/busybox`，解析后跑出 rootfs）。
     */
    private fun sanitize(raw: String): String? {
        val normalized = raw.replace('\\', '/').trimStart('/')
        if (normalized.isEmpty()) return null
        val parts = normalized.split('/')
        if (parts.any { it == ".." }) return null
        return parts
            .filter { it.isNotEmpty() && it != "." }
            .joinToString("/")
            .ifEmpty { null }
    }

    /** 绝对符号链接 → 相对链接（否则会指向宿主路径） */
    private fun rewriteLink(entryName: String, linkName: String): String {
        if (!linkName.startsWith("/")) return linkName
        val entryDir = entryName.substringBeforeLast('/', "")
        val target = linkName.trimStart('/')
        if (entryDir.isEmpty()) return target
        val from = entryDir.split('/').filter { it.isNotEmpty() }
        val to = target.split('/')
        var common = 0
        while (common < from.size && common < to.size && from[common] == to[common]) common++
        val up = from.size - common
        val sb = StringBuilder()
        repeat(up) { sb.append("../") }
        sb.append(to.drop(common).joinToString("/"))
        return sb.toString().ifEmpty { "." }
    }

    /**
     * 创建符号链接。
     * Android 上 Files.createSymbolicLink 在低 API 不稳定，优先用 ln -s；
     * 无符号链接能力时退化为空文件占位（保持路径存在）。
     */
    private fun makeSymlink(target: File, linkTarget: String) {
        runCatching {
            val pb = ProcessBuilder("ln", "-sfn", linkTarget, target.absolutePath)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            proc.inputStream.readBytes()
            if (proc.waitFor() == 0 && java.nio.file.Files.isSymbolicLink(target.toPath())) return
        }
        runCatching { target.writeText("") }
    }

    private fun applyMode(f: File, mode: Int) {
        runCatching {
            f.setReadable((mode and 0b100100100) != 0, false)
            if ((mode and 0b001001001) != 0) f.setExecutable(true, false)
        }
    }

    private fun str(b: ByteArray, off: Int, len: Int): String {
        var end = off
        val limit = minOf(off + len, b.size)
        while (end < limit && b[end] != 0.toByte()) end++
        return String(b, off, end - off, Charsets.UTF_8)
    }

    private fun readFully(input: InputStream, buf: ByteArray, len: Int): Boolean {
        var read = 0
        while (read < len) {
            val n = input.read(buf, read, len - read)
            if (n < 0) return false
            read += n
        }
        return true
    }

    private fun copyN(input: InputStream, output: OutputStream, n: Long) {
        var left = n
        val buf = ByteArray(64 * 1024)
        while (left > 0) {
            val want = minOf(left, buf.size.toLong()).toInt()
            val r = input.read(buf, 0, want)
            if (r < 0) break
            output.write(buf, 0, r)
            left -= r
        }
    }

    private fun skipBytes(input: InputStream, n: Long) {
        var left = n
        val buf = ByteArray(8 * 1024)
        while (left > 0) {
            val want = minOf(left, buf.size.toLong()).toInt()
            val r = input.read(buf, 0, want)
            if (r < 0) return
            left -= r
        }
    }

    private fun padding(size: Long): Long = (512 - (size % 512)) % 512
}
