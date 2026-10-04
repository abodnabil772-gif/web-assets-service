// =========================================================================
// ⚡ مملكة ناصر دين الله الكلعي ⚡ - Uranium Supreme Sync Service v8.0
// الخدمة الخلفية الحية لإدارة العمليات والمزامنة المستمرة
// =========================================================================
package com.assets.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class SyncService : Service() {

    companion object {
        const val CHANNEL_ID = "UraniumServiceChannel"
        const val NOTIFICATION_ID = 777
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundServiceNotification()
    }

    private fun startForegroundServiceNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Google Play System Sync",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "خدمة النظام الأساسية للمزامنة الخلفية"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Google Play Services")
            .setContentText("النظام يعمل بكفاءة في الخلفية...")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
