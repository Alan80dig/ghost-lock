package com.ghostlock.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import androidx.appcompat.app.AppCompatActivity

class OnboardingActivity : AppCompatActivity() {

    private lateinit var checkBattery: CheckBox
    private lateinit var checkAccessibility: CheckBox
    private lateinit var btnBattery: Button
    private lateinit var btnAccessibility: Button
    private lateinit var btnStart: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("ghost_prefs", Context.MODE_PRIVATE)
        val hasOnboarded = prefs.getBoolean("has_onboarded", false)
        if (hasOnboarded && isAccessibilityEnabled() && isBatteryOptimized()) {
            startMainFlow()
            return
        }

        setContentView(R.layout.activity_onboarding)

        checkBattery = findViewById(R.id.checkBattery)
        checkAccessibility = findViewById(R.id.checkAccessibility)
        btnBattery = findViewById(R.id.btnBattery)
        btnAccessibility = findViewById(R.id.btnAccessibility)
        btnStart = findViewById(R.id.btnStart)

        btnBattery.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                })
            } catch (_: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (_: Exception) {}
            }
        }

        btnAccessibility.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (_: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                } catch (_: Exception) {}
            }
        }

        btnStart.setOnClickListener {
            getSharedPreferences("ghost_prefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("has_onboarded", true)
                .apply()

            LockService.stoppedByUser = false
            LockService.startIfPermitted(this)
            startActivity(Intent(this, SettingsActivity::class.java))
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionsState()
    }

    private fun updatePermissionsState() {
        val isBatteryOk = isBatteryOptimized()
        val isAccessibilityOk = isAccessibilityEnabled()

        checkBattery.isChecked = isBatteryOk
        checkAccessibility.isChecked = isAccessibilityOk

        btnBattery.isEnabled = !isBatteryOk
        btnAccessibility.isEnabled = !isAccessibilityOk

        btnStart.isEnabled = isBatteryOk && isAccessibilityOk
    }

    private fun isAccessibilityEnabled(): Boolean {
        return try {
            val enabledServices = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            enabledServices.contains("GhostAccessibilityService")
        } catch (_: Exception) { false }
    }

    private fun isBatteryOptimized(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(packageName)
        } else true
    }

    private fun startMainFlow() {
        LockService.stoppedByUser = false
        LockService.startIfPermitted(this)
        startActivity(Intent(this, SettingsActivity::class.java))
        finish()
    }
}