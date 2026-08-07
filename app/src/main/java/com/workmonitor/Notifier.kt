package com.workmonitor

import android.app.AlertDialog
import android.content.Context
import android.content.Intent

/** Shows a non-dismissable blocker when a device has been revoked by admin. */
object Notifier {
    fun showRevoked(context: Context) {
        AlertDialog.Builder(context)
            .setTitle("Device Not Authorized")
            .setMessage("This device has been disabled by the administrator. Contact IT.")
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ -> }
            .show()
    }
}