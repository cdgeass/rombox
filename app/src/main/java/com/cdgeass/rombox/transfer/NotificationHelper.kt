package com.cdgeass.rombox.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.cdgeass.rombox.R
import com.cdgeass.rombox.ui.MainActivity

object NotificationHelper {

    const val CHANNEL_ID = "rombox_transfer"
    const val NOTIFICATION_ID = 0x5201

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "无线传输",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "无线传输服务运行时显示"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    fun build(context: Context, url: String): Notification {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_wifi)
            .setContentTitle("无线传输已开启")
            .setContentText(url)
            .setContentIntent(pending)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
    }
}
