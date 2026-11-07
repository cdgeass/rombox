package com.cdgeass.rombox.core.rom

import com.cdgeass.rombox.core.model.RomHeader
import com.cdgeass.rombox.core.util.Fmt
import java.io.File

/** 目录里的一个 ROM 文件 */
data class RomFile(
    val file: File,
    val size: Long,
    val lastModified: Long,
    val header: RomHeader?,
    val crc32: Long?,
    /** 重复分组编号，非空表示该组存在多份 */
    val dupGroup: Int? = null,
) {
    val name: String get() = file.name

    /** 展示名：中文文件名优先，否则取文件名与卡带标题里更长的一个 */
    val title: String
        get() = Naming.canonicalStem(file.nameWithoutExtension, header?.title)

    val region: String? get() = header?.region

    val gameCode: String? get() = header?.gameCode

    /** 列表第二行的说明文字 */
    val subtitle: String
        get() = buildString {
            append(Fmt.bytes(size))
            gameCode?.let { append(" · ").append(it) }
            header?.version?.takeIf { it > 0 }?.let { append(" · Rev ").append(it) }
            header?.takeIf { !it.valid }?.let { append(" · 头部异常") }
            if (dupGroup != null) append(" · 重复")
        }

    val path: String get() = file.absolutePath
}
