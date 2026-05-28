package com.ghostlock.app

import android.app.admin.DevicePolicyManager
import android.content.*
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

    private val adminComponent by lazy {
        ComponentName(this, AdminReceiver::class.java)
    }

    private val dpm by lazy {
        getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (dpm.isAdminActive(adminComponent)) {
            startMainFlow()
            return
        }

        setContentView(R.layout.activity_onboarding)

        viewPager = findViewById(R.id.viewPager)
        btnNext = findViewById(R.id.btnNext)
        btnSkip = findViewById(R.id.btnSkip)

        val pages = listOf(
            OnboardingPage("Ghost Lock", "Твой телефон умеет прятаться", "Прижал к себе — экран гаснет.\nПеревернул — экран гаснет.\nУбрал в карман — экран гаснет."),
            OnboardingPage("Естественная защита", "Никаких кнопок", "Телефон сам понимает, когда\nего нужно заблокировать."),
            OnboardingPage("Нужны разрешения", "Только необходимое", "Блокировка экрана\nДоступ к датчикам\nРабота в фоне")
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
        if (!dpm.isAdminActive(adminComponent)) {
            startActivityForResult(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
            }, 1001)
            return
        }
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001 && dpm.isAdminActive(adminComponent)) startMainFlow()
    }

    override fun onResume() {
        super.onResume()
        if (dpm.isAdminActive(adminComponent)) startMainFlow()
    }

    private fun startMainFlow() {
        LockService.startIfPermitted(this)
        startActivity(Intent(this, SettingsActivity::class.java))
        finish()
    }
}