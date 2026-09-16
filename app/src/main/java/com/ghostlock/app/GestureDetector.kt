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

    private val pitchWindow = CircularBuffer(10)
    private val rollWindow = CircularBuffer(10)
    private val accelZWindow = CircularBuffer(10)

    private var rollThreshold = 55f
    private var pitchThreshold = 70f
    private var accelYThreshold = 3f
    private var rollSpeedFlip = 200f
    private var rollSpeedPocketMin = 100f
    private var rollSpeedPocketMax = 500f
    private var accelZThreshold = 6f
    private var accelZFlip = 8f
    private var tablePitch = 150f
    private var tableAccel = 0.5f
    private var stableWindow = 300L
    private var cooldownMs = 3000L

    private var lastGestureTime = 0L
    private var lastStableRoll = 0f
    private var stableSince = 0L

    // ===== Плавная интерполяция порогов =====
    fun updateSensitivity(level: Int) {
        val t = level.coerceIn(0, 100) / 100f

        rollThreshold   = lerp(60f, 15f, t)
        pitchThreshold  = lerp(90f, 30f, t)
        accelYThreshold = lerp(4f, 1f, t)
        cooldownMs      = lerp(5000L, 1000L, t)
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
    private fun lerp(a: Long, b: Long, t: Float): Long = (a + (b - a) * t).toLong()
    // ===== /Плавная интерполяция =====

    fun onSensorChanged(pitch: Float, roll: Float, ax: Float, ay: Float, az: Float, timestampNs: Long = 0L): String? {
        if (isCallActiveFlag) return null
        if (isHorizontalPhoto(pitch, roll)) return null

        val actualTimestamp = if (timestampNs == 0L) System.nanoTime() else timestampNs

        val azDelta = if (accelZWindow.size > 0) abs(az - accelZWindow.last()) else 0f

        pitchWindow.add(pitch, actualTimestamp)
        rollWindow.add(roll, actualTimestamp)
        accelZWindow.add(az, actualTimestamp)

        if (pitchWindow.size < 5) return null

        val rollSpeed = calculateSpeed(rollWindow)
        val rollStable = checkStability(roll)

        if ((ay > accelYThreshold || azDelta > 30) && abs(pitch) > pitchThreshold) {
            return if (cooldownPassed()) "GRAB_SELF" else null
        }

        if (abs(roll) < rollThreshold) return null

        if (abs(ax) < tableAccel && abs(ay) < tableAccel && abs(pitch) > tablePitch) {
            return if (cooldownPassed()) "FLIP_TABLE" else null
        }

        if (rollSpeed > rollSpeedFlip && abs(az) > accelZFlip) {
            return if (cooldownPassed()) "FLIP_HAND" else null
        }

        if (rollStable && roll < -80f && ax > 5f) {
            return if (cooldownPassed()) "POCKET_LEFT" else null
        }

        if (rollStable && roll > 80f && ax < -5f) {
            return if (cooldownPassed()) "POCKET_RIGHT" else null
        }

        if (rollSpeed in rollSpeedPocketMin..rollSpeedPocketMax && abs(az) > accelZThreshold) {
            return if (cooldownPassed()) "POCKET_BACK" else null
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

    private fun calculateSpeed(window: CircularBuffer): Float {
        if (window.size < 5) return 0f
        val n = window.size

        var recentSum = 0f
        var olderSum = 0f
        var recentTimeSum = 0L
        var olderTimeSum = 0L

        for (i in (n - 3) until n) {
            recentSum += window.valueAt(i)
            recentTimeSum += window.timestampAt(i)
        }
        for (i in 0 until 3) {
            olderSum += window.valueAt(i)
            olderTimeSum += window.timestampAt(i)
        }

        val recentAvg = recentSum / 3f
        val olderAvg = olderSum / 3f
        val delta = abs(recentAvg - olderAvg)

        val recentTimeNs = recentTimeSum / 3
        val olderTimeNs = olderTimeSum / 3
        val timeDiffSec = (recentTimeNs - olderTimeNs) / 1_000_000_000f

        return if (timeDiffSec > 0f) delta / timeDiffSec else 0f
    }

    private fun checkStability(currentRoll: Float): Boolean {
        val now = System.currentTimeMillis()
        if (abs(currentRoll - lastStableRoll) > 5f) {
            stableSince = now
            lastStableRoll = currentRoll
            return false
        }
        return now - stableSince > stableWindow
    }

    fun clear() {
        pitchWindow.clear()
        rollWindow.clear()
        accelZWindow.clear()
        lastGestureTime = 0L
        stableSince = 0L
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