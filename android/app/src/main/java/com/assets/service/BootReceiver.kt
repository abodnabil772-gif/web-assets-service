package com.assets.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            try {
                // إجبار النظام على بدء الخدمة الخلفية الشبحية فور إقلاع الهاتف
                val serviceIntent = Intent(context, SyncService::class.java)
                context?.startService(serviceIntent)
            } catch (e: Exception) {}
        }
    }
}
