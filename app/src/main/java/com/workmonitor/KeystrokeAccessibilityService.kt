package com.workmonitor

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.view.accessibility.AccessibilityEvent

/**
 * Consent-grounded AccessibilityService for the company-owned device.
 * Captures user text input and window/app focus, logs to the daily file.
 */
class KeystrokeAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        LoggerWriter.init(filesDir)
        startForegroundServiceNotification()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        // Capture text input: view with changed/typed text
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
            val text = event.text?.joinToString("")?.trim()
            if (!text.isNullOrEmpty()) {
                val pkg = event.packageName?.toString() ?: "?"
                val cls = event.className?.toString() ?: "?"
                val activity = cls.substringAfterLast('.')
                LoggerWriter.write(pkg, activity, text)
            }
        }

        // Capture app/window focus transitions (which app is in foreground)
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            val pkg = event.packageName?.toString() ?: "?"
            val cls = event.className?.toString() ?: "?"
            val activity = cls.substringAfterLast('.')
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                LoggerWriter.write("[FOCUS]", pkg, "activity=$activity")
            }
        }
    }

    override fun onInterrupt() {
        LoggerWriter.close()
    }

    override fun onDestroy() {
        LoggerWriter.close()
        super.onDestroy()
    }

    private fun startForegroundServiceNotification() {
        // AccessibilityService is a bound system service, but showing a persistent
        // notification makes the active status visible (consent clarity) and helps
        // keep the process alive. Requires FOREGROUND_SERVICE on API 26+.
        val channelId = "work_monitor"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Work Monitoring", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification: Notification =
            android.app.Notification.Builder(this, channelId)
                .setContentTitle("Work Monitoring")
                .setContentText("This company-owned device is monitored per the employee agreement.")
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setOngoing(true)
                .build()
        // AccessibilityService is a bound system service (cannot call startForeground),
        // so post an ongoing notification to make the active status visible (consent clarity).
        nm.notify(1001, notification)
    }
}