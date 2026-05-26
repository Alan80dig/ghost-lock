package com.ghostlock.app

import android.app.admin.DevicePolicyManager
import android.content.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

class OnboardingActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager2
    private lateinit var tabLayout: TabLayout
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

        // Если права уже есть — пропускаем онбординг
        if (dpm.isAdminActive(adminComponent)) {
            startMainFlow()
            return
        }

        setContentView(R.layout.activity_onboarding)

        viewPager = findViewById(R.id.viewPager)
        tabLayout = findViewById(R.id.tabLayout)
        btnNext = findViewById(R.id.btnNext)
        btnSkip = findViewById(R.id.btnSkip)

        val pages = listOf(
            OnboardingPage(
                "👻 Ghost Lock",
                "Твой телефон умеет прятаться",
                "Прижал к себе — экран гаснет.\nПеревернул — экран гаснет.\nУбрал в карман — экран гаснет."
            ),
            OnboardingPage(
                "📱 Естественная защита",
                "Никаких кнопок",
                "Телефон сам понимает, когда\nего нужно заблокировать.\nРефлекс становится защитой."
            ),
            OnboardingPage(
                "🔐 Нужны разрешения",
                "Только необходимое",
                "• Блокировка экрана\n• Доступ к датчикам\n• Работа в фоне\n• Оптимизация батареи"
            )
        )

        viewPager.adapter = OnboardingAdapter(this, pages)

        TabLayoutMediator(tabLayout, viewPager) { _, _ -> }.attach()

        var currentPage = 0
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                currentPage = position
                btnNext.text = if (position == pages.size - 1) "Предоставить и запустить" else "Далее"
                btnSkip.visibility = if (position == pages.size - 1) android.view.View.GONE else android.view.View.VISIBLE
            }
        })

        btnNext.setOnClickListener {
            if (currentPage == pages.size - 1) {
                requestPermissionsAndStart()
            } else {
                viewPager.currentItem = currentPage + 1
            }
        }

        btnSkip.setOnClickListener {
            requestPermissionsAndStart()
        }
    }

    private fun requestPermissionsAndStart() {
        var allGranted = true

        // 1. Device Admin
        if (!dpm.isAdminActive(adminComponent)) {
            allGranted = false
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Ghost Lock нужны права администратора только для блокировки экрана жестом.")
            }
            startActivityForResult(intent, REQUEST_ADMIN)
        }

        // 2. Оптимизация батареи
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                allGranted = false
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        }

        // 3. Статистика использования (для детекции камеры и звонков)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val appOps = getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
            val mode = appOps.checkOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                packageName
            )
            if (mode != android.app.AppOpsManager.MODE_ALLOWED) {
                allGranted = false
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
        }

        if (allGranted) {
            startMainFlow()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ADMIN) {
            if (dpm.isAdminActive(adminComponent)) {
                startMainFlow()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Если вернулись из настроек и всё есть — запускаем
        if (dpm.isAdminActive(adminComponent)) {
            startMainFlow()
        }
    }

    private fun startMainFlow() {
        LockService.startIfPermitted(this)
        // Переходим в настройки
        startActivity(Intent(this, SettingsActivity::class.java))
        finish()
    }

    companion object {
        private const val REQUEST_ADMIN = 1001
    }
}

// Модель страницы онбординга
data class OnboardingPage(
    val title: String,
    val subtitle: String,
    val description: String
)
