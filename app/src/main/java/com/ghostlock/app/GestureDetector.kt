package com.ghostlock.app

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import android.util.Log
import kotlin.math.abs

class GestureDetector(private val context: Context) {

    companion object {
        private const val TAG = "GhostLock"
        private const val CALL_CHECK_INTERVAL_MS = 2000L
        private const val BUFFER_CAPACITY = 10

        private const val BASE_ALPHA = 0.01f
        private const val FREEZE_THRESHOLD = 10f

        private const val MIN_DT_SEC = 0.003f
        private const val MAX_DT_SEC = 0.05f

        private const val NEAR_MISS_RATIO = 0.8f
        private const val LOG_THROTTLE_MS = 500L
    }

    private var lastLogTime = 0L
    private fun shouldLog(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastLogTime < LOG_THROTTLE_MS) return false
        lastLogTime = now
        return true
    }

    // ===== isCallActive =====
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

    private val pitchWindow = CircularBuffer(BUFFER_CAPACITY)
    private val rollWindow = CircularBuffer(BUFFER_CAPACITY)
    private val accelZWindow = CircularBuffer(BUFFER_CAPACITY)
    private val axWindow = CircularBuffer(BUFFER_CAPACITY)

    private var basePitch = 0f
    private var baseRoll = 0f
    private var baseInitialized = false

    // ===== Пороги =====
    // GRAB_SELF (наклон к себе) — deltaPitch
    private var grabPitchThreshold = 60f

    // FLIP_HAND (поворот экраном вниз) — axSpeed + az
    private var flipAxSpeedThreshold = 120f
    private var flipAzThreshold = 5f

    // FLIP_TABLE (общее для обоих)
    private var tablePitch = 150f
    private var tableAccel = 0.5f

    // Cooldown — общий
    private var cooldownMs = 3000L

    private var lastGestureTime = 0L

    // ===== Раздельная настройка чувствительности =====

    /** Ползунок «Наклон к себе» */
    fun updateGrabSensitivity(level: Int) {
        val t = level.coerceIn(0, 100) / 100f
        grabPitchThreshold = lerp(60f, 15f, t)

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Grab sensitivity: level=$level, pitchThreshold=$grabPitchThreshold")
        }
    }

    /** Ползунок «Поворот экраном вниз» */
    fun updateFlipSensitivity(level: Int) {
        val t = level.coerceIn(0, 100) / 100f
        flipAxSpeedThreshold = lerp(200f, 120f, t)   // было 120 → 70
        flipAzThreshold = lerp(6f, 3f, t)            // было 5 → 2

        if (BuildConfig.DEBUG) {
             Log.d(TAG, "Flip sensitivity: level=$level, axSpeedFlip=$flipAxSpeedThreshold, azThreshold=$flipAzThreshold")
        }
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    // ===== /Раздельная настройка =====

    fun onSensorChanged(
        pitch: Float, roll: Float,
        ax: Float, ay: Float, az: Float,
        timestampNs: Long = 0L
    ): String? {
        if (isCallActiveFlag) return null
        if (isHorizontalPhoto(pitch, roll)) return null

        val actualTimestamp = if (timestampNs == 0L) System.nanoTime() else timestampNs

        if (!baseInitialized) {
            basePitch = pitch
            baseRoll = roll
            baseInitialized = true
            pitchWindow.add(pitch, actualTimestamp)
            rollWindow.add(roll, actualTimestamp)
            accelZWindow.add(az, actualTimestamp)
            axWindow.add(ax, actualTimestamp)
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Base initialized: basePitch=$basePitch, baseRoll=$baseRoll")
            }
            return null
        }

        // ===== 2. Дельты от базы (со знаком и нормализацией через ±180) =====
        val rawDeltaPitch = pitch - basePitch
        val rawDeltaRoll  = roll - baseRoll

        // Нормализация через ±180 (защита от скачков на границе)
        val deltaPitch = if (rawDeltaPitch > 180f) rawDeltaPitch - 360f
                         else if (rawDeltaPitch < -180f) rawDeltaPitch + 360f
                         else rawDeltaPitch

        val deltaRoll = if (rawDeltaRoll > 180f) rawDeltaRoll - 360f
                        else if (rawDeltaRoll < -180f) rawDeltaRoll + 360f
                        else rawDeltaRoll

        // absoluteDelta нужен для freeze; signed delta — для направления
        val absoluteDeltaPitch = abs(deltaPitch)
        val absoluteDeltaRoll  = abs(deltaRoll)

        if (absoluteDeltaPitch < FREEZE_THRESHOLD && absoluteDeltaRoll < FREEZE_THRESHOLD) {
            basePitch = BASE_ALPHA * pitch + (1f - BASE_ALPHA) * basePitch
            baseRoll  = BASE_ALPHA * roll  + (1f - BASE_ALPHA) * baseRoll
        }

        pitchWindow.add(pitch, actualTimestamp)
        rollWindow.add(roll, actualTimestamp)
        accelZWindow.add(az, actualTimestamp)
        axWindow.add(ax, actualTimestamp)

        if (pitchWindow.size < 5) return null

        val axSpeed = calculateSpeed(axWindow)

        // ===== GRAB_SELF: наклон к себе =====
        if (deltaPitch > grabPitchThreshold) {
            if (cooldownPassed()) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Gesture: GRAB_SELF, deltaPitch=$deltaPitch (threshold=$grabPitchThreshold), basePitch=$basePitch, pitch=$pitch")
                }
                return "GRAB_SELF"
            }
            return null
        }

        if (BuildConfig.DEBUG && deltaPitch > grabPitchThreshold * NEAR_MISS_RATIO && shouldLog()) {
            Log.d(TAG, "Near-miss GRAB_SELF: deltaPitch=$deltaPitch (threshold=$grabPitchThreshold), basePitch=$basePitch, pitch=$pitch")
        }

        // ===== FLIP_TABLE: плашмя экраном вниз =====
        if (abs(ax) < tableAccel && abs(ay) < tableAccel && abs(pitch) > tablePitch) {
            if (cooldownPassed()) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Gesture: FLIP_TABLE, pitch=$pitch, ax=$ax, ay=$ay")
                }
                return "FLIP_TABLE"
            }
            return null
        }

        // ===== FLIP_HAND: поворот экраном вниз =====
        if (axSpeed > flipAxSpeedThreshold && abs(az) > flipAzThreshold) {
            if (cooldownPassed()) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Gesture: FLIP_HAND, axSpeed=$axSpeed (threshold=$flipAxSpeedThreshold), az=$az (threshold=$flipAzThreshold)")
                }
                return "FLIP_HAND"
            }
            return null
        }

        if (BuildConfig.DEBUG && axSpeed > flipAxSpeedThreshold * NEAR_MISS_RATIO && shouldLog()) {
            Log.d(TAG, "Near-miss FLIP_HAND: axSpeed=$axSpeed (threshold=$flipAxSpeedThreshold), az=$az (threshold=$flipAzThreshold)")
        }

        return null
    }

    private fun isHorizontalPhoto(pitch: Float, roll: Float): Boolean {
        if (GhostAccessibilityService.isCameraOrGalleryActive ||
            GhostAccessibilityService.isCameraInUse) return true
            return abs(pitch) < 30f && abs(roll) in 45f..90f
    }

    private fun cooldownPassed(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastGestureTime < cooldownMs) return false
        lastGestureTime = now
        return true
    }

    private fun calculateSpeed(window: CircularBuffer): Float {
        if (window.size < 3) return 0f
        val n = window.size

        var accumulatedDegrees = 0f
        var totalTimeSec = 0f

        for (i in 1 until n) {
            val prevVal = window.valueAt(i - 1)
            val currVal = window.valueAt(i)

            var diff = abs(currVal - prevVal)
            if (diff > 180f) diff = 360f - diff

            val dtSec = (window.timestampAt(i) - window.timestampAt(i - 1)) / 1_000_000_000f

            if (dtSec in MIN_DT_SEC..MAX_DT_SEC) {
                accumulatedDegrees += diff
                totalTimeSec += dtSec
            }
        }

        return if (totalTimeSec > 0f) accumulatedDegrees / totalTimeSec else 0f
    }

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