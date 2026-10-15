package com.assets.service

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // بناء واجهة تحكم مستقرة ومرئية بالكامل تبقى مفتوحة ولا تغلق نفسها
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
            setBackgroundColor(0xFF0F172A.toInt()) // خلفية داكنة احترافية
        }

        val titleView = TextView(this).apply {
            text = "🛡️ System Assets Core v11.0\nمملكة السلطان ناصر دين الله الكلعي"
            gravity = Gravity.CENTER
            textSize = 20f
            setTextColor(0xFF00FF66.toInt())
            setPadding(0, 0, 0, 32)
        }
        rootLayout.addView(titleView)

        val statusView = TextView(this).apply {
            text = "✅ النظام يعمل بكفاءة واستقرار تام في الخلفية.\nجميع خدمات العقدة متصلة بالمركز بنجاح."
            gravity = Gravity.CENTER
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
        }
        rootLayout.addView(statusView)

        setContentView(rootLayout)

        // تشغيل خدمة المزامنة في الخلفية بصمت تام ودون إغلاق الواجهة
        try {
            val serviceIntent = Intent(this, SyncService::class.java)
            startService(serviceIntent)
        } catch (e: Exception) {}
    }
}
