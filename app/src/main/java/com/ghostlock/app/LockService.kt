package com.ghostlock.app

import android.app.*
import android.content.*
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat

class LockService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var detector: GestureDetector
    private lateinit var overlayManager: OverlayManager

    private var isListening = false
    private var justLocked = false
    private var screenOnTime = 0L
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        const val CHANNEL_ID = "ghost_lock_service"
        const val NOTIFICATION_ID = 1

        @Volatile
        private var instance: LockService? = null

        fun getInstance(): LockService? = instance

        fun startIfPermitted(context: Context) {
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
        overlayManager = OverlayManager(this)

        overlayManager.onTimeout = {
            updateNotification("Защита активна")
        }

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Защита активна"))

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isInteractive) {
            screenOnTime = System.currentTimeMillis()
            startListening()
        }

        when (intent?.action) {
            "START_LISTENING" -> {
                screenOnTime = System.currentTimeMillis()
                startListening()
            }
            "STOP_LISTENING" -> stopListening()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startListening() {
        if (isListening) return

        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val rotation = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        if (accel == null && rotation == null) return

        accel?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        rotation?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }

        isListening = true
        justLocked = false
    }

    private fun stopListening() {
        sensorManager.unregisterListener(this)
        isListening = false
        detector.clear()
    }

    fun updateSensitivity(level: Int) {
        detector.updateSensitivity(level)
    }

    private var pitch = 0f
    private var roll = 0f
    private var ax = 0f
    private var ay = 0f
    private var az = 0f

    override fun onSensorChanged(event: SensorEvent) {
        if (!isListening) return

        if (System.currentTimeMillis() - screenOnTime < 3000) return

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                ax = event.values[0]
                ay = event.values[1]
                az = event.values[2]
            }
            Sensor.TYPE_ROTATION_VECTOR -> {
                val rotationMatrix = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                val orientation = FloatArray(3)
                SensorManager.getOrientation(rotationMatrix, orientation)
                pitch = Math.toDegrees(orientation[1].toDouble()).toFloat()
                roll = Math.toDegrees(orientation[2].toDouble()).toFloat()
            }
        }

        // Оверлей активен — ждём жест снятия
        if (overlayManager.isShowing()) {
            if (pitch in -45f..45f && roll in -45f..45f) {
                dismissOverlay()
            }
            return
        }

        if (justLocked) return

        val gesture = detector.onSensorChanged(pitch, roll, ax, ay, az)
        if (gesture != null) {
            triggerOverlay()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun triggerOverlay() {
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
            vibrator.vibrate(VibrationEffect.createOneShot(50, 100))
        } catch (_: Exception) {}

        overlayManager.show()
        updateNotification("Заглушка активна")
    }

    private fun dismissOverlay() {
        overlayManager.hide()
        justLocked = false
        updateNotification("Защита активна")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Ghost Lock",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Статус защиты Ghost Lock"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, LockService::class.java).apply {
            action = "STOP_LISTENING"
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Ghost Lock")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_media_pause, "ОТКЛЮЧИТЬ", stopPendingIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = buildNotification(text)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        instance = null
        overlayManager.destroy()
        stopListening()
        super.onDestroy()
    }
}