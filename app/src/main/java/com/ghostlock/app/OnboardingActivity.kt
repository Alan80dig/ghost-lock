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
        private const val REQUEST_WRITE_SETTINGS = 1002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        viewPager = findViewById(R.id.viewPager)
        btnNext = findViewById(R.id.btnNext)
        btnSkip = findViewById(R.id.btnSkip)

        val pages = listOf(
            OnboardingPage("Ghost Lock", "Твой телефон умеет прятаться", "Прижал к себе — экран гаснет.\nПеревернул — экран гаснет.\nУбрал в карман — экран гаснет."),
            OnboardingPage("Естественная защита", "Никаких кнопок", "Телефон сам понимает, когда\nего нужно заблокировать."),
            OnboardingPage("Нужны разрешения", "Только необходимое", "Доступ к датчикам\nРабота в фоне\nОптимизация батареи"),
            OnboardingPage("Не отключать в фоне", "Чтобы Ghost Lock работал всегда", "Разрешите приложению работать\nбез ограничений.\n\nЭто предотвратит отключение\nзащиты системой.")
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
    // 1. Запрос WRITE_SETTINGS (для таймаута экрана)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(this)) {
        val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivityForResult(intent, REQUEST_WRITE_SETTINGS)
        return
    }

    // 2. Запрос игнорирования оптимизации батареи
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
    startMainFlow()
 }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_IGNORE_BATTERY) {
            startMainFlow()
        }
        if (requestCode == REQUEST_WRITE_SETTINGS) {
            requestPermissionsAndStart()
         }
     }

    private fun startMainFlow() {
        LockService.stoppedByUser = false
        LockService.startIfPermitted(this)
        startActivity(Intent(this, SettingsActivity::class.java))
        finish()
    }
}