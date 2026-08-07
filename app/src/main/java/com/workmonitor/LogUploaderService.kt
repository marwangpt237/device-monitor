package com.workmonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Foreground upload + monitoring service. Runs a loop every UPLOAD_INTERVAL_MS:
 * register (enroll), report inventory, heartbeat (status + location + commands),
 * upload daily logs. Uses startForeground with a persistent notification so
 * modern Android keeps it alive in the background.
 */
class LogUploaderService : Service() {

    companion object { const val NOTIF_ID = 1001 }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scheduleNext()
        return START_STICKY
    }

    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** Run one loop cycle on a background thread, then re-arm. Network-safe. */
    private fun scheduleNext() {
        executor.execute {
            runCatching { loop() }
            try { Thread.sleep(AppConfig.UPLOAD_INTERVAL_MS) } catch (_: InterruptedException) {}
            runCatching { loop() }
        }
    }

    private fun loop() {
        runCatching { ensureRegistered() }
        runCatching { DeviceReporter.report(this) }
        runCatching { heartbeat() }
        runCatching { uploadLogs() }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel("monitoring", "Monitoring", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }
        val contentIntent = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, "monitoring")
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder
            .setContentTitle("Work monitoring active")
            .setContentText("Syncing device status to company server")
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
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
            // 1) prefer a recently-cached last-known fix
            var best: android.location.Location? = null
            for (p in providers) {
                if (!lm.isProviderEnabled(p)) continue
                val l = lm.getLastKnownLocation(p) ?: continue
                if (best == null || l.time > best.time) best = l
            }
            if (best != null && System.currentTimeMillis() - best.time < 15 * 60 * 1000L) {
                return Pair(best.latitude, best.longitude)
            }
            // 2) actively request a fresh single fix (blocking, capped timeout)
            val latch = java.util.concurrent.CountDownLatch(1)
            val holder = arrayOfNulls<android.location.Location>(1)
            val listener = android.location.LocationListener {
                holder[0] = it; latch.countDown()
            }
            val looper = android.os.Looper.myLooper()
            for (p in providers) {
                if (!lm.isProviderEnabled(p)) continue
                try {
                    if (p == android.location.LocationManager.NETWORK_PROVIDER && Build.VERSION.SDK_INT >= 31 &&
                        checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                        continue
                    if (p == android.location.LocationManager.GPS_PROVIDER && checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                        continue
                    lm.requestSingleUpdate(p, listener, looper)
                } catch (_: Exception) {}
            }
            latch.await(6, java.util.concurrent.TimeUnit.SECONDS)
            for (p in providers) {
                try { lm.removeUpdates(listener) } catch (_: Exception) {}
            }
            val fresh = holder[0]
                ?: (providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                       .maxByOrNull { it.time })
            if (fresh != null) return Pair(fresh.latitude, fresh.longitude)
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