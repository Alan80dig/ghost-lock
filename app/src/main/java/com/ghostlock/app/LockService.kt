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
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import android.content.pm.ServiceInfo

class LockService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var detector: GestureDetector
    private lateinit var overlayManager: OverlayManager
    private lateinit var screenReceiver: BroadcastReceiver

    private var isListening = false
    private var isProximityNear = false
    private var hasFreshData = false
    private var justLocked = false
    private var screenOnTime = 0L
    private val handler = Handler(Looper.getMainLooper())

    private val rescueRunnable = Runnable {
        Log.w("GhostLock", "Rescue timeout — force hiding overlay")
        destroyOverlaySecurely()
        // Возобновляем прослушку
      startListening()
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

        overlayManager.onDismiss = {
            justLocked = false
            screenOnTime = System.currentTimeMillis()
            updateNotification("Защита активна")
        }

        registerScreenReceiver()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification("Защита активна"), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                 startForeground(NOTIFICATION_ID, buildNotification("Защита активна"))
            }

        when (intent?.action) {
            "ACTION_HIDE_OVERLAY" -> {
                Log.w("GhostLock", "ACTION_HIDE_OVERLAY received")
                destroyOverlaySecurely()
                return START_STICKY
            }
            "START_LISTENING" -> {
                screenOnTime = System.currentTimeMillis()
                startListening()
            }
            "STOP_LISTENING" -> stopListening()
            "STOP_SERVICE" -> {
                stoppedByUser = true
                destroyOverlaySecurely()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isInteractive) {
            if (overlayManager.isShowing()) {
                overlayManager.hide()
            }
            screenOnTime = System.currentTimeMillis()
            startListening()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun isOverlayShowing(): Boolean = overlayManager.isShowing()

    fun onScreenOff() {
        destroyOverlaySecurely()
    }

    private fun registerScreenReceiver() {
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        Log.d("GhostLock", "SCREEN_OFF — stop sensors")
                        stopListening()
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        Log.d("GhostLock", "SCREEN_ON — hide overlay if stuck, start sensors")
                        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                        if (pm.isInteractive && overlayManager.isShowing()) {
                            destroyOverlaySecurely()
                        }
                        if (!overlayManager.isShowing()) startListening()
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

    private fun destroyOverlaySecurely() {
        try {
            overlayManager.hide()
        } catch (_: Exception) {}
        justLocked = false
        handler.removeCallbacks(rescueRunnable)
    }

    private fun startListening() {
        if (isListening) return

        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val rotation = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val proximity = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        
        proximity?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            Log.d("GhostLock", "Proximity registered: maxRange=${it.maximumRange}")
        }

        
        if (accel == null && rotation == null) return

        accel?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        rotation?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
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
                Log.d("GhostLock", "Proximity event: ${event.values[0]} cm")
                isProximityNear = event.values[0] < 5f
                if (overlayManager.isShowing()) {
                    overlayManager.setTouchBlocking(isProximityNear)
                }
            }
        }

        if (!hasFreshData) return
        hasFreshData = false

        if (System.currentTimeMillis() - screenOnTime < 2000) return

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (overlayManager.isShowing() && !pm.isInteractive) {
            destroyOverlaySecurely()
            return
        }

        if (overlayManager.isShowing()) return

        if (justLocked) return

        val gesture = detector.onSensorChanged(pitch, roll, ax, ay, az)
        if (gesture != null) {
            triggerOverlay()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun triggerOverlay() {
        if (justLocked && overlayManager.isShowing()) return
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
        overlayManager.setTouchBlocking(true)
        updateNotification("Заглушка активна")
        startRescueTimer()
    }

    private fun startRescueTimer() {
        handler.removeCallbacks(rescueRunnable)
        val userTimeout = try {
            Settings.System.getLong(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, 60_000L)
        } catch (_: Exception) { 60_000L }
        val totalLifetime = userTimeout + 10_000L
        Log.d("GhostLock", "Rescue timer: $totalLifetime ms")
        handler.postDelayed(rescueRunnable, totalLifetime)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Ghost Lock",
                NotificationManager.IMPORTANCE_MIN
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
        handler.removeCallbacks(rescueRunnable)
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        overlayManager.destroy()
        stopListening()
        super.onDestroy()
    }
}