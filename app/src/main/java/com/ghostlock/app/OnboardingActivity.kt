package com.ghostlock.app

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2

class OnboardingActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager2
    private lateinit var btnNext: Button
    private lateinit var btnBattery: Button
    private lateinit var btnAccessibility: Button
    private lateinit var btnStart: Button

    private lateinit var adapter: OnboardingAdapter

    private val onboardingPrefs by lazy {
        getSharedPreferences("onboarding_prefs", Context.MODE_PRIVATE)
    }

    private val TOTAL_PAGES = 4

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val hasOnboarded = onboardingPrefs.getBoolean("has_onboarded", false)
        if (hasOnboarded && isAccessibilityEnabled() && isBatteryOptimizationIgnored()) {
            startMainFlow()
            return
        }

        setContentView(R.layout.activity_onboarding)

        viewPager = findViewById(R.id.viewPager)
        btnNext = findViewById(R.id.btnNext)
        btnBattery = findViewById(R.id.btnBattery)
        btnAccessibility = findViewById(R.id.btnAccessibility)
        btnStart = findViewById(R.id.btnStart)

        adapter = OnboardingAdapter()
        viewPager.adapter = adapter

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                btnNext.text = if (position == TOTAL_PAGES - 1) "ГОТОВО" else "ДАЛЕЕ"
            }
        })

        // ===== ДАЛЕЕ / ГОТОВО =====
        btnNext.setOnClickListener {
            val current = viewPager.currentItem
            if (current < TOTAL_PAGES - 1) {
                viewPager.currentItem = current + 1
            } else {
                if (isBatteryOptimizationIgnored() && isAccessibilityEnabled()) {
                    onboardingPrefs.edit().putBoolean("has_onboarded", true).apply()
                    startMainFlow()
                } else {
                    showMissingPermissionsDialog()
                }
            }
        }

        // ===== Батарея =====
        btnBattery.setOnClickListener {
            openBatterySettings()
        }

        // ===== Служба специальных возможностей =====
        btnAccessibility.setOnClickListener {
            openAccessibilitySettings()
        }

        // ===== НАЧАТЬ =====
        btnStart.setOnClickListener {
            if (isBatteryOptimizationIgnored() && isAccessibilityEnabled()) {
                onboardingPrefs.edit().putBoolean("has_onboarded", true).apply()
                startMainFlow()
            } else {
                showMissingPermissionsDialog()
            }
        }
    }

    /**
     * Диалог с недостающими разрешениями.
     */
    private fun showMissingPermissionsDialog() {
        val missing = buildString {
            if (!isBatteryOptimizationIgnored()) append("• Настройка батареи\n")
            if (!isAccessibilityEnabled()) append("• Служба специальных возможностей\n")
        }

        AlertDialog.Builder(this)
            .setTitle("Нужны разрешения")
            .setMessage("Выдайте следующие разрешения:\n\n$missing")
            .setPositiveButton("OK", null)
            .show()
    }

    /**
     * Прямой экран Accessibility.
     */
    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: Exception) {
            Log.e("GhostLock", "Accessibility intent failed", e)
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (e2: Exception) {
                Log.e("GhostLock", "Settings intent failed", e2)
            }
        }
    }

    /**
     * Батарея: прямой Intent + каскад fallback.
     */
    private fun openBatterySettings() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            })
        } catch (e1: Exception) {
            Log.e("GhostLock", "Battery intent 1 failed", e1)
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                Log.e("GhostLock", "Battery intent 2 failed", e2)
                try {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                } catch (e3: Exception) {
                    Log.e("GhostLock", "Battery intent 3 failed", e3)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()

        if (isAccessibilityEnabled() && isBatteryOptimizationIgnored()) {
            startMainFlow()
            return
        }

        updatePermissionsState()
    }

    private fun updatePermissionsState() {
        val isBatteryOk = isBatteryOptimizationIgnored()
        val isAccessibilityOk = isAccessibilityEnabled()

        btnBattery.isEnabled = !isBatteryOk
        btnAccessibility.isEnabled = !isAccessibilityOk

        // btnStart — всегда активна. Проверка внутри обработчика.
        btnStart.isEnabled = true

        btnBattery.text = if (isBatteryOk) "✅ Батарея настроена" else "Настроить батарею"
        btnAccessibility.text = if (isAccessibilityOk) "✅ Служба включена" else "Включить службу"
    }

    private fun isAccessibilityEnabled(): Boolean {
        return try {
            val enabledServices = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val target = ComponentName(this, GhostAccessibilityService::class.java)
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabledServices)

            while (splitter.hasNext()) {
                val component = ComponentName.unflattenFromString(splitter.next())
                if (component == target) return true
            }
            false
        } catch (e: Exception) {
            Log.e("GhostLock", "isAccessibilityEnabled failed", e)
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