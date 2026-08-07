package com.workmonitor

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Collects device inventory + compliance facts and reports them via
 * POST /device/inventory. Pure background collection (no covert behavior).
 */
object DeviceReporter {

    fun report(context: Context) {
        val body = JSONObject().apply {
            put("device_id", AppConfig.deviceId(context))
            put("model", Build.MODEL)
            put("manufacturer", Build.MANUFACTURER)
            put("os_version", Build.VERSION.RELEASE)
            put("sdk", Build.VERSION.SDK_INT)
            put("security_patch", Build.VERSION.SECURITY_PATCH)
            put("build_number", Build.DISPLAY ?: Build.VERSION.INCREMENTAL)
            put("android_id", Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID))
            put("rooted", if (detectRoot()) 1 else 0)
            put("unknown_sources", if (canInstallUnknown(context)) 1 else 0)
            put("unlocked_boot", if (isBootloaderUnlocked()) 1 else 0)
            put("apps", collectApps(context))
        }
        val b = battery(context)
        if (b.first >= 0) put("battery_pct", b.first)
        if (b.second >= 0) put("charging", b.second)
        val st = storage()
        put("storage_total", st.first)
        put("storage_free", st.second)

        try {
            val url = URL("${AppConfig.SERVER_URL}/device/inventory")
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                val os = DataOutputStream(conn.outputStream)
                os.writeBytes(body.toString())
                os.flush(); os.close()
                conn.responseCode
            } finally { conn.disconnect() }
        } catch (_: Exception) {}
    }

    private fun battery(ctx: Context): Pair<Int, Int> {
        return try {
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val pct = if (Build.VERSION.SDK_INT >= 21) bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) else -1
            val charge = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
            Pair(pct, if (charge == BatteryManager.BATTERY_STATUS_CHARGING) 1 else 0)
        } catch (_: Exception) { Pair(-1, -1) }
    }

    private fun storage(): Pair<Long, Long> {
        return try {
            val st = StatFs(Environment.getDataDirectory().path)
            val total = st.blockCountLong * st.blockSizeLong
            val free = st.availableBlocksLong * st.blockSizeLong
            Pair(total, free)
        } catch (_: Exception) { Pair(0L, 0L) }
    }

    private fun collectApps(ctx: Context): JSONArray {
        val arr = JSONArray()
        try {
            val pm = ctx.packageManager
            val apps = pm.getInstalledApplications(0)
            for (a in apps) {
                if (arr.length() >= 500) break
                val jo = JSONObject()
                jo.put("package", a.packageName)
                jo.put("label", runCatching { pm.getApplicationLabel(a).toString() }.getOrDefault(a.packageName))
                arr.put(jo)
            }
        } catch (_: Exception) {}
        return arr
    }

    fun hasLocationPermission(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 23) return true
        return ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
               ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun canInstallUnknown(ctx: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.packageManager.canRequestPackageInstalls()
            } else {
                Settings.Secure.getInt(ctx.contentResolver, Settings.Secure.INSTALL_NON_MARKET_APPS, 0) == 1
            }
        } catch (_: Exception) { false }
    }

    private fun isBootloaderUnlocked(): Boolean {
        return try {
            Build.FINGERPRINT != null &&
            (Build.TAGS ?: "").contains("test-keys") ||
            runCatching { Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
                .invoke(null, "ro.boot.verifiedbootstate") as String }.getOrNull() == "orange"
        } catch (_: Exception) { false }
    }

    private fun detectRoot(): Boolean {
        val paths = arrayOf(
            "/system/app/Superuser.apk", "/sbin/su", "/system/bin/su", "/system/xbin/su",
            "/data/local/xbin/su", "/data/local/bin/su", "/system/sd/xbin/su", "/data/xbin/su",
            "/system/bin/failsafe/su", "/data/local/su"
        )
        for (p in paths) if (java.io.File(p).exists()) return true
        return try {
            val p = Runtime.getRuntime().exec("which su")
            p.inputStream.reader().readText().trim().isNotEmpty()
        } catch (_: Exception) { false }
    }
}
