package com.flicklock.app

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import android.util.Log
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.sqrt

class GestureDetector(private val context: Context) {

    companion object {
        private const val TAG = "FlickLock"
        private const val CALL_CHECK_INTERVAL_MS = 2000L

        // ===== Low-pass фильтр =====
        private const val LP_ALPHA = 0.2f
        private const val CLEAN_GRAVITY_MIN = 7.5f
        private const val CLEAN_GRAVITY_MAX = 12.2f

        // ===== Offset по позе =====
        private const val OFFSET_MAX = 70f              // было 85° — снижено
        private const val OFFSET_STABLE_SAMPLES = 8
        private const val OFFSET_IDLE_SPEED = 20f
        private const val OFFSET_IDLE_TIME_MS = 3000L
        private const val OFFSET_UPDATE_DELTA = 20f
        private const val POKOY_ALPHA = 0.02f

        // ===== Пороги жеста =====
        private const val SLOW_ANGLE = 90f
        private const val FAST_ANGLE_OFFSET = 10f
        private const val MIN_Z_DROP = 0.60f

        // ===== Скорость =====
        private const val MIN_PEAK_SPEED = 35f
        private const val FAST_PEAK_SPEED = 160f

        // ===== Удержание =====
        private const val SLOW_HOLD_MS = 140L
        private const val FAST_HOLD_MS = 40L

        // ===== Антидребезг и cooldown =====
        private const val RESET_GRACE_MS = 180L
        private const val COOLDOWN_MS = 500L

        // ===== Лог =====
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

    // ===== Low-pass фильтр гравитации =====
    private var gravX = 0f
    private var gravY = 0f
    private var gravZ = 0f
    private var gravInitialized = false

    // ===== Сырой вектор (для скорости) =====
    private var prevRawNx = 0f
    private var prevRawNy = 0f
    private var prevRawNz = 1f
    private var prevRawTimestampNs = 0L

    // ===== Угол =====
    private var prevAngle = -1f
    private var prevTimestampNs = 0L

    // ===== Offset по позе =====
    private var offset = 0f
    private var offsetPending = false
    private var offsetStableCount = 0
    private var idleSinceMs = 0L
    private var anglePokoy = 0f
    private var pokoyInitialized = false

    // ===== Таймеры жеста =====
    private var gestureSinceMs = 0L
    private var resetCandidateSinceMs = 0L
    private var peakSpeed = 0f

    private var lastGestureTime = 0L

    // ===== Ползунок чувствительности =====
    private var slowAngleThreshold = SLOW_ANGLE
    private var fastAngleThreshold = SLOW_ANGLE + FAST_ANGLE_OFFSET

    fun updateTwistSensitivity(level: Int) {
        val t = level.coerceIn(0, 100) / 100f
        slowAngleThreshold = lerp(110f, 70f, t)
        fastAngleThreshold = slowAngleThreshold + FAST_ANGLE_OFFSET
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Twist sensitivity: level=$level, slow=$slowAngleThreshold°, fast=$fastAngleThreshold°")
        }
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    /** Вызывается из LockService при SCREEN_ON. */
    fun onScreenOn() {
        offsetPending = true
        offsetStableCount = 0
        idleSinceMs = 0L
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "onScreenOn: offset pending (${OFFSET_STABLE_SAMPLES} stable samples)")
        }
    }

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

        val isCleanGravity = magnitude in CLEAN_GRAVITY_MIN..CLEAN_GRAVITY_MAX
        val actualTimestamp = if (timestampNs == 0L) System.nanoTime() else timestampNs
        val now = System.currentTimeMillis()

        // 1. Low-pass фильтр
        if (!gravInitialized) {
            if (!isCleanGravity) return null
            gravX = ax; gravY = ay; gravZ = az
            gravInitialized = true
            prevRawNx = ax / magnitude
            prevRawNy = ay / magnitude
            prevRawNz = az / magnitude
            prevRawTimestampNs = actualTimestamp
            if (BuildConfig.DEBUG) Log.d(TAG, "Gravity initialized: g=($gravX, $gravY, $gravZ), az=$az")
            return null
        }

        if (isCleanGravity) {
            gravX = LP_ALPHA * ax + (1f - LP_ALPHA) * gravX
            gravY = LP_ALPHA * ay + (1f - LP_ALPHA) * gravY
            gravZ = LP_ALPHA * az + (1f - LP_ALPHA) * gravZ
        }

        val gMag = sqrt(gravX * gravX + gravY * gravY + gravZ * gravZ)
        if (gMag < 0.001f) return null
        val nz = gravZ / gMag

        // 2. Угол от горизонта
        val angleDeg = Math.toDegrees(acos(nz.coerceIn(-1f, 1f).toDouble())).toFloat()

        // 3. Скорость по СЫРОМУ акселерометру
        var speed = 0f
        if (prevRawTimestampNs != 0L && magnitude > 1f) {
            val dtSec = (actualTimestamp - prevRawTimestampNs) / 1_000_000_000f
            if (dtSec in 0.003f..0.2f) {
                val rawNx = ax / magnitude
                val rawNy = ay / magnitude
                val rawNz = az / magnitude
                val dot = (rawNx * prevRawNx + rawNy * prevRawNy + rawNz * prevRawNz).coerceIn(-1f, 1f)
                val stepDeg = Math.toDegrees(acos(dot.toDouble())).toFloat()
                speed = stepDeg / dtSec
            }
        }
        prevRawNx = ax / magnitude
        prevRawNy = ay / magnitude
        prevRawNz = az / magnitude
        prevRawTimestampNs = actualTimestamp

        prevAngle = angleDeg
        prevTimestampNs = actualTimestamp

        // 4. Медленная адаптация angle_pokoy
        if (speed < OFFSET_IDLE_SPEED) {
            if (!pokoyInitialized) {
                anglePokoy = angleDeg
                pokoyInitialized = true
            } else {
                anglePokoy = POKOY_ALPHA * angleDeg + (1f - POKOY_ALPHA) * anglePokoy
            }
            if (idleSinceMs == 0L) idleSinceMs = now
        } else {
            idleSinceMs = 0L
        }

        // 5. Фиксация offset по стабильным сэмплам
        if (offsetPending) {
            if (speed < OFFSET_IDLE_SPEED) {
                offsetStableCount++
                if (offsetStableCount >= OFFSET_STABLE_SAMPLES) {
                    offset = anglePokoy.coerceAtMost(OFFSET_MAX)
                    offsetPending = false
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "Offset calibrated: $offset° (stable after $offsetStableCount samples)")
                    }
                }
            } else {
                offsetStableCount = 0
            }
        }

        // 6. Обновление offset при длительном покое
        if (!offsetPending && idleSinceMs != 0L && now - idleSinceMs >= OFFSET_IDLE_TIME_MS) {
            val candidate = anglePokoy.coerceAtMost(OFFSET_MAX)
            if (abs(candidate - offset) > OFFSET_UPDATE_DELTA) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Offset updated: $offset° → $candidate° (pose changed)")
                }
                offset = candidate
            }
        }

        // 7. Проверка жеста
        val effectiveAngle = angleDeg - offset
        val slowThr = minOf(slowAngleThreshold, 180f - offset - 10f)
        val fastThr = minOf(slowThr + FAST_ANGLE_OFFSET, 180f - offset - 15f)  // ограничение сверху
        val zDrop = 1f - nz

        // 7a. Накапливаем peakSpeed только когда жест реально начался
        // nz < 0.6 — телефон пошёл на опрокидывание, пик скорости ещё не потерян
        val gestureActive = effectiveAngle > slowThr * 0.5f && nz < 0.6f
        if (gestureActive) {
            peakSpeed = max(peakSpeed, speed)
        } else if (effectiveAngle < slowThr * 0.5f) {
            peakSpeed = 0f
        }

        val isFast = peakSpeed >= FAST_PEAK_SPEED
        val requiredAngle = if (isFast) fastThr else slowThr
        val requiredHoldMs = if (isFast) FAST_HOLD_MS else SLOW_HOLD_MS

        val candidate = effectiveAngle >= requiredAngle &&
                zDrop >= MIN_Z_DROP &&
                peakSpeed >= MIN_PEAK_SPEED        

        if (candidate) {
            if (gestureSinceMs == 0L) {
                gestureSinceMs = now
                resetCandidateSinceMs = 0L
                if (BuildConfig.DEBUG && shouldLog()) {
                    Log.d(TAG, "TWIST candidate (${if (isFast) "fast" else "slow"}): " +
                            "angle=${"%.1f".format(angleDeg)}°, offset=${"%.1f".format(offset)}°, " +
                            "effective=${"%.1f".format(effectiveAngle)}° (thr=$requiredAngle°), " +
                            "peakSpeed=${"%.0f".format(peakSpeed)}°/s, zDrop=${"%.2f".format(zDrop)}")
                }
            } else if (now - gestureSinceMs >= requiredHoldMs) {
                if (cooldownPassed()) {
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "Gesture: TWIST (${if (isFast) "fast" else "slow"}), " +
                                "angle=${"%.1f".format(angleDeg)}°, offset=${"%.1f".format(offset)}°, " +
                                "effective=${"%.1f".format(effectiveAngle)}°, " +
                                "peakSpeed=${"%.0f".format(peakSpeed)}°/s, " +
                                "hold=${now - gestureSinceMs}ms")
                    }
                    gestureSinceMs = 0L
                    resetCandidateSinceMs = 0L
                    peakSpeed = 0f
                    offset = 0f
                    return "TWIST"
                }
            }
        } else {
            if (gestureSinceMs != 0L) {
                if (resetCandidateSinceMs == 0L) {
                    resetCandidateSinceMs = now
                } else if (now - resetCandidateSinceMs >= RESET_GRACE_MS) {
                    if (BuildConfig.DEBUG && shouldLog()) {
                        Log.d(TAG, "TWIST cancelled: angle=${"%.1f".format(angleDeg)}°, " +
                                "effective=${"%.1f".format(effectiveAngle)}°, speed=${"%.0f".format(speed)}°/s")
                    }
                    gestureSinceMs = 0L
                    resetCandidateSinceMs = 0L
                    peakSpeed = 0f
                }
            }
        }

        return null
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
        if (now - lastGestureTime < COOLDOWN_MS) return false
        lastGestureTime = now
        return true
    }

    fun clear() {
        gravX = 0f; gravY = 0f; gravZ = 0f
        gravInitialized = false
        prevRawNx = 0f; prevRawNy = 0f; prevRawNz = 1f
        prevRawTimestampNs = 0L
        prevAngle = -1f
        prevTimestampNs = 0L
        offset = 0f
        offsetPending = false
        offsetStableCount = 0
        idleSinceMs = 0L
        anglePokoy = 0f
        pokoyInitialized = false
        gestureSinceMs = 0L
        resetCandidateSinceMs = 0L
        peakSpeed = 0f
        if (BuildConfig.DEBUG) Log.d(TAG, "clear: all state reset")
    }

    fun resetCooldown() {
        lastGestureTime = System.currentTimeMillis()
    }

    /** Страховка при SCREEN_ON / перезапуске сервиса. */
    fun resetBaseFrozen() {
        clear()
    }
}