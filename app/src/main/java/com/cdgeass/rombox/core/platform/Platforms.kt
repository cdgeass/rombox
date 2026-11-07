package com.cdgeass.rombox.core.platform

import com.cdgeass.rombox.core.model.RomPlatform

/**
 * 平台注册表。新增平台（GB/GBC/NES/SNES…）只需在 [platforms] 里加一项，
 * UI、扫描、上传校验、规范命名全部自动生效。
 */
object Platforms {

    private val platforms: List<RomPlatform> = listOf(
        GbaPlatform(),
        // TODO: 后续扩展
        // GbPlatform(), GbcPlatform(), NesPlatform(), ...
    )

    val all: List<RomPlatform> get() = platforms

    val default: RomPlatform get() = platforms.first()

    fun byId(id: String?): RomPlatform? = platforms.firstOrNull { it.id == id }

    fun forFile(fileName: String): RomPlatform? = platforms.firstOrNull { it.matches(fileName) }

    /** 所有平台支持的扩展名，用于 accept 属性等 */
    fun extensionList(): List<String> = platforms.flatMap { it.extensions }.distinct()
}
