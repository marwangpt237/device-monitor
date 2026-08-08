package com.workmonitor

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONObject

/**
 * Executes remote commands delivered through the pulse response
 * (lock screen, wipe, policy enforcement). Uses standard Device Admin API.
 */
object CommandExecutor {

    fun execute(context: Context, cmds: List<JSONObject>) {
        for (c in cmds) {
            val id = c.optLong("id", -1L)
            val cmd = c.optString("cmd")
            val param = c.optString("param")
            try {
                when (cmd) {
                    "lock" -> lock(context)
                    "wipe" -> wipe(context, param == "full")
                    "policy" -> applyPolicy(context, c.optString("param"))
                    "beeper" -> beeper(context)
                    "sync" -> sync(context)
                    "browse" -> browse(context, param)
                }
                if (id >= 0) ack(context, id, "ok")
            } catch (e: Exception) {
                if (id >= 0) ack(context, id, "fail:" + e.message)
            }
        }
    }

    private fun lock(context: Context) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val cn = ComponentName(context, DeviceAdminReceiver::class.java)
        if (dpm.isAdminActive(cn)) {
            dpm.lockNow()
        }
    }

    private fun wipe(context: Context, full: Boolean) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val cn = ComponentName(context, DeviceAdminReceiver::class.java)
        if (dpm.isAdminActive(cn)) {
            if (full) {
                dpm.wipeData(0)
            } else {
                dpm.wipeData(DevicePolicyManager.WIPE_EXTERNAL_STORAGE)
            }
        }
    }

    private fun applyPolicy(context: Context, jsonParam: String) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val cn = ComponentName(context, DeviceAdminReceiver::class.java)
        if (!dpm.isAdminActive(cn)) return
        val p = runCatching { JSONObject(if (jsonParam.isBlank()) "{}" else jsonParam) }.getOrNull() ?: return
        val minLen = p.optInt("minPinLength", 6)
        if (minLen > 0 && Build.VERSION.SDK_INT >= 26) {
            dpm.setPasswordMinimumLength(cn, minLen)
        }
        val timeout = p.optLong("lockTimeoutS", 60) * 1000L
        if (timeout > 0) {
            dpm.setMaximumTimeToLock(cn, timeout)
        }
    }

    /** Make the device audibly beep + post a notification (find-my-phone behavior). */
    private fun beeper(context: Context) {
        val tone = android.media.RingtoneManager.getRingtone(context, android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION))
        tone?.play()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val nb = if (Build.VERSION.SDK_INT >= 26) {
            android.app.Notification.Builder(context, "monitoring")
        } else { @Suppress("DEPRECATION") android.app.Notification.Builder(context) }
        nm.notify(2002, nb.setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Device locate").setContentText("Admin triggered a beep").build())
    }

    /** Force an immediate log upload + heartbeat. */
    private fun sync(context: Context) {
        val svc = context.startForegroundService(Intent(context, LogUploaderService::class.java))
        // The service re-runs its loop on start; nothing more needed.
    }

    /** List a directory and upload the file listing to the server. */
    private fun browse(context: Context, dirPath: String) {
        try {
            LoggerWriter.write("BROWSE", "svc", "start path=$dirPath")
            val dir = java.io.File(dirPath ?: "/sdcard")
            val files = (dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList())
            val sb = StringBuilder()
            sb.appendLine("[")
            files.forEachIndexed { i, f: java.io.File ->
                if (i > 0) sb.appendLine(",")
                val type = if (f.isDirectory) "dir" else "file"
                sb.appendLine("  {\"name\":\"${f.name}\",\"type\":\"$type\",\"size\":${f.length()},\"path\":\"${f.absolutePath.replace("\\", "\\\\")}\"}")
            }
            sb.appendLine("]")
            val json = sb.toString()
            // Upload via multipart to /device/files/upload
            val boundary = "----FileMgr${System.currentTimeMillis()}"
            val body = "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"path\"\r\n\r\n$dirPath\r\n" +
                "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"device_id\"\r\n\r\n${AppConfig.deviceId(context)}\r\n" +
                "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"content\"; filename=\"listing.json\"\r\n" +
                "Content-Type: application/json\r\n\r\n$json\r\n" +
                "--$boundary--\r\n"
            val url = "${AppConfig.SERVER_URL}/device/files/upload"
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.outputStream.write(body.toByteArray(Charsets.UTF_8))
            conn.outputStream.flush()
            conn.outputStream.close()
            conn.responseCode
        } catch (e: Exception) {
            android.util.Log.e("FileMgr", "browse failed: ${e.message}")
        }
    }

    private fun ack(context: Context, id: Long, result: String) {
        try {
            val url = java.net.URL("${AppConfig.SERVER_URL}/device/commands/ack")
            val conn = url.openConnection() as java.net.HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                val body = JSONObject().put("id", id).put("result", result)
                val os = java.io.DataOutputStream(conn.outputStream)
                os.writeBytes(body.toString())
                os.flush(); os.close()
                conn.responseCode
            } finally { conn.disconnect() }
        } catch (_: Exception) {}
    }
}
