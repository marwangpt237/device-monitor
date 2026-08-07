package com.workmonitor

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts the upload loop after reboot so logging stays active on the device. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            scheduleUpload(context)
        }
    }

    companion object {
        /** Schedule periodic log upload using AlarmManager + foreground service. */
        fun scheduleUpload(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getForegroundService(
                context, 0,
                Intent(context, LogUploaderService::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )
            am.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                System.currentTimeMillis(),
                AppConfig.UPLOAD_INTERVAL_MS,
                pi
            )
        }

        /** Start the foreground service immediately (called from MainActivity). */
        fun startNow(context: Context) {
            context.startForegroundService(Intent(context, LogUploaderService::class.java))
        }
    }
}