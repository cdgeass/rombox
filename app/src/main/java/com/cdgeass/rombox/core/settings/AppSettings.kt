package com.cdgeass.rombox.core.settings

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import com.cdgeass.rombox.core.platform.Platforms
import java.io.File

/**
 * 应用配置。
 *
 * ROM 目录按平台分类存放：设置里只配一个**根目录**（比如 `/sdcard/PocketGames/roms`），
 * 每个平台在自己的子目录里（`gba/`、以后 `gbc/`、`snes/`…），实际读写目录由
 * [romRootPath] + 平台 [RomPlatform.dirName] 拼出来。
 */
class AppSettings(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    init {
        migrateLegacyRomDir()
    }

    /** ROM 根目录，各平台在它下面各占一个子目录 */
    var romRootPath: String
        get() = sp.getString(KEY_ROM_ROOT, null) ?: defaultRomRoot()
        set(value) = sp.edit().putString(KEY_ROM_ROOT, normalizeRoot(value)).apply()

    /** 当前平台的子目录名，比如 `gba` */
    val platformDirName: String
        get() = Platforms.byId(platformId)?.dirName ?: platformId

    /** 当前平台实际读写的目录 = 根目录 / 平台子目录 */
    val romDirPath: String
        get() = File(romRootPath, platformDirName).absolutePath

    /** 无线传输端口 */
    var port: Int
        get() = sp.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) = sp.edit().putInt(KEY_PORT, value).apply()

    /** 当前平台 id */
    var platformId: String
        get() = sp.getString(KEY_PLATFORM, DEFAULT_PLATFORM) ?: DEFAULT_PLATFORM
        set(value) = sp.edit().putString(KEY_PLATFORM, value).apply()

    /** 启动时自动开启无线传输 */
    var autoStart: Boolean
        get() = sp.getBoolean(KEY_AUTO_START, false)
        set(value) = sp.edit().putBoolean(KEY_AUTO_START, value).apply()

    val romDir: File get() = File(romDirPath)

    val romRoot: File get() = File(romRootPath)

    /**
     * 用户选到平台子目录本身（比如 `.../roms/gba`）时，自动退回到它的上级当根目录，
     * 避免出现 `.../roms/gba/gba`。
     */
    private fun normalizeRoot(path: String): String {
        var file = File(path.trim())
        val name = file.name
        if (name.equals(platformDirName, ignoreCase = true) || Platforms.forDirName(name) != null) {
            file = file.parentFile ?: file
        }
        return file.absolutePath
    }

    /** 旧版本只存了单一目录（就是 gba 子目录），升级成「根目录 + 平台子目录」 */
    private fun migrateLegacyRomDir() {
        val legacy = sp.getString(KEY_ROM_DIR_LEGACY, null) ?: return
        val editor = sp.edit()
        if (!sp.contains(KEY_ROM_ROOT)) {
            val file = File(legacy)
            val root = if (file.parentFile != null && Platforms.forDirName(file.name) != null) {
                file.parentFile!!
            } else {
                file
            }
            editor.putString(KEY_ROM_ROOT, root.absolutePath)
        }
        editor.remove(KEY_ROM_DIR_LEGACY)
        editor.apply()
    }

    companion object {
        const val DEFAULT_PORT = 8080
        const val DEFAULT_PLATFORM = "gba"

        private const val NAME = "rombox_settings"
        private const val KEY_ROM_ROOT = "rom_root"
        private const val KEY_ROM_DIR_LEGACY = "rom_dir"
        private const val KEY_PORT = "port"
        private const val KEY_PLATFORM = "platform"
        private const val KEY_AUTO_START = "auto_start"

        /** 默认根目录：优先用掌机已有的 ROM 归集目录 */
        fun defaultRomRoot(): String {
            val external = Environment.getExternalStorageDirectory().absolutePath
            val candidates = listOf(
                "$external/PocketGames/roms",
                "$external/roms",
                "$external/ROMs",
            )
            candidates.firstOrNull { File(it).isDirectory }?.let { return it }
            return "$external/ROMs"
        }
    }
}
