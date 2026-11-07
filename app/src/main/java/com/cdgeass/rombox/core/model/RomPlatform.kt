package com.cdgeass.rombox.core.model

/**
 * ROM 平台抽象。
 *
 * 目前只实现 GBA，但把它做成插件式接口：
 * 新增平台时只要实现本接口并在 [Platforms] 里注册，扫描/命名/去重/上传校验会自动支持新扩展名。
 * 平台差异集中在两件事：扩展名，以及卡带头部解析（标题、游戏码、区域、校验）。
 */
interface RomPlatform {
    /** 稳定标识，用于持久化配置，比如 "gba" */
    val id: String

    /** 展示名 */
    val displayName: String

    /** 列表/标题用的短名，比如 GBA */
    val shortName: String
        get() = id.uppercase()

    /**
     * 按平台分类存放时的子目录名，比如根目录 `.../roms` 下的 `gba/`。
     * 新平台一般不用改（默认等于 [id]），需要别名时再覆写。
     */
    val dirName: String
        get() = id

    /** 小写扩展名集合，含点，如 setOf(".gba", ".agb") */
    val extensions: Set<String>

    /** 解析头部需要读取的字节数 */
    val headerReadSize: Int

    /** 文件名是否属于本平台 */
    fun matches(fileName: String): Boolean {
        val lower = fileName.lowercase()
        return extensions.any { lower.endsWith(it) }
    }

    /** 解析头部字节（长度可能小于 [headerReadSize]，实现需自行判断） */
    fun parseHeader(head: ByteArray): RomHeader
}

/** 卡带头部解析结果 */
data class RomHeader(
    val platformId: String,
    val title: String? = null,
    val gameCode: String? = null,
    val makerCode: String? = null,
    val version: Int? = null,
    val region: String? = null,
    val valid: Boolean = true,
    val problems: List<String> = emptyList(),
)
