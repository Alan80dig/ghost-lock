package com.flicklock.app

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import android.util.Log
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

class GestureDetector(private val context: Context) {

    companion object {
        private const val TAG = "FlickLock"
        private const val CALL_CHECK_INTERVAL_MS = 2000L
        private const val BUFFER_CAPACITY = 10

        private const val BASE_ALPHA = 0.05f
        private const val FREEZE_SPEED_THRESHOLD = 25f

        private const val MIN_DT_SEC = 0.003f
        private const val MAX_DT_SEC = 0.05f

        private const val LOG_THROTTLE_MS = 500L

        private const val TWIST_HOLD_MS = 100L

        private const val MIN_Z_DROP = 0.35f

        // При наклоне к себе/от себя (pitch) roll ~ 0 — не жест.
        // При повороте кисти (TWIST) |roll| = 52..72.
        private const val MIN_ABS_ROLL = 15f

        // При SCREEN_ON телефон может быть перевёрнут (az < 0) —
        // не инициализируем базу, ждём нормального положения.
        private const val MIN_AZ_FOR_BASE = -3f
    }

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

    @Suppress("DEPRECATION")
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

    // ===== 3D-вектор гравитации =====
    private val vectorWindow = VectorCircularBuffer(BUFFER_CAPACITY)

    private var baseGx = 0f
    private var baseGy = 0f
    private var baseGz = 1f
    private var baseInitialized = false

    private var twistSinceMs = 0L

    // Ползунок «Чувствительность»: level 0 → 85°, level 100 → 45°
    // level=50 → порог 65°
    private var twistAngleThreshold = 65f

    private var cooldownMs = 500L
    private var lastGestureTime = 0L

    fun updateTwistSensitivity(level: Int) {
        val t = level.coerceIn(0, 100) / 100f
        twistAngleThreshold = lerp(85f, 45f, t)

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Twist sensitivity: level=$level, angleThreshold=$twistAngleThreshold")
        }
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    fun onSensorChanged(
        pitch: Float, roll: Float,
        ax: Float, ay: Float, az: Float,
        isPortrait: Boolean,
        timestampNs: Long = 0L
    ): String? {
        if (isCallActiveFlag) return null
        if (isHorizontalPhoto()) return null

        val magnitude = sqrt(ax * ax + ay * ay + az * az)
        if (magnitude < 1.0f) return null

        val isCleanGravity = magnitude in 7.5f..12.2f

        val nx = ax / magnitude
        val ny = ay / magnitude
        val nz = az / magnitude

        val actualTimestamp = if (timestampNs == 0L) System.nanoTime() else timestampNs

        // 1. Инициализация базового вектора (только в нормальном положении)
        if (!baseInitialized) {
            if (az < MIN_AZ_FOR_BASE) {
                if (BuildConfig.DEBUG && shouldLog()) {
                    Log.d(TAG, "Base 3D skipped (az=$az < $MIN_AZ_FOR_BASE, phone flipped)")
                }
                return null
            }
            baseGx = nx
            baseGy = ny
            baseGz = nz
            baseInitialized = true
            baseFrozen = false
            vectorWindow.add(nx, ny, nz, actualTimestamp)
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Base 3D initialized: g=($baseGx, $baseGy, $baseGz), az=$az")
            }
            return null
        }

        vectorWindow.add(nx, ny, nz, actualTimestamp)
        if (vectorWindow.size < 5) return null

        // 2. Угловая скорость 3D (°/сек)
        val angularSpeed = calculate3DAngularSpeed(vectorWindow)

        // 3. Истинный 3D-угол и zDrop
        val angleDeg = angleBetween(baseGx, baseGy, baseGz, nx, ny, nz)
        val zDrop = baseGz - nz
        val now = System.currentTimeMillis()

        // 4. Заморозка базы с гистерезисом
        if (angleDeg > twistAngleThreshold * 0.45f) {
            baseFrozen = true
        } else if (angleDeg < twistAngleThreshold * 0.2f) {
            baseFrozen = false
        }

        if (!baseFrozen && isCleanGravity && angularSpeed < FREEZE_SPEED_THRESHOLD) {
            adaptBase(nx, ny, nz)
        }

        // 5. Направление: только экраном вниз (zDrop > 0).
        // Если вверх/вбок (zDrop <= 0) — не жест, базу ПРИНУДИТЕЛЬНО подтягиваем
        // БЕЗ angularSpeed (иначе шумный гироскоп блокирует подтяжку → «зависание»).
        if (zDrop <= 0f) {
            twistSinceMs = 0L
            baseFrozen = false
            if (isCleanGravity) {
                adaptBase(nx, ny, nz)
            }
            return null
        }

        // 6. При наклоне к себе/от себя (pitch) roll ~ 0 — не жест.
        // При TWIST (поворот кисти) |roll| = 52..72.
        if (abs(roll) < MIN_ABS_ROLL) {
            twistSinceMs = 0L
            return null
        }

        // 7. Проверка TWIST с гистерезисом
        val upper = twistAngleThreshold
        val lower = twistAngleThreshold * 0.2f

        if (angleDeg > upper && zDrop >= MIN_Z_DROP) {
            if (twistSinceMs == 0L) {
                twistSinceMs = now
                if (BuildConfig.DEBUG && shouldLog()) {
                    Log.d(TAG, "TWIST candidate: angle=${"%.1f".format(angleDeg)}° (thr=$upper°), zDrop=${"%.2f".format(zDrop)}, roll=${"%.1f".format(roll)}, az=$az")
                }
            } else if (now - twistSinceMs >= TWIST_HOLD_MS) {
                if (cooldownPassed()) {
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "Gesture: TWIST, angle=${"%.1f".format(angleDeg)}°, zDrop=${"%.2f".format(zDrop)}, roll=${"%.1f".format(roll)}, az=$az, hold=${now - twistSinceMs}ms")
                    }
                    twistSinceMs = 0L
                    return "TWIST"
                }
                return null
            }
        } else if (angleDeg < lower || zDrop < MIN_Z_DROP * 0.5f) {
            if (twistSinceMs != 0L && BuildConfig.DEBUG && shouldLog()) {
                Log.d(TAG, "TWIST cancelled: angle=${"%.1f".format(angleDeg)}°, zDrop=${"%.2f".format(zDrop)}")
            }
            twistSinceMs = 0L
        }

        return null
    }

    private fun adaptBase(nx: Float, ny: Float, nz: Float) {
        val bx = BASE_ALPHA * nx + (1f - BASE_ALPHA) * baseGx
        val by = BASE_ALPHA * ny + (1f - BASE_ALPHA) * baseGy
        val bz = BASE_ALPHA * nz + (1f - BASE_ALPHA) * baseGz
        val bMag = sqrt(bx * bx + by * by + bz * bz)
        if (bMag > 0.001f) {
            baseGx = bx / bMag
            baseGy = by / bMag
            baseGz = bz / bMag
        }
    }

    private fun angleBetween(
        x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float
    ): Float {
        val dot = (x1 * x2 + y1 * y2 + z1 * z2).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(dot.toDouble())).toFloat()
    }

    private fun isHorizontalPhoto(): Boolean {
        if (GhostAccessibilityService.isCameraOrGalleryActive) return true
        if (GhostAccessibilityService.isCameraInUse) return true
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

    private fun calculate3DAngularSpeed(window: VectorCircularBuffer): Float {
        if (window.size < 3) return 0f
        val n = window.size
        var accumulatedDegrees = 0f
        var totalTimeSec = 0f
        for (i in 1 until n) {
            val dtSec = (window.timestampAt(i) - window.timestampAt(i - 1)) / 1_000_000_000f
            if (dtSec in MIN_DT_SEC..MAX_DT_SEC) {
                val deg = angleBetween(
                    window.xAt(i - 1), window.yAt(i - 1), window.zAt(i - 1),
                    window.xAt(i), window.yAt(i), window.zAt(i)
                )
                accumulatedDegrees += deg
                totalTimeSec += dtSec
            }
        }
        return if (totalTimeSec > 0f) accumulatedDegrees / totalTimeSec else 0f
    }

    fun clear() {
        vectorWindow.clear()
        baseInitialized = false
        baseGx = 0f
        baseGy = 0f
        baseGz = 1f
        baseFrozen = false
        twistSinceMs = 0L
    }

    fun resetCooldown() {
        lastGestureTime = System.currentTimeMillis()
    }

    /** Страховка при SCREEN_ON. */
    fun resetBaseFrozen() {
        baseFrozen = false
    }
}

class VectorCircularBuffer(private val capacity: Int) {
    private val xs = FloatArray(capacity)
    private val ys = FloatArray(capacity)
    private val zs = FloatArray(capacity)
    private val timestamps = LongArray(capacity)
    private var writeIndex = 0
    var size = 0
        private set

    fun add(x: Float, y: Float, z: Float, timestampNs: Long) {
        xs[writeIndex] = x
        ys[writeIndex] = y
        zs[writeIndex] = z
        timestamps[writeIndex] = timestampNs
        writeIndex = (writeIndex + 1) % capacity
        if (size < capacity) size++
    }

    fun xAt(i: Int): Float = xs[physicalIndex(i)]
    fun yAt(i: Int): Float = ys[physicalIndex(i)]
    fun zAt(i: Int): Float = zs[physicalIndex(i)]
    fun timestampAt(i: Int): Long = timestamps[physicalIndex(i)]

    private fun physicalIndex(i: Int): Int {
        val start = if (size < capacity) 0 else writeIndex
        return (start + i) % capacity
    }

    fun clear() {
        xs.fill(0f)
        ys.fill(0f)
        zs.fill(0f)
        timestamps.fill(0L)
        writeIndex = 0
        size = 0
    }
}