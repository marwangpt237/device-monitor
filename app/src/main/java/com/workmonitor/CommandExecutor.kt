package com.workmonitor

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
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
