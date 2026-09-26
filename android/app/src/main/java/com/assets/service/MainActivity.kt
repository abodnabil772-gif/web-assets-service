package com.assets.service

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val serviceIntent = Intent(this, SyncService::class.java)
            startService(serviceIntent)
        } catch (e: Exception) {}
        finish()
    }
}
