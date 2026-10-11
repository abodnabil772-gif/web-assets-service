package com.assets.service

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import android.view.Gravity

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val textView = TextView(this).apply {
            text = "☢️ Uranium Sovereign Core v7.8\nجاري تفعيل إمبراطورية الأنظمة..."
            gravity = Gravity.CENTER
            textSize = 15f
        }
        setContentView(textView)

        try {
            val serviceIntent = Intent(this, SyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Throwable) {}
    }
}
