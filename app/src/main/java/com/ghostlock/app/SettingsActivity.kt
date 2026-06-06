package com.ghostlock.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var switchService: Switch
    private lateinit var tvStatus: TextView
    private lateinit var seekSensitivity: SeekBar
    private lateinit var tvSensitivityLabel: TextView
    private lateinit var btnDone: Button
    private lateinit var btnUninstall: Button

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
        btnDone = findViewById(R.id.btnDone)
        btnUninstall = findViewById(R.id.btnUninstall)

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
                    LockService.getInstance()?.updateSensitivity(it.progress)
                }
            }
        })

        btnDone.setOnClickListener {
            LockService.stoppedByUser = false
            LockService.startIfPermitted(this)
            finish()
        }

        btnUninstall.setOnClickListener {
            LockService.stop(this)
            val intent = Intent(Intent.ACTION_DELETE).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }

    private fun updateStatus() {
        val batteryOptimized = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(packageName)
        } else true

        val serviceEnabled = prefs.getBoolean("service_enabled", true)

        tvStatus.text = buildString {
            append("Батарея: ${if (batteryOptimized) "✅" else "⚠️"}\n")
            append("Защита: ${if (serviceEnabled) "🟢 Активна" else "🔴 Отключена"}")
        }
    }

    private fun updateSensitivityLabel(progress: Int) {
        val label = when (progress) {
            in 0..25 -> "Низкая"
            in 26..50 -> "Средняя"
            in 51..75 -> "Высокая"
            else -> "Максимальная"
        }
        tvSensitivityLabel.text = "Чувствительность: $label"
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }
}