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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        viewPager = findViewById(R.id.viewPager)
        btnNext = findViewById(R.id.btnNext)
        btnSkip = findViewById(R.id.btnSkip)

        val pages = listOf(
            OnboardingPage("Ghost Lock", "Твой телефон умеет прятаться", "Прижал к себе — экран гаснет.\nПеревернул — экран гаснет.\nУбрал в карман — экран гаснет."),
            OnboardingPage("Естественная защита", "Никаких кнопок", "Телефон сам понимает, когда\nего нужно заблокировать."),
            OnboardingPage("Нужны разрешения", "Только необходимое", "Доступ к датчикам\nРабота в фоне\nОптимизация батареи")
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

    private fun requestPermissionsAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                })
            }
        }
        startMainFlow()
    }

    private fun startMainFlow() {
        LockService.startIfPermitted(this)
        startActivity(Intent(this, SettingsActivity::class.java))
        finish()
    }
}