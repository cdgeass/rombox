package com.cdgeass.rombox.ui.transfer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.cdgeass.rombox.container
import com.cdgeass.rombox.transfer.ServerState
import com.cdgeass.rombox.transfer.TransferService
import kotlinx.coroutines.flow.StateFlow

class TransferViewModel(app: Application) : AndroidViewModel(app) {

    private val container = app.container
    private val settings = container.settings

    val state: StateFlow<ServerState> = container.server.state

    fun start(port: Int): Boolean {
        val started = container.server.start(port)
        if (started) {
            settings.port = port
            val url = container.server.state.value.urls.firstOrNull() ?: ""
            TransferService.start(getApplication(), url)
        }
        return started
    }

    fun stop() {
        container.server.stop()
        TransferService.stop(getApplication())
    }

    /** 端口改了先存起来，下次开启服务生效 */
    fun savePort(port: Int) {
        settings.port = port
    }
}
