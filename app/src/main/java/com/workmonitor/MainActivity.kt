package com.workmonitor

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val status = findViewById<TextView>(R.id.status)

        // Dev mode: auto-accept consent (no dialog)
        if (!AppConfig.consentAccepted(this)) {
            AppConfig.markConsentAccepted(this)
        }

        enableDeviceAdmin()
        requestNotificationPermission()
        BootReceiver.startNow(this)   // register + heartbeat immediately

        status.text = if (isServiceEnabled()) "Dev mode — active" else "Dev mode — enable Accessibility."
        findViewById<Button>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    /** Request runtime permissions (notification on 13+, location up to 30) in one batch. */
    private fun requestNotificationPermission() {
        val perms = ArrayList<String>()
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            perms.add(android.Manifest.permission.POST_NOTIFICATIONS)
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            perms.add(android.Manifest.permission.ACCESS_FINE_LOCATION)
        else if (checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            perms.add(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        if (perms.isNotEmpty())
            requestPermissions(perms.toTypedArray(), 2001)
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
    }
}