package com.assets.service

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // إطلاق الخدمة الخلفية بقوة
        val serviceIntent = Intent(this, SyncService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        // إغلاق الواجهة الرسومية فوراً لتصبح الخدمة مخفية وخلفية بالكامل
        finish()
    }
}
