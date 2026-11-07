package com.cdgeass.rombox.core.platform

import com.cdgeass.rombox.core.model.RomHeader
import com.cdgeass.rombox.core.model.RomPlatform

/**
 * Game Boy Advance。
 *
 * 头部布局（GBATEK）：
 *  - 0xA0..0xAB  12 字节标题
 *  - 0xAC..0xAF  游戏码，第 4 位是区域
 *  - 0xB0..0xB1  厂商码
 *  - 0xB2        固定值 0x96
 *  - 0xBC        版本号
 *  - 0xBD        头部校验补码
 */
class GbaPlatform : RomPlatform {

    override val id: String = "gba"
    override val displayName: String = "Game Boy Advance"
    override val shortName: String = "GBA"
    override val extensions: Set<String> = setOf(".gba", ".agb")
    override val headerReadSize: Int = 0xC0

    override fun parseHeader(head: ByteArray): RomHeader {
        if (head.size < 0xC0) {
            return RomHeader(
                platformId = id,
                valid = false,
                problems = listOf("文件太小，读不到完整卡带头部"),
            )
        }
        val title = readAscii(head, 0xA0, 12)
        val gameCode = readAscii(head, 0xAC, 4)
        val makerCode = readAscii(head, 0xB0, 2)
        val version = head[0xBC].toInt() and 0xFF

        val problems = mutableListOf<String>()
        if ((head[0xB2].toInt() and 0xFF) != HEADER_FIXED) {
            problems += "头部固定值异常 (0xB2 != 0x96)"
        }
        var sum = 0
        for (i in 0xA0..0xBC) sum += head[i].toInt() and 0xFF
        // GBATEK: chk = -(0x19 + sum(0xA0..0xBC)) & 0xFF，0xBD 处存的就是这个补码
        val complement = (-(0x19 + sum)) and 0xFF
        if (complement != (head[0xBD].toInt() and 0xFF)) {
            problems += "头部校验和错误"
        }

        val region = gameCode.takeIf { it.length == 4 }
            ?.let { REGION_BY_CHAR[it[3].uppercaseChar()] }

        return RomHeader(
            platformId = id,
            title = title.ifBlank { null },
            gameCode = gameCode.ifBlank { null },
            makerCode = makerCode.ifBlank { null },
            version = version,
            region = region,
            valid = problems.isEmpty(),
            problems = problems,
        )
    }

    private fun readAscii(bytes: ByteArray, offset: Int, length: Int): String {
        val sb = StringBuilder(length)
        val end = (offset + length).coerceAtMost(bytes.size)
        for (i in offset until end) {
            val c = bytes[i].toInt() and 0xFF
            if (c == 0) break
            if (c in 0x20..0x7E) sb.append(c.toChar())
        }
        return sb.toString().trim()
    }

    companion object {
        private const val HEADER_FIXED = 0x96

        /** 游戏码第 4 位 -> 区域 */
        private val REGION_BY_CHAR = mapOf(
            'J' to "Japan",
            'E' to "USA",
            'P' to "Europe",
            'D' to "Germany",
            'F' to "France",
            'I' to "Italy",
            'S' to "Spain",
            'K' to "Korea",
            'C' to "China",
            'X' to "Other",
            'Y' to "Other",
            'Z' to "Other",
        )
    }
}
