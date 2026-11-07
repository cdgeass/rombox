package com.cdgeass.rombox.core.settings

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import java.io.File

/** 应用配置，SharedPreferences 持久化 */
class AppSettings(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** ROM 存储目录（绝对路径） */
    var romDirPath: String
        get() = sp.getString(KEY_ROM_DIR, null) ?: defaultRomDir()
        set(value) = sp.edit().putString(KEY_ROM_DIR, value).apply()

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

    companion object {
        const val DEFAULT_PORT = 8080
        const val DEFAULT_PLATFORM = "gba"

        private const val NAME = "rombox_settings"
        private const val KEY_ROM_DIR = "rom_dir"
        private const val KEY_PORT = "port"
        private const val KEY_PLATFORM = "platform"
        private const val KEY_AUTO_START = "auto_start"

        /** 优先用掌机已有的 ROM 目录，其次通用目录 */
        fun defaultRomDir(): String {
            val external = Environment.getExternalStorageDirectory().absolutePath
            val candidates = listOf(
                "$external/PocketGames/roms/gba",
                "$external/roms/gba",
                "$external/ROMs/gba",
                "$external/ROMs",
            )
            candidates.firstOrNull { File(it).isDirectory }?.let { return it }
            return "$external/ROMs"
        }
    }
}
