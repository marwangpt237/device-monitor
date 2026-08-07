package com.workmonitor

import android.content.Context
import java.util.UUID

/** Central config. SERVER_URL is set at build time by the company; edit here or inject env. */
object AppConfig {
    /** Live server (device-monitor backend on Out Plane). */
    const val SERVER_URL = "https://devmonto-8080-urwkbqmtg0.outplane.app"

    /** Upload every N seconds while online (short so admin kill/revoke works fast on-site). */
    const val UPLOAD_INTERVAL_MS = 60_000L // 60s

    /** True => fetched logs are cleared from device after successful upload. */
    const val DELETE_AFTER_UPLOAD = true

    /** Daily rollover hour (0 = midnight, 12 = noon). Company specified 12:00. */
    const val ROLLOVER_HOUR = 12

    fun deviceId(context: Context): String {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        var id = prefs.getString("device_id", null)
        if (id == null) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString("device_id", id).apply()
        }
        return id!!
    }

    fun consentAccepted(context: Context): Boolean =
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .getBoolean("consent_accepted", false)

    fun markConsentAccepted(context: Context) =
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("consent_accepted", true).apply()
}