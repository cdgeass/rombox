package com.cdgeass.rombox.transfer

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.cdgeass.rombox.container

/**
 * 前台服务：保证 App 切到后台后无线传输还活着。
 * 服务本身不做传输，真正的服务器在 AppContainer 里的 [RomHttpServer]。
 */
class TransferService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra(EXTRA_URL) ?: "无线传输运行中"
        val notification = NotificationHelper.build(this, url)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NotificationHelper.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NotificationHelper.NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { applicationContext.container.server.stop() }
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_URL = "url"

        fun start(context: Context, url: String) {
            val intent = Intent(context, TransferService::class.java).putExtra(EXTRA_URL, url)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TransferService::class.java))
        }
    }
}
