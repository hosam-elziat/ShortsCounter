package com.reelcounter.app.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.reelcounter.app.services.FloatingVRGogglesService
import com.reelcounter.app.utils.PermissionHelper

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            if (PermissionHelper.canDrawOverlays(context) && PermissionHelper.isAccessibilityServiceEnabled(context)) {
                val serviceIntent = Intent(context, FloatingVRGogglesService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }
}