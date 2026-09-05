package com.ghostlock.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2

class OnboardingActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager2
    private lateinit var btnNext: Button
    private lateinit var btnSkip: Button

    companion object {
        private const val REQUEST_IGNORE_BATTERY = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Быстрый старт: если уже настроено — мгновенно в настройки
        val prefs = getSharedPreferences("ghost_prefs", Context.MODE_PRIVATE)
        val hasOnboarded = prefs.getBoolean("has_onboarded", false)
        if (hasOnboarded && isAccessibilityEnabled()) {
            startMainFlow()
            return
        }

        setContentView(R.layout.activity_onboarding)

        viewPager = findViewById(R.id.viewPager)
        btnNext = findViewById(R.id.btnNext)
        btnSkip = findViewById(R.id.btnSkip)

        val pages = listOf(
            OnboardingPage("FlickLock", "Snap your screen shut", "Прижал к себе — экран гаснет.\nПеревернул — экран гаснет.\nУбрал в карман — экран гаснет."),
            OnboardingPage("Естественная защита", "Никаких кнопок", "Телефон сам понимает, когда\nего нужно заблокировать."),
            OnboardingPage("Нужны разрешения", "Только необходимое", "Доступ к датчикам\nСлужба специальных возможностей\nОптимизация батареи"),
            OnboardingPage("Служба специальных возможностей", "Одна функция — одна цель", "FlickLock использует только\nGLOBAL_ACTION_LOCK_SCREEN.\n\nМы не читаем текст, не видим экран,\nне собираем данные.\n\nВключите FlickLock в настройках\nСпециальных возможностей.")
        )

        viewPager.adapter = OnboardingAdapter(pages)

        var currentPage = 0
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                currentPage = position
                btnNext.text = if (position == pages.size - 1) "Запустить" else "Далее"
                btnSkip.visibility = if (position == pages.size - 1) android.view.View.GONE else android.view.View.VISIBLE
            }
        })

        btnNext.setOnClickListener {
            if (currentPage == pages.size - 1) requestPermissionsAndStart()
            else viewPager.currentItem = currentPage + 1
        }

        btnSkip.setOnClickListener { requestPermissionsAndStart() }
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

    private fun requestPermissionsAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivityForResult(intent, REQUEST_IGNORE_BATTERY)
                return
            }
        }

        if (!isAccessibilityEnabled()) {
            // Пробуем открыть настройки Accessibility
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (_: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                } catch (_: Exception) {}
            }
        }

        // Сохраняем флаг онбординга
        getSharedPreferences("ghost_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("has_onboarded", true)
            .apply()

        startMainFlow()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_IGNORE_BATTERY) {
            startMainFlow()
        }
    }

    private fun startMainFlow() {
        LockService.stoppedByUser = false
        LockService.startIfPermitted(this)
        startActivity(Intent(this, SettingsActivity::class.java))
        finish()
    }
}