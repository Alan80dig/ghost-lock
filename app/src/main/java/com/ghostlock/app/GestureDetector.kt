package com.ghostlock.app

import android.content.Context
import android.media.AudioManager
import android.telephony.TelephonyManager
import kotlin.math.abs

class GestureDetector(private val context: Context) {

    private val pitchWindow = CircularBuffer(10)
    private val rollWindow = CircularBuffer(10)
    private val accelZWindow = CircularBuffer(10)

    // Изменяемые пороги
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
            in 0..25 -> 65f
            in 26..50 -> 55f
            in 51..75 -> 45f
            else -> 35f
        }
        pitchThreshold = when (level) {
            in 0..25 -> 90f
            in 26..50 -> 70f
            in 51..75 -> 50f
            else -> 35f
        }
        accelYThreshold = when (level) {
            in 0..25 -> 5f
            in 26..50 -> 3f
            in 51..75 -> 2f
            else -> 1.5f
        }
        cooldownMs = when (level) {
            in 0..25 -> 5000L
            in 26..50 -> 3000L
            in 51..75 -> 2000L
            else -> 1000L
        }
    }

    fun onSensorChanged(pitch: Float, roll: Float, ax: Float, ay: Float, az: Float): String? {
        
        if (isHorizontalPhoto(pitch, roll)) return null

        pitchWindow.add(pitch)
        rollWindow.add(roll)
        accelZWindow.add(az)

        if (pitchWindow.size < 5) return null

        val rollSpeed = calculateSpeed(rollWindow)
        val rollStable = checkStability(roll)

        // Прижал к себе
        val azDelta = abs(az - (accelZWindow.toList().lastOrNull() ?: 0f))
        if ((ay > accelYThreshold || azDelta > 30) && abs(pitch) > pitchThreshold) {
            return if (cooldownPassed()) "GRAB_SELF" else null
        }

        if (abs(roll) < rollThreshold) return null

        // Переворот на столе
        if (abs(ax) < tableAccel && abs(ay) < tableAccel && abs(pitch) > tablePitch) {
            return if (cooldownPassed()) "FLIP_TABLE" else null
        }

        // Переворот в руке
        if (rollSpeed > rollSpeedFlip && abs(az) > accelZFlip) {
            return if (cooldownPassed()) "FLIP_HAND" else null
        }

        // Левый карман
        if (rollStable && roll < -80f && ax > 5f) {
            return if (cooldownPassed()) "POCKET_LEFT" else null
        }

        // Правый карман
        if (rollStable && roll > 80f && ax < -5f) {
            return if (cooldownPassed()) "POCKET_RIGHT" else null
        }

        // Задний карман
        if (rollSpeed in rollSpeedPocketMin..rollSpeedPocketMax && abs(az) > accelZThreshold) {
            return if (cooldownPassed()) "POCKET_BACK" else null
        }

        return null
      }

   
    private fun isHorizontalPhoto(pitch: Float, roll: Float): Boolean {
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
        val recent = values.takeLast(3).average().toFloat()
        val older = values.take(3).average().toFloat()
        val delta = abs(recent - older)
        val timeMs = (values.size / 2) * 20
        return if (timeMs > 0) (delta / timeMs) * 1000f else 0f
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

// Простой кольцевой буфер
class CircularBuffer(private val capacity: Int) {
    private val data = FloatArray(capacity)
    private var writeIndex = 0
    var size = 0
        private set

    fun add(value: Float) {
        data[writeIndex] = value
        writeIndex = (writeIndex + 1) % capacity
        if (size < capacity) size++
    }

    fun toList(): List<Float> {
        if (size == 0) return emptyList()
        val result = mutableListOf<Float>()
        val start = if (size < capacity) 0 else writeIndex
        for (i in 0 until size) {
            result.add(data[(start + i) % capacity])
        }
        return result
    }

    fun clear() {
        data.fill(0f)
        writeIndex = 0
        size = 0
    }
}
