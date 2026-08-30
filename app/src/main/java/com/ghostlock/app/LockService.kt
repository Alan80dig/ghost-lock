package com.ghostlock.app

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlin.math.sqrt

class LockService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var isLocking = false

    private var lastAcceleration = 0f
    private var currentAcceleration = 0f
    private val shakeThreshold = 5f

    private val safetyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON, Intent.ACTION_SCREEN_OFF -> {
                    ensureTimeoutRestored(context ?: return)
                }
                "com.ghostlock.app.ACTION_RESTORE_TIMEOUT" -> {
                    ensureTimeoutRestored(context ?: return)
                }
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "ghost_lock_service"
        const val NOTIFICATION_ID = 1

        @Volatile
        private var instance: LockService? = null

        @Volatile
        var stoppedByUser = false

        fun getInstance(): LockService? = instance

        fun startIfPermitted(context: Context) {
            if (stoppedByUser) return
            val intent = Intent(context, LockService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LockService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction("com.ghostlock.app.ACTION_RESTORE_TIMEOUT")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(safetyReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(safetyReceiver, filter)
        }

        createNotificationChannel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification("Защита активна"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification("Защита активна"))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "STOP_SERVICE" -> {
                stoppedByUser = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_ACCELEROMETER || isLocking) return

        Log.d("GhostLock", "Sensor event: values=${event.values[0]},${event.values[1]},${event.values[2]}")

        lastAcceleration = currentAcceleration
        currentAcceleration = sqrt(
            event.values[0] * event.values[0] +
            event.values[1] * event.values[1] +
            event.values[2] * event.values[2]
        )
        val delta = currentAcceleration - lastAcceleration

        if (delta > shakeThreshold) {
            triggerLockByMotion()
        }
    }

    private fun triggerLockByMotion() {
        if (!Settings.System.canWrite(this)) return
        isLocking = true

        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = getSystemService(VibratorManager::class.java)
                manager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(50, 100))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(50)
            }
        } catch (_: Exception) {}

        try {
            val currentTimeout = Settings.System.getInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, 15000)
            if (currentTimeout > 2500) {
                getSharedPreferences("safety_prefs", MODE_PRIVATE)
                    .edit()
                    .putInt("user_timeout", currentTimeout)
                    .apply()
                Log.d("GhostLock", "User timeout saved: $currentTimeout")
            }

            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent("com.ghostlock.app.ACTION_RESTORE_TIMEOUT")
            val pendingIntent = PendingIntent.getBroadcast(
                this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 3500,
                pendingIntent
            )
            Log.d("GhostLock", "Restore alarm set for +3500ms")

            Settings.System.putInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, 2500)
            Log.d("GhostLock", "Timeout set to 2500")
        } catch (e: Exception) {
            ensureTimeoutRestored(this)
        }
    }

    private fun ensureTimeoutRestored(context: Context) {
        if (!Settings.System.canWrite(context)) return
        try {
            val currentTimeout = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, 15000)
            if (currentTimeout <= 2500) {
                val prefs = context.getSharedPreferences("safety_prefs", MODE_PRIVATE)
                val originalTimeout = prefs.getInt("user_timeout", 30000)

                Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, originalTimeout)
                Log.d("GhostLock", "Timeout restored: $originalTimeout")
            }
        } catch (_: Exception) {}
        isLocking = false
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        sensorManager.unregisterListener(this)
        try { unregisterReceiver(safetyReceiver) } catch (_: Exception) {}
        ensureTimeoutRestored(this)
        instance = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Lock Timer",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Статус защиты Lock Timer"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, LockService::class.java).apply {
            action = "STOP_SERVICE"
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Lock Timer")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_media_pause, "ОТКЛЮЧИТЬ", stopPendingIntent)
            .build()
    }
}