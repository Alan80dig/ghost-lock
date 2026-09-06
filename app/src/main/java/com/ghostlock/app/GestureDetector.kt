package com.ghostlock.app

import android.content.Context
import android.media.AudioManager
import android.telephony.TelephonyManager
import kotlin.math.abs

class GestureDetector(private val context: Context) {

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

    fun updateSensitivity(level: Int) {
        rollThreshold = when (level) {
            in 0..25 -> 55f
            in 26..50 -> 40f
            in 51..75 -> 30f
            else -> 20f
        }
        pitchThreshold = when (level) {
            in 0..25 -> 120f
            in 26..50 -> 100f
            in 51..75 -> 80f
            else -> 60f
        }
        accelYThreshold = when (level) {
            in 0..25 -> 4f
            in 26..50 -> 2.5f
            in 51..75 -> 1.5f
            else -> 1f
        }
        cooldownMs = when (level) {
            in 0..25 -> 5000L
            in 26..50 -> 3000L
            in 51..75 -> 2000L
            else -> 1000L
        }
    }

    fun onSensorChanged(pitch: Float, roll: Float, ax: Float, ay: Float, az: Float, timestampNs: Long = 0L): String? {
        if (isCallActive()) return null
        if (isHorizontalPhoto(pitch, roll)) return null

        // Если timestamp не передан — используем System.nanoTime()
        val actualTimestamp = if (timestampNs == 0L) System.nanoTime() else timestampNs

        // Считаем azDelta ДО добавления в буфер
        val azDelta = abs(az - (accelZWindow.toList().lastOrNull()?.value ?: az))

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

    private fun isCallActive(): Boolean {
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

    private fun isHorizontalPhoto(pitch: Float, roll: Float): Boolean {
        // Если камера активна — блокируем ВСЕ жесты
        if (GhostAccessibilityService.isCameraOrGalleryActive) return true

        // Иначе проверяем горизонтальное положение
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
        val values = window.toList()

        val recentPoints = values.takeLast(3)
        val olderPoints = values.take(3)

        val recentAvg = recentPoints.map { it.value }.average().toFloat()
        val olderAvg = olderPoints.map { it.value }.average().toFloat()

        val delta = abs(recentAvg - olderAvg)

        val recentTimeNs = recentPoints.map { it.timestampNs }.average().toLong()
        val olderTimeNs = olderPoints.map { it.timestampNs }.average().toLong()

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

class SensorData(val value: Float, val timestampNs: Long)

class CircularBuffer(private val capacity: Int) {
    private val data = arrayOfNulls<SensorData>(capacity)
    private var writeIndex = 0
    var size = 0
        private set

    fun add(value: Float, timestampNs: Long = 0L) {
        data[writeIndex] = SensorData(value, timestampNs)
        writeIndex = (writeIndex + 1) % capacity
        if (size < capacity) size++
    }

    fun toList(): List<SensorData> {
        if (size == 0) return emptyList()
        val result = mutableListOf<SensorData>()
        val start = if (size < capacity) 0 else writeIndex
        for (i in 0 until size) {
            data[(start + i) % capacity]?.let { result.add(it) }
        }
        return result
    }

    fun clear() {
        data.fill(null)
        writeIndex = 0
        size = 0
    }
}