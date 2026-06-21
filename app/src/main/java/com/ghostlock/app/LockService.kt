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
import android.util.Log
import androidx.core.app.NotificationCompat

class LockService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var detector: GestureDetector
    private lateinit var overlayManager: OverlayManager

    private var isListening = false
    private var isProximityNear = false
    private var hasFreshData = false
    private var justLocked = false
    private var screenOnTime = 0L
    private var pendingGestureTime = 0L
    private val handler = Handler(Looper.getMainLooper())

    private val pendingTimeoutRunnable = Runnable {
        if (pendingGestureTime > 0L) {
            Log.d("GhostLock", "Proximity timeout — cancel")
            pendingGestureTime = 0L
            updateNotification("Защита активна")
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

        overlayManager.onDismiss = {
            justLocked = false
            screenOnTime = System.currentTimeMillis()
            pendingGestureTime = 0L
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
            "STOP_SERVICE" -> {
                stoppedByUser = true
                stopListening()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun onScreenOff() {
        overlayManager.hide()
        justLocked = false
        pendingGestureTime = 0L
        handler.removeCallbacks(pendingTimeoutRunnable)
    }

    private fun startListening() {
        if (isListening) return
        Log.d("GhostLock", "startListening()")

        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val rotation = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val proximity = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        
        proximity?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            Log.d("GhostLock", "Proximity sensor registered")
        }
        
        if (accel == null && rotation == null) {
            Log.e("GhostLock", "No accel/rotation sensors")
            return
        }

        accel?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        rotation?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }

        isListening = true
        justLocked = false
        pendingGestureTime = 0L
        hasFreshData = false
        Log.d("GhostLock", "Sensors registered, listening started")
    }

    private fun stopListening() {
        sensorManager.unregisterListener(this)
        isListening = false
        detector.clear()
        handler.removeCallbacks(pendingTimeoutRunnable)
        Log.d("GhostLock", "stopListening()")
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
                Log.d("GhostLock", "Proximity: ${event.values[0]} cm, isNear=$isProximityNear, pending=$pendingGestureTime")
                if (isProximityNear && pendingGestureTime > 0L) {
                    Log.i("GhostLock", "Proximity triggered during pending — overlay now")
                    handler.removeCallbacks(pendingTimeoutRunnable)
                    triggerOverlay()
                    pendingGestureTime = 0L
                }
            }
        }

        if (!hasFreshData) return
        hasFreshData = false

        if (System.currentTimeMillis() - screenOnTime < 2000) return

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (overlayManager.isShowing() && !pm.isInteractive) {
            Log.d("GhostLock", "Screen off with overlay — hiding")
            overlayManager.hide()
            justLocked = false
            return
        }

        if (overlayManager.isShowing()) return

        if (pendingGestureTime > 0L) return

        if (justLocked) return

        val gesture = detector.onSensorChanged(pitch, roll, ax, ay, az)
        if (gesture != null) {
            Log.i("GhostLock", "GESTURE: $gesture | proximity=$isProximityNear pitch=$pitch roll=$roll az=$az")
            if (isProximityNear) {
                triggerOverlay()
            } else {
                Log.d("GhostLock", "Waiting 3s for proximity...")
                pendingGestureTime = System.currentTimeMillis()
                handler.removeCallbacks(pendingTimeoutRunnable)
                handler.postDelayed(pendingTimeoutRunnable, 3000)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun triggerOverlay() {
        if (justLocked && overlayManager.isShowing()) return
        justLocked = true
        pendingGestureTime = 0L
        handler.removeCallbacks(pendingTimeoutRunnable)

        Log.i("GhostLock", "OVERLAY SHOW | proximity=$isProximityNear pitch=$pitch roll=$roll")

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
            action = "STOP_SERVICE"
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
        handler.removeCallbacks(pendingTimeoutRunnable)
        overlayManager.destroy()
        stopListening()
        super.onDestroy()
    }
}