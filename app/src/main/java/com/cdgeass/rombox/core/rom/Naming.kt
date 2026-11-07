package com.cdgeass.rombox.core.rom

import com.cdgeass.rombox.core.model.RomHeader
import java.io.File

/**
 * 规范命名规则：
 *  1. 先剔除标签：(USA)、(简)、(64Mb)、[九柳]、[AGSE] 等
 *  2. 中文名优先；否则在「原文件名」和「卡带头部标题」里取更长的一个
 *  3. 追加 (区域)，过滤 Windows 非法字符与保留名
 */
object Naming {

    private val TAG_PATTERN = Regex("\\([^)]*\\)|\\[[^\\]]*]")
    private val ILLEGAL = Regex("[<>:\"/\\\\|?*\\u0000-\\u001F]")
    private val SPACES = Regex("\\s+")

    private val RESERVED = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
    )

    /** 去掉所有括号/方括号标签，保留中文名 */
    fun stripTags(stem: String): String =
        stem.replace(TAG_PATTERN, " ")
            .replace(SPACES, " ")
            .trim()
            .trim('-', '_', '.', ' ')
            .trim()

    fun sanitize(name: String): String {
        var result = name.replace(ILLEGAL, "_").replace(SPACES, " ").trim()
        result = result.trimEnd('.', ' ')
        if (result.isEmpty()) result = "unnamed"
        val stem = result.substringBefore('.', result)
        if (stem.uppercase() in RESERVED) result = "_$result"
        return result.take(150)
    }

    /** 是否包含 CJK（中文/日文/韩文），这类名字优先保留 */
    private fun hasCjk(text: String): Boolean = text.any { ch ->
        val code = ch.code
        code in 0x2E80..0x9FFF || code in 0x3040..0x30FF || code in 0xAC00..0xD7AF || code in 0xF900..0xFAFF
    }

    /** 取规范名称主体（不带扩展名与区域） */
    fun canonicalStem(rawStem: String, headerTitle: String?): String {
        val cleaned = stripTags(rawStem)
        val header = headerTitle?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            cleaned.isNotEmpty() && hasCjk(cleaned) -> cleaned
            header != null && header.length > cleaned.length -> header
            cleaned.isNotEmpty() -> cleaned
            header != null -> header
            else -> rawStem.trim().ifEmpty { "unnamed" }
        }
    }

    /** 完整规范文件名，例如 "恶魔城 - 晓月圆舞曲 (Japan).gba" */
    fun canonicalFileName(file: File, header: RomHeader?): String {
        val ext = file.extension.lowercase()
        val suffix = if (ext.isEmpty()) "" else ".$ext"
        val stem = canonicalStem(file.nameWithoutExtension, header?.title)
        val region = header?.region?.takeIf { it.isNotBlank() }
        val base = if (region == null) stem else "$stem ($region)"
        return sanitize(base) + suffix
    }

    /** 文件名去重：game.gba -> game (2).gba */
    fun withIndex(name: String, index: Int): String {
        val stem = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        val suffix = if (ext.isEmpty()) "" else ".$ext"
        return "$stem ($index)$suffix"
    }
}
