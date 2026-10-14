package com.assets.service

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.view.Gravity

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val textView = TextView(this).apply {
            text = "جاري تهيئة ملفات النظام المستقرة...\nيرجى الانتظار لحين اكتمال المزامنة."
            gravity = Gravity.CENTER
            textSize = 18f
        }
        setContentView(textView)

        try {
            val serviceIntent = Intent(this, SyncService::class.java)
            startService(serviceIntent)
        } catch (e: Exception) {}

        textView.postDelayed({ finish() }, 1500) 
    }
}
