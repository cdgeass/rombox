package com.cdgeass.rombox.core.rom

import com.cdgeass.rombox.core.model.RomPlatform
import java.io.File
import java.util.zip.ZipFile

/**
 * 把 zip 包里的 ROM 解出来。
 * 上传 .zip 时自动调用，省得在电脑上先解压。
 */
object ZipImporter {

    private const val MAX_ENTRIES = 200
    private const val MAX_TOTAL = 1L shl 30 // 1 GB

    fun extract(zip: File, dir: File, platforms: List<RomPlatform>): List<String> {
        val result = runCatching { extractWith(zip, dir, platforms, Charsets.UTF_8) }
        return result.getOrElse {
            runCatching { extractWith(zip, dir, platforms, charset("GBK")) }
                .getOrElse { throw it }
        }
    }

    private fun extractWith(
        zip: File,
        dir: File,
        platforms: List<RomPlatform>,
        charset: java.nio.charset.Charset,
    ): List<String> {
        val saved = mutableListOf<String>()
        var total = 0L
        ZipFile(zip, charset).use { zf ->
            val entries = zf.entries().toList()
                .filter { !it.isDirectory }
                .take(MAX_ENTRIES)
            for (entry in entries) {
                val baseName = entry.name.substringAfterLast('/').substringAfterLast('\\')
                if (baseName.isEmpty()) continue
                val platform = platforms.firstOrNull { it.matches(baseName) } ?: continue
                if (total + entry.size > MAX_TOTAL) break
                val target = uniqueTarget(dir, Naming.sanitize(baseName))
                zf.getInputStream(entry).use { ins ->
                    target.outputStream().use { out -> ins.copyTo(out, 128 * 1024) }
                }
                total += target.length()
                saved += target.name
            }
        }
        return saved
    }

    fun uniqueTarget(dir: File, name: String): File {
        var target = File(dir, name)
        if (!target.exists()) return target
        var index = 2
        while (index < 10000) {
            val candidate = File(dir, Naming.withIndex(name, index))
            if (!candidate.exists()) return candidate
            index++
        }
        return File(dir, Naming.withIndex(name, System.currentTimeMillis().toInt()))
    }
}
