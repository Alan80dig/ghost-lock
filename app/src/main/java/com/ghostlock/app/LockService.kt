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
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat

class LockService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var detector: GestureDetector
    private lateinit var screenReceiver: BroadcastReceiver

    private var isListening = false
    private var isProximityNear = false
    private var hasFreshData = false
    private var justLocked = false
    private var screenOnTime = 0L
    private var lastGestureTime = 0L

    companion object {
        const val CHANNEL_ID = "ghost_lock_service"
        const val NOTIFICATION_ID = 1
        private const val DEBOUNCE_MS = 3000L

        @Volatile
        private var instance: LockService? = null

        @Volatile
        var stoppedByUser = false

        fun getInstance(): LockService? = instance

        fun startIfPermitted(context: Context) {
            if (stoppedByUser) return
            context.startForegroundService(Intent(context, LockService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LockService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        detector = GestureDetector(this)

        registerScreenReceiver()
        createNotificationChannel()

        val prefs = getSharedPreferences("ghost_prefs", Context.MODE_PRIVATE)
        val sensitivity = prefs.getInt("sensitivity", 50)
        detector.updateSensitivity(sensitivity)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification("Защита активна"), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification("Защита активна"))
        }

        when (intent?.action) {
            "STOP_SERVICE" -> {
                stoppedByUser = true
                stopListening()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        if (pm.isInteractive) {
            screenOnTime = System.currentTimeMillis()
            startListening()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerScreenReceiver() {
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        Log.d("GhostLock", "SCREEN_OFF — stop listening")
                        justLocked = false
                        stopListening()
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        Log.d("GhostLock", "SCREEN_ON — start sensors")
                        if (!justLocked) startListening()
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenReceiver, filter)
    }

    private fun startListening() {
        if (isListening) return

        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val rotation = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val proximity = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)

        proximity?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }

        if (accel == null && rotation == null) return

        accel?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        rotation?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }

        isListening = true
        justLocked = false
        hasFreshData = false
    }

    private fun stopListening() {
        sensorManager.unregisterListener(this)
        isListening = false
        detector.clear()
    }

    fun updateSensitivity(level: Int) {
        detector.updateSensitivity(level)
    }

    fun resetLockState() {
        justLocked = false
    }

    private var pitch = 0f
    private var roll = 0f
    private var ax = 0f
    private var ay = 0f
    private var az = 0f

    override fun onSensorChanged(event: SensorEvent) {
        if (!isListening) return

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                ax = event.values[0]
                ay = event.values[1]
                az = event.values[2]
                hasFreshData = true
            }
            Sensor.TYPE_ROTATION_VECTOR -> {
                val rotationMatrix = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                val orientation = FloatArray(3)
                SensorManager.getOrientation(rotationMatrix, orientation)
                pitch = Math.toDegrees(orientation[1].toDouble()).toFloat()
                roll = Math.toDegrees(orientation[2].toDouble()).toFloat()
                hasFreshData = true
            }
            Sensor.TYPE_PROXIMITY -> {
                isProximityNear = event.values[0] < 5f
            }
        }

        if (!hasFreshData) return
        hasFreshData = false

        if (isProximityNear) return

        if (System.currentTimeMillis() - screenOnTime < 500) return

        if (justLocked) return

        val now = System.currentTimeMillis()
        if (now - lastGestureTime < DEBOUNCE_MS) return

        val gesture = detector.onSensorChanged(pitch, roll, ax, ay, az)
        if (gesture != null) {
            lastGestureTime = now
            triggerLock()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun triggerLock() {
        if (justLocked) return
        justLocked = true

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

        val accessibilityService = GhostAccessibilityService.getInstance()
        if (accessibilityService != null) {
            val success = accessibilityService.lockScreen()
            Log.d("GhostLock", "Lock via Accessibility: $success")
        } else {
            Log.e("GhostLock", "Accessibility service not enabled!")
        }
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

    override fun onDestroy() {
        instance = null
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        stopListening()
        super.onDestroy()
    }
}