package com.ghostlock.app

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import kotlin.math.abs

class GestureDetector(private val context: Context) {

    companion object {
        private const val CALL_CHECK_INTERVAL_MS = 2000L
        private const val BUFFER_CAPACITY = 10

        // Динамическая база
        private const val BASE_ALPHA = 0.01f          // ~2 сек подтягивание
        private const val FREEZE_THRESHOLD = 10f      // при жесте база замораживается

        // Отсечка dt для calculateSpeed
        private const val MIN_DT_SEC = 0.003f         // 333 Гц
        private const val MAX_DT_SEC = 0.05f          // 20 Гц
    }

    // ===== isCallActive через Handler =====
    @Volatile private var isCallActiveFlag = false
    private val callCheckHandler = Handler(Looper.getMainLooper())
    private val callCheckRunnable = object : Runnable {
        override fun run() {
            isCallActiveFlag = checkCallActiveNow()
            callCheckHandler.postDelayed(this, CALL_CHECK_INTERVAL_MS)
        }
    }

    fun startCallMonitoring() {
        callCheckHandler.removeCallbacks(callCheckRunnable)
        callCheckHandler.post(callCheckRunnable)
    }

    fun stopCallMonitoring() {
        callCheckHandler.removeCallbacks(callCheckRunnable)
        isCallActiveFlag = false
    }

    private fun checkCallActiveNow(): Boolean {
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (tm != null && am != null) {
                tm.callState != TelephonyManager.CALL_STATE_IDLE ||
                        am.mode == AudioManager.MODE_IN_COMMUNICATION ||
                        am.mode == AudioManager.MODE_IN_CALL
            } else false
        } catch (e: SecurityException) {
            false
        }
    }
    // ===== /isCallActive =====

    // ===== Буферы =====
    private val pitchWindow = CircularBuffer(BUFFER_CAPACITY)
    private val rollWindow = CircularBuffer(BUFFER_CAPACITY)
    private val accelZWindow = CircularBuffer(BUFFER_CAPACITY)
    private val axWindow = CircularBuffer(BUFFER_CAPACITY)   // для FLIP_HAND через axSpeed

    // ===== Динамическая база =====
    private var basePitch = 0f
    private var baseRoll = 0f
    private var baseInitialized = false

    // ===== Пороги =====
    private var pitchThreshold = 60f       // для deltaPitch (GRAB_SELF)
    private var rollThreshold = 50f        // для deltaRoll (на будущее)
    private var axSpeedFlip = 300f         // °/сек для FLIP_HAND
    private var accelZFlip = 3.5f          // |az| при перевороте проходит через 0
    private var tablePitch = 150f          // абсолютный pitch для FLIP_TABLE
    private var tableAccel = 0.5f
    private var cooldownMs = 3000L

    private var lastGestureTime = 0L

    // ===== Плавная интерполяция порогов =====
    fun updateSensitivity(level: Int) {
        val t = level.coerceIn(0, 100) / 100f

        pitchThreshold = lerp(60f, 15f, t)     // deltaPitch: 60° (мин. чувств.) → 15° (макс.)
        rollThreshold  = lerp(50f, 12f, t)     // deltaRoll
        axSpeedFlip    = lerp(300f, 100f, t)   // °/сек по ax
        accelZFlip     = lerp(5f, 2f, t)
        cooldownMs     = lerp(4000L, 1000L, t)
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
    private fun lerp(a: Long, b: Long, t: Float): Long = (a + (b - a) * t).toLong()
    // ===== /Плавная интерполяция =====

    fun onSensorChanged(
        pitch: Float, roll: Float,
        ax: Float, ay: Float, az: Float,
        timestampNs: Long = 0L
    ): String? {
        if (isCallActiveFlag) return null
        if (isHorizontalPhoto(pitch, roll)) return null

        val actualTimestamp = if (timestampNs == 0L) System.nanoTime() else timestampNs

        // ===== 1. Инициализация базы =====
        if (!baseInitialized) {
            basePitch = pitch
            baseRoll = roll
            baseInitialized = true
            // Первое событие — только инициализируем, не детектим
            pitchWindow.add(pitch, actualTimestamp)
            rollWindow.add(roll, actualTimestamp)
            accelZWindow.add(az, actualTimestamp)
            axWindow.add(ax, actualTimestamp)
            return null
        }

        // ===== 2. Дельты от базы =====
        val deltaPitch = abs(pitch - basePitch)
        val deltaRoll  = abs(roll - baseRoll)

        // ===== 3. Обновление базы (только если жест НЕ в процессе) =====
        if (deltaPitch < FREEZE_THRESHOLD && deltaRoll < FREEZE_THRESHOLD) {
            basePitch = BASE_ALPHA * pitch + (1f - BASE_ALPHA) * basePitch
            baseRoll  = BASE_ALPHA * roll  + (1f - BASE_ALPHA) * baseRoll
        }

        // ===== 4. Добавление в буферы =====
        pitchWindow.add(pitch, actualTimestamp)
        rollWindow.add(roll, actualTimestamp)
        accelZWindow.add(az, actualTimestamp)
        axWindow.add(ax, actualTimestamp)

        if (pitchWindow.size < 5) return null

        // ===== 5. Скорости =====
        val axSpeed = calculateSpeed(axWindow)

        // ===== 6. Проверки жестов =====

        // GRAB_SELF: наклон "на себя" — только по deltaPitch
        if (deltaPitch > pitchThreshold) {
            return if (cooldownPassed()) "GRAB_SELF" else null
        }

        // FLIP_TABLE: положили плашмя экраном вниз (абсолютное положение)
        if (abs(ax) < tableAccel && abs(ay) < tableAccel && abs(pitch) > tablePitch) {
            return if (cooldownPassed()) "FLIP_TABLE" else null
        }

        // FLIP_HAND: поворот "экраном в бок" — через axSpeed
        if (axSpeed > axSpeedFlip && abs(az) > accelZFlip) {
            return if (cooldownPassed()) "FLIP_HAND" else null
        }

        return null
    }

    private fun isHorizontalPhoto(pitch: Float, roll: Float): Boolean {
        if (GhostAccessibilityService.isCameraOrGalleryActive) return true
        return abs(pitch) < 30f && abs(roll) in 45f..90f
    }

    private fun cooldownPassed(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastGestureTime < cooldownMs) return false
        lastGestureTime = now
        return true
    }

    // ===== calculateSpeed с нормализацией углов и отсечкой dt =====
    private fun calculateSpeed(window: CircularBuffer): Float {
        if (window.size < 3) return 0f
        val n = window.size

        var accumulatedDegrees = 0f
        var totalTimeSec = 0f

        for (i in 1 until n) {
            val prevVal = window.valueAt(i - 1)
            val currVal = window.valueAt(i)

            // Нормализация перехода через квадранты (границы 180 / -180)
            var diff = abs(currVal - prevVal)
            if (diff > 180f) diff = 360f - diff

            val dtSec = (window.timestampAt(i) - window.timestampAt(i - 1)) / 1_000_000_000f

            // Отсечка выбросов dt (системные фризы)
            if (dtSec in MIN_DT_SEC..MAX_DT_SEC) {
                accumulatedDegrees += diff
                totalTimeSec += dtSec
            }
        }

        return if (totalTimeSec > 0f) accumulatedDegrees / totalTimeSec else 0f
    }
    // ===== /calculateSpeed =====

    fun clear() {
        pitchWindow.clear()
        rollWindow.clear()
        accelZWindow.clear()
        axWindow.clear()
        baseInitialized = false
        basePitch = 0f
        baseRoll = 0f
        lastGestureTime = 0L
    }
}

// ===== CircularBuffer без аллокаций =====
class CircularBuffer(private val capacity: Int) {
    private val values = FloatArray(capacity)
    private val timestamps = LongArray(capacity)
    private var writeIndex = 0
    var size = 0
        private set

    fun add(value: Float, timestampNs: Long) {
        values[writeIndex] = value
        timestamps[writeIndex] = timestampNs
        writeIndex = (writeIndex + 1) % capacity
        if (size < capacity) size++
    }

    /** i = 0 — самый старый, i = size-1 — самый новый */
    fun valueAt(i: Int): Float = values[physicalIndex(i)]
    fun timestampAt(i: Int): Long = timestamps[physicalIndex(i)]
    fun last(): Float = if (size == 0) 0f else values[(writeIndex - 1 + capacity) % capacity]

    private fun physicalIndex(i: Int): Int {
        val start = if (size < capacity) 0 else writeIndex
        return (start + i) % capacity
    }

    fun clear() {
        values.fill(0f)
        timestamps.fill(0L)
        writeIndex = 0
        size = 0
    }
}