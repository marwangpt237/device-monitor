package com.workmonitor

import android.app.IntentService
import android.content.Context
import android.content.Intent
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Uploads collected daily logs to the server, then (optionally) clears them.
 * Also sends heartbeat/pulse to check for revoke/kill status.
 */
class LogUploaderService : IntentService("LogUploaderService") {

    override fun onHandleIntent(intent: Intent?) {
        runCatching { ensureRegistered() }
        runCatching { DeviceReporter.report(this) }
        runCatching { heartbeat() }
        runCatching { uploadLogs() }
    }

    /**
     * Idempotent enrollment: POST /device/register. Safe to call every cycle;
     * the server ignores an already-enrolled device id (returns enrolled=true).
     * This is what makes the device appear in the admin console.
     */
    private fun ensureRegistered() {
        val url = URL("${AppConfig.SERVER_URL}/device/register")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            val body = JSONObject().apply {
                put("device_id", AppConfig.deviceId(this@LogUploaderService))
                put("app_version", "1.0.0")
                put("os_version", android.os.Build.VERSION.RELEASE)
            }
            val os = DataOutputStream(conn.outputStream)
            os.writeBytes(body.toString())
            os.flush(); os.close()
            conn.responseCode // read to trigger request; ignore result
        } finally {
            conn.disconnect()
        }
    }

    /** POST /device/pulse — checks status, sends location/battery, executes remote commands. */
    private fun heartbeat() {
        val url = URL("${AppConfig.SERVER_URL}/device/pulse")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            val loc = lastLocation()
            val bat = lastBattery()
            val body = JSONObject().apply {
                put("device_id", AppConfig.deviceId(this@LogUploaderService))
                put("app_version", "1.0.0")
                put("battery_pct", bat.first)
                put("charging", bat.second)
                if (loc != null) {
                    put("lat", loc.first)
                    put("lng", loc.second)
                }
            }
            val os = DataOutputStream(conn.outputStream)
            os.writeBytes(body.toString())
            os.flush(); os.close()
            val code = conn.responseCode
            if (code == 200) {
                val resp = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(resp)
                if (json.optString("status") == "revoked") {
                    Notifier.showRevoked(this)
                }
                // Execute remote commands (lock / wipe / policy) delivered by admin.
                val cmds = json.optJSONArray("commands")
                if (cmds != null && cmds.length() > 0) {
                    val list = (0 until cmds.length()).map { cmds.getJSONObject(it) }
                    CommandExecutor.execute(this, list)
                }
            }
        } catch (_: Exception) {
        } finally {
            conn.disconnect()
        }
    }

    /** Single best-effort location fix (Fused/framework). Returns lat/lng or null. */
    private fun lastLocation(): Pair<Double, Double>? {
        return try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            val providers = listOf(android.location.LocationManager.GPS_PROVIDER,
                android.location.LocationManager.NETWORK_PROVIDER)
            for (p in providers) {
                if (!lm.isProviderEnabled(p)) continue
                val l = lm.getLastKnownLocation(p) ?: continue
                return Pair(l.latitude, l.longitude)
            }
            null
        } catch (_: Exception) { null }
    }

    private fun lastBattery(): Pair<Int, Int> {
        return try {
            val bm = getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            val pct = if (android.os.Build.VERSION.SDK_INT >= 21) bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) else -1
            val ch = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_STATUS) == android.os.BatteryManager.BATTERY_STATUS_CHARGING
            Pair(pct, if (ch) 1 else 0)
        } catch (_: Exception) { Pair(-1, 0) }
    }

    /** Upload the current day's (or pending) log files via multipart POST. */
    private fun uploadLogs() {
        val dir = File(filesDir, "logs")
        val files = dir.listFiles()?.filter { it.extension != "sent" } ?: return
        for (f in files) {
            if (!f.isFile) continue
            try {
                val success = postLog(f)
                if (success) {
                    if (AppConfig.DELETE_AFTER_UPLOAD) {
                        f.delete()
                    } else {
                        f.renameTo(File(f.absolutePath + ".sent"))
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun postLog(file: java.io.File): Boolean {
        val date = file.name.removeSuffix(".log") // "2026-08-07" (LoggerWriter names files yyyy-MM-dd)
        val url = URL("${AppConfig.SERVER_URL}/device/logs/${URLEncoder.encode(date, "UTF-8")}")
        val conn = url.openConnection() as HttpURLConnection
        try {
            val boundary = "---boundary-${System.currentTimeMillis()}"
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            val dos = DataOutputStream(conn.outputStream)
            dos.writeBytes("--$boundary\r\n")
            dos.writeBytes("Content-Disposition: form-data; name=\"device_id\"\r\n\r\n${AppConfig.deviceId(this)}\r\n")
            dos.writeBytes("--$boundary\r\n")
            dos.writeBytes("Content-Disposition: form-data; name=\"log\"; filename=\"${file.name}\"\r\n")
            dos.writeBytes("Content-Type: text/plain\r\n\r\n")
            dos.write(file.readBytes())
            dos.writeBytes("\r\n--$boundary--\r\n")
            dos.flush(); dos.close()
            return conn.responseCode == 200
        } catch (_: Exception) {
            return false
        } finally {
            conn.disconnect()
        }
    }
}