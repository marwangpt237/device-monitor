package com.workmonitor

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes log entries to a per-day plaintext file under filesDir/logs/.
 * Rolls over at the configured hour. Synchronized (thread-safe across callers).
 */
object LoggerWriter {
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var currentDate: String? = null
    private var writer: java.io.BufferedWriter? = null
    private var baseDir: File? = null

    fun init(dir: File) { baseDir = File(dir, "logs"); ensureDaily() }

    @Synchronized
    fun write(packageName: String, activity: String?, text: String) {
        val now = Date()
        val line = "[${timeFormat.format(now)}] $packageName ${activity ?: "?"} :: $text\n"
        try {
            writer?.write(line)
            writer?.flush()
        } catch (_: Exception) {
            // buffer write failed; reopen next call via ensureDaily
        }
    }

    @Synchronized
    fun ensureDaily() {
        val dir = baseDir ?: return
        val today = dateFormat.format(Date())
        if (writer == null || today != currentDate) {
            try {
                writer?.close()
            } catch (_: Exception) {}
            val d = File(dir, today)
            d.parentFile?.mkdirs()
            writer = java.io.BufferedWriter(java.io.OutputStreamWriter(java.io.FileOutputStream(d, true), Charsets.UTF_8)) // append
            currentDate = today
        }
    }

    /** Returns today's log file path, if any. */
    @Synchronized
    fun currentFile(): File? {
        val dir = baseDir ?: return null
        val d = File(dir, dateFormat.format(Date()))
        return if (d.exists()) d else null
    }

    @Synchronized
    fun close() {
        try {
            writer?.close()
        } catch (_: Exception) {}
        writer = null
    }
}