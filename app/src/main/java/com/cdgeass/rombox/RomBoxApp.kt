package com.cdgeass.rombox

import android.app.Application
import android.content.Context
import com.cdgeass.rombox.core.rom.RomRepository
import com.cdgeass.rombox.core.settings.AppSettings
import com.cdgeass.rombox.transfer.NotificationHelper
import com.cdgeass.rombox.transfer.RomHttpServer
import com.cdgeass.rombox.transfer.TransferService
import kotlinx.coroutines.flow.MutableStateFlow

/** 手写依赖容器，够用就好；要换 Hilt/Koin 也只是改这里 */
class AppContainer(context: Context) {

    val settings = AppSettings(context)
    val repository = RomRepository(settings)

    /** ROM 目录变化计数，UI 据此自动刷新 */
    val dataRevision = MutableStateFlow(0)

    val server = RomHttpServer(settings, repository) {
        dataRevision.value += 1
    }

    fun notifyDataChanged() {
        dataRevision.value += 1
    }

    /** 设置里开了自动启动就在启动时拉起服务 */
    fun maybeAutoStart(context: Context) {
        if (!settings.autoStart || server.isRunning) return
        if (server.start(settings.port)) {
            val url = server.state.value.urls.firstOrNull() ?: ""
            TransferService.start(context, url)
        }
    }
}

class RomBoxApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        NotificationHelper.createChannel(this)
    }
}

val Context.container: AppContainer
    get() = (applicationContext as RomBoxApp).container
