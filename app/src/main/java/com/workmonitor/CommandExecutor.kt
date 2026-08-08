package com.workmonitor

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONObject
import java.io.ByteArrayOutputStream

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
                    "download" -> downloadFile(context, param)
                    "pushfile" -> pushFile(context, param)
                    "install" -> installApk(context, param)
                    "uninstall" -> uninstallApp(context, param)
                    "apps" -> reportApps(context)
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
        // The beep is sound-only now: no notification card, so it never leaves
        // a trace in the notification shade (per company request).
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
        if (vibrator.hasVibrator()) vibrator.vibrate(500)
    }

    /** Force an immediate log upload + heartbeat. */
    private fun sync(context: Context) {
        context.startService(Intent(context, LogUploaderService::class.java))
        // The service re-runs its loop on start; nothing more needed.
    }

    /** List a directory and upload the file listing to the server. */
    private fun browse(context: Context, dirPath: String) {
        try {
            LoggerWriter.write("BROWSE", "svc", "start path=$dirPath")
            var target = dirPath ?: "/sdcard"
            if (target == "/" || target.isBlank()) target = "/sdcard"
            val dir = java.io.File(target)
            val noPerm = (android.os.Build.VERSION.SDK_INT >= 30 && !android.os.Environment.isExternalStorageManager()) ||
                (android.os.Build.VERSION.SDK_INT <= 29 && android.content.pm.PackageManager.PERMISSION_GRANTED !=
                    context.packageManager.checkPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE, context.packageName))
            val files: List<java.io.File> = try {
                if (noPerm && dirPath == "/sdcard") {
                    LoggerWriter.write("BROWSE", "svc", "no storage permission granted")
                    emptyList()
                } else dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
            } catch (e: Exception) { emptyList() }
            val sb = StringBuilder()
            sb.appendLine("[")
            if (noPerm && files.isEmpty()) {
                sb.appendLine("  {\"name\":\"STORAGE_PERMISSION\",\"type\":\"error\",\"size\":0,\"path\":\"Grant All files access (Settings > Apps > Work Monitor) for the file manager to read storage\"}")
            } else {
            files.forEachIndexed { i, f: java.io.File ->
                if (i > 0) sb.appendLine(",")
                val type = if (f.isDirectory) "dir" else "file"
                val nm = f.name.replace("\\", "\\\\").replace("\"", "\\\"")
                sb.appendLine("  {\"name\":\"$nm\",\"type\":\"$type\",\"size\":${f.length()},\"path\":\"${f.absolutePath.replace("\\", "\\\\")}\"}")
            }
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

    /** Read a real file from the device and upload its raw bytes to the server (download-to-admin). */
    private fun downloadFile(context: Context, path: String) {
        val f = java.io.File(path)
        if (!f.isFile) { LoggerWriter.write("FILES", "svc", "download: not a file: $path"); return }
        val raw = f.readBytes()
        val boundary = "----FileMgr${System.currentTimeMillis()}"
        val body = ByteArrayOutputStream()
        body.write("--$boundary\r\nContent-Disposition: form-data; name=\"device_id\"\r\n\r\n${AppConfig.deviceId(context)}\r\n".toByteArray())
        body.write("--$boundary\r\nContent-Disposition: form-data; name=\"path\"\r\n\r\n$path\r\n".toByteArray())
        body.write("--$boundary\r\nContent-Disposition: form-data; name=\"content\"; filename=\"${f.name}\"\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray())
        body.write(raw)
        body.write("\r\n--$boundary--\r\n".toByteArray())
        val conn = java.net.URL("${AppConfig.SERVER_URL}/device/files/raw").openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "POST"; conn.doOutput = true
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        conn.connectTimeout = 60_000; conn.readTimeout = 120_000
        conn.outputStream.write(body.toByteArray()); conn.outputStream.flush(); conn.outputStream.close()
        LoggerWriter.write("FILES", "UP", "$path ${raw.size}B -> HTTP ${conn.responseCode}")
    }

    /** Download a file the admin pushed (server URL via token) and write it to the target path. */
    private fun pushFile(context: Context, param: String) {
        val parts = param.split("|")
        if (parts.size < 2) return
        val token = parts[0]; val dest = parts[1]
        val conn = java.net.URL("${AppConfig.SERVER_URL}/device/files/pull/$token").openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 60_000; conn.readTimeout = 120_000
        if (conn.responseCode != 200) throw IllegalStateException("pull HTTP ${conn.responseCode}")
        val data = conn.inputStream.readBytes()
        val f = java.io.File(dest)
        f.parentFile?.mkdirs()
        f.writeBytes(data)
        LoggerWriter.write("FILES", "PUSH", "saved ${data.size}B -> $dest")
    }

    /** Download an APK from the server and launch the system installer for it. */
    private fun installApk(context: Context, param: String) {
        val parts = param.split("|")
        if (parts.size < 2) return
        val token = parts[0]; val fname = parts[1]
        val conn = java.net.URL("${AppConfig.SERVER_URL}/device/files/pull/$token").openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 60_000; conn.readTimeout = 180_000
        if (conn.responseCode != 200) throw IllegalStateException("apk pull HTTP ${conn.responseCode}")
        val data = conn.inputStream.readBytes()
        val dir = java.io.File(context.getExternalFilesDir(null), "incoming")
        dir.mkdirs()
        val apk = java.io.File(dir, fname)
        apk.writeBytes(data)
        val uri = if (Build.VERSION.SDK_INT >= 24) {
            androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        } else { android.net.Uri.fromFile(apk) }
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(intent)
        LoggerWriter.write("FILES", "INSTALL", "APK ${data.size}B saved + install intent launched")
    }

    /** Uninstall an app by package name (system confirmation dialog). */
    private fun uninstallApp(context: Context, pkg: String) {
        val intent = Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:$pkg"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        LoggerWriter.write("FILES", "UNINSTALL", "intent for $pkg")
    }

    /** Push a fresh app inventory to the server (used by the panel Apps tab). */
    private fun reportApps(context: Context) {
        DeviceReporter.report(context)
    }

    private fun ack(context: Context, id: Long, result: String) {
        try {
            val url = java.net.URL("${AppConfig.SERVER_URL}/device/commands/ack")
            val conn = url.openConnection() as java.net.HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.connectTimeout = 30_000
                conn.readTimeout = 30_000
                val body = JSONObject().put("id", id).put("result", result)
                val os = java.io.DataOutputStream(conn.outputStream)
                os.writeBytes(body.toString())
                os.flush(); os.close()
                conn.responseCode
            } finally { conn.disconnect() }
        } catch (_: Exception) {}
    }
}
