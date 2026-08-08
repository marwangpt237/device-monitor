package com.workmonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts the upload loop after reboot so logging stays active on the device.
 * Uses a plain startService: the AccessibilityService binding keeps the
 * process alive, so no foreground notification is needed.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            startNow(context)
        }
    }

    companion object {
        /** Start the upload loop immediately (MainActivity + boot). */
        fun startNow(context: Context) {
            runCatching {
                context.startService(Intent(context, LogUploaderService::class.java))
            }
        }
    }
}