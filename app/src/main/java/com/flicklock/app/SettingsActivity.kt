package com.flicklock.app

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var switchService: Switch
    private lateinit var switchBattery: Switch
    private lateinit var tvStatus: TextView    
    private lateinit var seekFlipSensitivity: SeekBar    
    private lateinit var tvFlipSensitivity: TextView
    private lateinit var btnDone: Button

    private val prefs by lazy {
        getSharedPreferences("ghost_prefs", Context.MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        switchService = findViewById(R.id.switchService)
        switchBattery = findViewById(R.id.switchBattery)
        tvStatus = findViewById(R.id.tvStatus)        
        seekFlipSensitivity = findViewById(R.id.seekFlipSensitivity)        
        tvFlipSensitivity = findViewById(R.id.tvFlipSensitivity)
        btnDone = findViewById(R.id.btnDone)

        setupUI()
    }

    private fun setupUI() {
        updateStatus()

        val isServiceEnabled = prefs.getBoolean("service_enabled", true)
        switchService.isChecked = isServiceEnabled
        switchService.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("service_enabled", isChecked).apply()
            if (isChecked) {
                LockService.stoppedByUser = false
                LockService.startIfPermitted(this)
            } else {
                LockService.stoppedByUser = true
                LockService.stop(this)
            }
            updateStatus()
        }

        switchBattery.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                requestBatteryOptimization()
            } else {
                openBatterySettings()
            }
        }        

        // ===== Ползунок «Поворот экраном вниз» =====
        val flipSensitivity = prefs.getInt("sensitivity_twist",85 )
        seekFlipSensitivity.progress = flipSensitivity
        tvFlipSensitivity.text = "Чувствительность: $flipSensitivity"

        seekFlipSensitivity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                tvFlipSensitivity.text = "Чувствительность: $progress"
                prefs.edit().putInt("sensitivity_twist", progress).apply()
                LockService.getInstance()?.updateTwistSensitivity(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnDone.setOnClickListener {
            LockService.stoppedByUser = false
            LockService.startIfPermitted(this)
            finish()
        }
    }

    private fun isIgnoringBattery(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(packageName)
        } else true
    }

    private fun requestBatteryOptimization() {
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (_: Exception) {}
        }
    }

    private fun openBatterySettings() {
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        startActivity(intent)
    }

    private fun updateStatus() {
        val batteryOptimized = isIgnoringBattery()
        val serviceEnabled = prefs.getBoolean("service_enabled", true)

        tvStatus.text = buildString {
            append("Фон: ${if (batteryOptimized) "✅" else "⚠️"}\n")
            append("Защита: ${if (serviceEnabled) "🟢 Активна" else "🔴 Отключена"}")
        }

        switchBattery.setOnCheckedChangeListener(null)
        switchBattery.isChecked = batteryOptimized
        switchBattery.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                requestBatteryOptimization()
            } else {
                openBatterySettings()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }
}
