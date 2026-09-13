package com.ghostlock.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.CheckBox
import androidx.appcompat.app.AppCompatActivity

class OnboardingActivity : AppCompatActivity() {

    private lateinit var checkBattery: CheckBox
    private lateinit var checkAccessibility: CheckBox
    private lateinit var btnBattery: Button
    private lateinit var btnAccessibility: Button
    private lateinit var btnStart: Button

    private val onboardingPrefs by lazy {
        getSharedPreferences("onboarding_prefs", Context.MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val hasOnboarded = onboardingPrefs.getBoolean("has_onboarded", false)
        if (hasOnboarded && isAccessibilityEnabled() && isBatteryOptimizationIgnored()) {
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
            // Каскад Intent для разных прошивок
            try {
                // 1. Funtouch OS — прямое окно
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                })
            } catch (e1: Exception) {
                Log.e("FlickLock", "Battery intent 1 failed", e1)
                try {
                    // 2. Realme / общий список
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (e2: Exception) {
                    Log.e("FlickLock", "Battery intent 2 failed", e2)
                    try {
                        // 3. Крайний случай
                        startActivity(Intent(Settings.ACTION_SETTINGS))
                    } catch (e3: Exception) {
                        Log.e("FlickLock", "Battery intent 3 failed", e3)
                    }
                }
            }
        }

        btnAccessibility.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (e1: Exception) {
                Log.e("FlickLock", "Accessibility intent failed", e1)
                try {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                } catch (e2: Exception) {
                    Log.e("FlickLock", "Settings intent failed", e2)
                }
            }
        }

        btnStart.setOnClickListener {
            onboardingPrefs.edit().putBoolean("has_onboarded", true).apply()
            LockService.stoppedByUser = false
            LockService.startIfPermitted(this)
            startActivity(Intent(this, SettingsActivity::class.java))
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        // Микро-задержка, чтобы не спамить Settings.Secure на Realme UI
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            updatePermissionsState()
        }, 200)
    }

    private fun updatePermissionsState() {
        val isBatteryOk = isBatteryOptimizationIgnored()
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

            val componentName = ComponentName(this, GhostAccessibilityService::class.java)
            val flat = componentName.flattenToString()
            val flatShort = componentName.flattenToShortString()

            enabledServices.contains(flat) || enabledServices.contains(flatShort)
        } catch (e: Exception) {
            Log.e("FlickLock", "isAccessibilityEnabled failed", e)
            false
        }
    }

    private fun isBatteryOptimizationIgnored(): Boolean {
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