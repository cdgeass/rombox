package com.cdgeass.rombox.core.rom

import android.os.StatFs
import com.cdgeass.rombox.core.model.RomHeader
import com.cdgeass.rombox.core.model.RomPlatform
import com.cdgeass.rombox.core.platform.Platforms
import com.cdgeass.rombox.core.settings.AppSettings
import java.io.File
import java.io.FileInputStream
import java.util.zip.CRC32

/** ROM 库操作：扫描、解析头部、算 CRC32、去重、改名、删除 */
class RomRepository(private val settings: AppSettings) {

    fun romDir(): File = File(settings.romDirPath)

    fun ensureRomDir(): File = romDir().apply { if (!exists()) mkdirs() }

    fun freeSpace(): Long = runCatching { StatFs(romDir().absolutePath).availableBytes }.getOrDefault(0L)

    fun totalSpace(): Long = runCatching { StatFs(romDir().absolutePath).totalBytes }.getOrDefault(0L)

    /**
     * 扫描目录。
     * @param computeCrc 是否计算 CRC32（快速展示可以关掉）
     * @param onProgress (已完成, 总数)
     * @param cancelled 轮询是否已取消
     */
    fun listRoms(
        platform: RomPlatform,
        computeCrc: Boolean,
        onProgress: ((Int, Int) -> Unit)? = null,
        cancelled: () -> Boolean = { false },
    ): List<RomFile> {
        val dir = romDir()
        if (!dir.isDirectory) return emptyList()

        val files = mutableListOf<File>()
        dir.walkTopDown()
            .maxDepth(3)
            .onEnter { it == dir || !it.name.startsWith(".") }
            .filter { it.isFile && !it.name.startsWith(".") && platform.matches(it.name) }
            .forEach { files += it }

        val items = ArrayList<RomFile>(files.size)
        files.forEachIndexed { index, file ->
            if (cancelled()) return markDuplicates(items)
            val header = readHeader(file, platform)
            val crc = if (computeCrc) crc32(file) else null
            items += RomFile(
                file = file,
                size = file.length(),
                lastModified = file.lastModified(),
                header = header,
                crc32 = crc,
            )
            onProgress?.invoke(index + 1, files.size)
        }
        return markDuplicates(items)
    }

    fun readHeader(file: File, platform: RomPlatform): RomHeader? = try {
        val size = platform.headerReadSize
        val buffer = ByteArray(size)
        var read = 0
        FileInputStream(file).use { ins ->
            while (read < size) {
                val n = ins.read(buffer, read, size - read)
                if (n <= 0) break
                read += n
            }
        }
        platform.parseHeader(if (read == size) buffer else buffer.copyOf(read))
    } catch (e: Exception) {
        null
    }

    fun crc32(file: File, cancelled: () -> Boolean = { false }): Long? = try {
        val crc = CRC32()
        FileInputStream(file).use { ins ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val n = ins.read(buffer)
                if (n <= 0) break
                crc.update(buffer, 0, n)
                if (cancelled()) return null
            }
        }
        crc.value
    } catch (e: Exception) {
        null
    }

    /** 按 (大小, CRC32) 分组标出重复项：只有内容相同才算重复 */
    fun markDuplicates(items: List<RomFile>): List<RomFile> {
        val groups = items.filter { it.crc32 != null }
            .groupBy { it.size to it.crc32!! }
            .values
            .filter { it.size > 1 }
        if (groups.isEmpty()) return items

        val groupIds = HashMap<String, Int>()
        groups.forEachIndexed { index, group ->
            group.forEach { groupIds[it.path] = index + 1 }
        }
        return items.map { it.copy(dupGroup = groupIds[it.path]) }
    }

    /** 重复组，每组已按「建议保留」顺序排好（文件名最短的在前） */
    fun duplicateGroups(items: List<RomFile>): List<List<RomFile>> =
        items.filter { it.dupGroup != null }
            .groupBy { it.dupGroup!! }
            .values
            .map { group -> group.sortedBy { it.name.length } }
            .filter { it.size > 1 }

    fun delete(file: File): Boolean = runCatching { file.delete() }.getOrDefault(false)

    /** 目录内不冲突的目标文件 */
    fun uniqueTarget(dir: File, desiredName: String, exclude: File? = null): File {
        val name = Naming.sanitize(desiredName)
        var index = 1
        while (index < 10000) {
            val candidateName = if (index == 1) name else Naming.withIndex(name, index)
            val candidate = File(dir, candidateName)
            if (exclude != null && candidate.absolutePath == exclude.absolutePath) return candidate
            if (!candidate.exists()) return candidate
            index++
        }
        return File(dir, Naming.withIndex(name, System.currentTimeMillis().toInt()))
    }

    /** 把单个文件改成规范名，返回新文件 */
    fun renameToCanonical(file: File, header: RomHeader?): File? {
        val desired = Naming.canonicalFileName(file, header)
        if (desired == file.name) return file
        val target = uniqueTarget(file.parentFile ?: romDir(), desired, file)
        return if (runCatching { file.renameTo(target) }.getOrDefault(false)) target else null
    }

    /**
     * 批量规范命名。两阶段改名（先临时名再落地），避免互换/链式改名互相覆盖。
     * @return 实际改动的数量
     */
    fun renameAllToCanonical(platform: RomPlatform): Int {
        val items = listRoms(platform, computeCrc = false)
        val pending = items.map { it to Naming.canonicalFileName(it.file, it.header) }
            .filter { (item, desired) -> desired != item.file.name }
        if (pending.isEmpty()) return 0

        data class Pending(val temp: File, val parent: File, val desired: String)

        val temps = mutableListOf<Pending>()
        for ((item, desired) in pending) {
            val parent = item.file.parentFile ?: continue
            val temp = File(parent, ".rombox-tmp-${System.nanoTime()}-${item.file.name}")
            if (runCatching { item.file.renameTo(temp) }.getOrDefault(false)) {
                temps += Pending(temp, parent, desired)
            }
        }
        var count = 0
        for (entry in temps) {
            val target = uniqueTarget(entry.parent, entry.desired)
            if (runCatching { entry.temp.renameTo(target) }.getOrDefault(false)) count++
        }
        return count
    }
}
