package com.ghostlock.app

import android.content.Context
import android.media.AudioManager
import android.telephony.TelephonyManager
import kotlin.math.abs

class GestureDetector(private val context: Context) {

    // Скользящие окна для расчёта скорости
    private val pitchWindow = CircularBuffer(10)
    private val rollWindow = CircularBuffer(10)
    private val accelZWindow = CircularBuffer(10)

    // Пороги
    companion object {
        const val ROLL_THRESHOLD = 80f
        const val PITCH_THRESHOLD = 110f
        const val ACCEL_Y_THRESHOLD = 5f
        const val ROLL_SPEED_FLIP = 1500f
        const val ROLL_SPEED_POCKET_MIN = 200f
        const val ROLL_SPEED_POCKET_MAX = 800f
        const val ACCEL_Z_THRESHOLD = 8f
        const val ACCEL_Z_FLIP = 10f
        const val TABLE_PITCH = 170f
        const val TABLE_ACCEL = 0.5f
        const val STABLE_WINDOW = 300L // 300 мс стабильности для карманов
    }

    private var lastGestureTime = 0L
    private var lastStableRoll = 0f
    private var stableSince = 0L

    fun onSensorChanged(pitch: Float, roll: Float, ax: Float, ay: Float, az: Float): String? {
        // Исключения — не блокируем
        if (isCallActive()) return null
        if (isHorizontalPhoto(pitch, roll)) return null

        pitchWindow.add(pitch)
        rollWindow.add(roll)
        accelZWindow.add(az)

        if (pitchWindow.size < 5) return null

        val rollSpeed = calculateSpeed(rollWindow)
        val rollStable = checkStability(roll)

        // 1. ПРИЖАЛ К СЕБЕ (проверяем первым — самый быстрый рефлекс)
        if (ay > ACCEL_Y_THRESHOLD && abs(pitch) > PITCH_THRESHOLD) {
            return if (cooldownPassed()) "GRAB_SELF" else null
        }

        // Остальные жесты требуют roll > 80°
        if (abs(roll) < ROLL_THRESHOLD) return null

        // 2. ПЕРЕВОРОТ НА СТОЛЕ
        if (abs(ax) < TABLE_ACCEL && abs(ay) < TABLE_ACCEL && abs(pitch) > TABLE_PITCH) {
            return if (cooldownPassed()) "FLIP_TABLE" else null
        }

        // 3. ПЕРЕВОРОТ В РУКЕ
        if (rollSpeed > ROLL_SPEED_FLIP && abs(az) > ACCEL_Z_FLIP) {
            return if (cooldownPassed()) "FLIP_HAND" else null
        }

        // 4. ЛЕВЫЙ КАРМАН
        if (rollStable && roll < -80f && ax > 5f) {
            return if (cooldownPassed()) "POCKET_LEFT" else null
        }

        // 5. ПРАВЫЙ КАРМАН
        if (rollStable && roll > 80f && ax < -5f) {
            return if (cooldownPassed()) "POCKET_RIGHT" else null
        }

        // 6. ЗАДНИЙ КАРМАН
        if (rollSpeed in ROLL_SPEED_POCKET_MIN..ROLL_SPEED_POCKET_MAX && abs(az) > ACCEL_Z_THRESHOLD) {
            return if (cooldownPassed()) "POCKET_BACK" else null
        }

        return null
    }

    private fun isCallActive(): Boolean {
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            tm.callState != TelephonyManager.CALL_STATE_IDLE ||
                    am.mode == AudioManager.MODE_IN_COMMUNICATION ||
                    am.mode == AudioManager.MODE_IN_CALL
        } catch (e: Exception) {
            false
        }
    }

    private fun isHorizontalPhoto(pitch: Float, roll: Float): Boolean {
        // Телефон горизонтально (как при фото) — не блокируем
        return abs(pitch) < 30f && abs(roll) in 45f..90f
    }

    private fun cooldownPassed(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastGestureTime < 5000) return false // 5 сек кулдаун
        lastGestureTime = now
        return true
    }

    private fun calculateSpeed(window: CircularBuffer): Float {
        if (window.size < 5) return 0f
        val values = window.toList()
        val recent = values.takeLast(3).average().toFloat()
        val older = values.take(3).average().toFloat()
        val delta = abs(recent - older)
        val timeMs = (values.size / 2) * 20 // ~20ms на сэмпл при SENSOR_DELAY_GAME
        return if (timeMs > 0) (delta / timeMs) * 1000f else 0f
    }

    private fun checkStability(currentRoll: Float): Boolean {
        val now = System.currentTimeMillis()
        if (abs(currentRoll - lastStableRoll) > 5f) {
            stableSince = now
            lastStableRoll = currentRoll
            return false
        }
        return now - stableSince > STABLE_WINDOW
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
