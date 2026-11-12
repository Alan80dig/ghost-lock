package com.flicklock.app

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import android.util.Log
import kotlin.math.abs

class GestureDetector(private val context: Context) {

    companion object {
        private const val TAG = "FlickLock"
        private const val CALL_CHECK_INTERVAL_MS = 2000L
        private const val BUFFER_CAPACITY = 10

        private const val BASE_ALPHA = 0.05f
        private const val FREEZE_SPEED_THRESHOLD = 25f  // °/сек: выше — база замораживается

        private const val MIN_DT_SEC = 0.003f
        private const val MAX_DT_SEC = 0.05f

        private const val LOG_THROTTLE_MS = 500L

        // Устойчивость: deltaRoll должен держаться выше порога не меньше этого времени
        private const val TWIST_HOLD_MS = 100L
    }

    // ===== Поля TWIST =====
    private var baseFrozen = false
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

    private val rollWindow = CircularBuffer(BUFFER_CAPACITY)

    // ===== TWIST: адаптивная база roll =====
    private var baseRoll = 0f
    private var baseRollInitialized = false

    // Устойчивость: с какого момента deltaRoll держится выше порога (0 = не держится)
    private var twistSinceMs = 0L

    // ===== Порог TWIST =====
    // Ползунок «Поворот кисти»: level 0 → 80°, level 100 → 45°
    private var twistRollThreshold = 60f

    // Cooldown
    private var cooldownMs = 1500L
    private var lastGestureTime = 0L

    // ===== Настройка чувствительности =====

    /** Ползунок «Поворот кисти» */
    fun updateTwistSensitivity(level: Int) {
        val t = level.coerceIn(0, 100) / 100f
        twistRollThreshold = lerp(80f, 45f, t)

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Twist sensitivity: level=$level, rollThreshold=$twistRollThreshold")
        }
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    // ===== /Настройка чувствительности =====

    fun onSensorChanged(
        pitch: Float, roll: Float,
        ax: Float, ay: Float, az: Float,
        timestampNs: Long = 0L
    ): String? {
        if (isCallActiveFlag) return null
        if (isHorizontalPhoto()) return null

        val actualTimestamp = if (timestampNs == 0L) System.nanoTime() else timestampNs

        // ===== 1. Первый сэмпл: инициализация базы =====
        if (!baseRollInitialized) {
            baseRoll = roll
            baseRollInitialized = true
            rollWindow.add(roll, actualTimestamp)
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Base roll initialized: baseRoll=$baseRoll")
            }
            return null
        }

        // ===== 2. Добавляем сэмпл в буфер =====
        rollWindow.add(roll, actualTimestamp)

        if (rollWindow.size < 5) return null

        // ===== 3. Скорость roll =====
        val rollSpeed = calculateSpeed(rollWindow)

        // ===== 4. deltaRoll от базы с нормализацией через ±180 =====
        val rawDelta = roll - baseRoll
        val deltaRoll = when {
            rawDelta > 180f -> rawDelta - 360f
            rawDelta < -180f -> rawDelta + 360f
            else -> rawDelta
        }

        val absDelta = abs(deltaRoll)
        val now = System.currentTimeMillis()

        // ===== 5. Заморозка базы с гистерезисом =====
        // Как только deltaRoll приблизился к порогу — база замерла.
        // Разморозка — только когда deltaRoll реально мал (телефон в покое).
        if (absDelta > twistRollThreshold * 0.5f) {
            baseFrozen = true
        } else if (absDelta < twistRollThreshold * 0.2f) {
            baseFrozen = false
        }
        if (!baseFrozen && rollSpeed < FREEZE_SPEED_THRESHOLD) {
            baseRoll = BASE_ALPHA * roll + (1f - BASE_ALPHA) * baseRoll
        }

        // ===== 6. Асимметрия: только «экран вниз» (deltaRoll < 0) =====
        // Тыльная сторона (deltaRoll > 0) игнорируется — нет ложных срабатываний.
        // Для левши поменять знак на > 0f.
        if (deltaRoll > 0f) {
            twistSinceMs = 0L
            return null
        }

        // ===== 7. Проверка TWIST с гистерезисом =====
        val upper = twistRollThreshold
        val lower = twistRollThreshold * 0.8f

        if (absDelta > upper) {
            if (twistSinceMs == 0L) {
                twistSinceMs = now
                if (BuildConfig.DEBUG && shouldLog()) {
                    Log.d(TAG, "TWIST candidate: deltaRoll=$deltaRoll (threshold=$twistRollThreshold), roll=$roll, baseRoll=$baseRoll, az=$az")
                }
            } else if (now - twistSinceMs >= TWIST_HOLD_MS) {
                if (cooldownPassed()) {
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "Gesture: TWIST, deltaRoll=$deltaRoll (threshold=$twistRollThreshold), roll=$roll, az=$az, pitch=$pitch, hold=${now - twistSinceMs}ms")
                    }
                    twistSinceMs = 0L
                    return "TWIST"
                }
                return null
            }
        } else if (absDelta < lower) {
            if (twistSinceMs != 0L && BuildConfig.DEBUG && shouldLog()) {
                Log.d(TAG, "TWIST cancelled: deltaRoll=$deltaRoll (threshold=$twistRollThreshold)")
            }
            twistSinceMs = 0L
        }
        // между lower и upper — НЕ сбрасываем, hold продолжает копиться

        return null
    }

    private fun isHorizontalPhoto(): Boolean {
        // 1. Камера/галерея по foreground-пакету
        if (GhostAccessibilityService.isCameraOrGalleryActive) return true

        // 2. Камера реально занята (WhatsApp/MAX/Telegram фото/видео/видеозаметка).
        if (GhostAccessibilityService.isCameraInUse) return true

        // 3. Видеозвонок — AudioManager.mode
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (am?.mode == AudioManager.MODE_IN_COMMUNICATION ||
            am?.mode == AudioManager.MODE_IN_CALL) return true

        return false
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
        rollWindow.clear()
        baseRollInitialized = false
        baseRoll = 0f
        baseFrozen = false
        twistSinceMs = 0L
        // lastGestureTime НЕ сбрасываем — cooldown управляется через resetCooldown()
    }

    fun resetCooldown() {
        lastGestureTime = System.currentTimeMillis()
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