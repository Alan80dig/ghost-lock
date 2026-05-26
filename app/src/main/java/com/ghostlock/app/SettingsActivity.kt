package com.ghostlock.app

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var switchService: Switch
    private lateinit var tvStatus: TextView
    private lateinit var seekSensitivity: SeekBar
    private lateinit var tvSensitivityLabel: TextView
    private lateinit var btnUninstall: Button

    private val adminComponent by lazy {
        ComponentName(this, AdminReceiver::class.java)
    }

    private val dpm by lazy {
        getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    }

    private val prefs by lazy {
        getSharedPreferences("ghost_prefs", Context.MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        switchService = findViewById(R.id.switchService)
        tvStatus = findViewById(R.id.tvStatus)
        seekSensitivity = findViewById(R.id.seekSensitivity)
        tvSensitivityLabel = findViewById(R.id.tvSensitivityLabel)
        btnUninstall = findViewById(R.id.btnUninstall)

        setupUI()
    }

    private fun setupUI() {
        // Статус
        updateStatus()

        // Переключатель сервиса
        switchService.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                LockService.startIfPermitted(this)
            } else {
                LockService.stop(this)
            }
            updateStatus()
        }

        // Чувствительность
        val sensitivity = prefs.getInt("sensitivity", 50)
        seekSensitivity.progress = sensitivity
        updateSensitivityLabel(sensitivity)

        seekSensitivity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateSensitivityLabel(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar?.let {
                    prefs.edit().putInt("sensitivity", it.progress).apply()
                }
            }
        })

        // Удаление
        btnUninstall.setOnClickListener {
            // Снимаем админ-права
            if (dpm.isAdminActive(adminComponent)) {
                dpm.removeActiveAdmin(adminComponent)
            }
            // Останавливаем сервис
            LockService.stop(this)
            // Системное удаление
            val intent = Intent(Intent.ACTION_DELETE).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }

    private fun updateStatus() {
        val isAdmin = dpm.isAdminActive(adminComponent)
        val batteryOptimized = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(packageName)
        } else true

        switchService.isChecked = isAdmin

        tvStatus.text = buildString {
            append("Права админа: ${if (isAdmin) "✅" else "❌"}\n")
            append("Батарея: ${if (batteryOptimized) "✅" else "⚠️"}\n")
            append("Сервис: ${if (isAdmin) "🟢 Активен" else "🔴 Остановлен"}")
        }
    }

    private fun updateSensitivityLabel(progress: Int) {
        val label = when (progress) {
            in 0..25 -> "Низкая (только резкие движения)"
            in 26..50 -> "Средняя"
            in 51..75 -> "Высокая"
            else -> "Максимальная (реагирует на всё)"
        }
        tvSensitivityLabel.text = "Чувствительность: $label"
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }
}
