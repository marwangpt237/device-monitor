package com.workmonitor

import android.app.IntentService
import android.content.Context
import android.content.Intent
import org.json.JSONObject
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Uploads collected daily logs to the server, then (optionally) clears them.
 * Also sends heartbeat/pulse to check for revoke/kill status.
 */
class LogUploaderService : IntentService("LogUploaderService") {

    override fun onHandleIntent(intent: Intent?) {
        runCatching { heartbeat() }
        runCatching { uploadLogs() }
    }

    /** POST /device/pulse — checks status and sends last-log timestamp. */
    private fun heartbeat() {
        val url = URL("${AppConfig.SERVER_URL}/device/pulse")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            val body = JSONObject().apply {
                put("deviceId", AppConfig.deviceId(this@LogUploaderService))
                put("appVersion", "1.0.0")
            }
            val os = DataOutputStream(conn.outputStream)
            os.writeBytes(body.toString())
            os.flush(); os.close()
            val code = conn.responseCode
            if (code == 200) {
                val resp = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(resp)
                if (json.optString("status") == "revoked") {
                    // Handle revocation: show non-dismissable block or stop
                    Notifier.showRevoked(this)
                }
            }
        } catch (_: Exception) {
        } finally {
            conn.disconnect()
        }
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
        val url = URL("${AppConfig.SERVER_URL}/device/logs/${URLEncoder.encode(file.name, "UTF-8")}")
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
            dos.writeBytes("Content-Disposition: form-data; name=\"deviceId\"\r\n\r\n${AppConfig.deviceId(this)}\r\n")
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