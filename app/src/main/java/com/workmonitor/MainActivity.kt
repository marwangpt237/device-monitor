package com.workmonitor

import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * On first launch: show the consent dialog (as required by the employee agreement).
 * Only after explicit consent does the app guide the user to enable the service.
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val status = findViewById<TextView>(R.id.status)

        // Consent gate
        if (!AppConfig.consentAccepted(this)) {
            showConsent() { accepted ->
                if (accepted) {
                    AppConfig.markConsentAccepted(this)
                    enableDeviceAdmin()
                } else {
                    status.text = "Consent declined. Logging will not run."
                }
            }
        } else {
            status.text = if (isServiceEnabled()) "Monitoring active" else "Monitoring active (service not enabled) — enable Accessibility."
            findViewById<Button>(R.id.btn_settings).setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
    }

    private fun isServiceEnabled(): Boolean {
        val am = getSystemService(android.accessibilityservice.AccessibilityManager::class.java)
        val enabled = am.getEnabledAccessibilityServiceList(android.view.accessibility.AccessibilityEvent.TYPES_ALL_MASK)
        return enabled.any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

    private fun showConsent(onResult: (Boolean) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("Work Device Monitoring — Consent")
            .setMessage(
                "This device is company property. For work security and productivity, " +
                "this app records typed input and app/window activity on work hours and " +
                "sends it to the company server. See the employee agreement. " +
                "By accepting you consent to this on the company-owned device."
            )
            .setCancelable(false)
            .setPositiveButton("I Agree") { _, _ -> onResult(true) }
            .setNegativeButton("Exit") { _, _ -> onResult(false) }
            .show()
    }

    private fun enableDeviceAdmin() {
        val dpm = getSystemService(DevicePolicyManager::class.java)
        val cn = ComponentName(this, DeviceAdminReceiver::class.java)
        if (!dpm.isAdminActive(cn)) {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, cn)
            intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Required to enforce security and allow remote lock/wipe on this company device.")
            startActivity(intent)
        }
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }
}