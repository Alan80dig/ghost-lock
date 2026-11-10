package com.flicklock.app

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class GhostAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "FlickLock"

        // Страховка от OEM-багов: если «камера занята» висит без обновлений дольше этого —
        // сбрасываем флаг (watchdog). Face Wake обычно отпускает камеру за 1–3 сек,
        // но если OEM не прислал onCameraAvailable — спасёт watchdog.
        private const val CAMERA_WATCHDOG_MS = 30_000L
        private const val WATCHDOG_INTERVAL_MS = 5_000L

        @Volatile
        private var instance: GhostAccessibilityService? = null

        @Volatile
        var isCameraOrGalleryActive: Boolean = false
            private set

        @Volatile
        var isCameraInUse: Boolean = false
            private set

        fun getInstance(): GhostAccessibilityService? = instance

        fun resetCameraFlag() {
            isCameraOrGalleryActive = false
        }
    }

    private lateinit var cameraManager: CameraManager

    // Активные (занятые) камеры. Может быть больше одной (фронт + зад).
    private val busyCameras = mutableSetOf<String>()

    // Пакеты Face Wake / разблокировки по лицу - камеру игнорируем.
    // Мессенджеры (WhatsApp, MAX, Telegram) сюда НЕ входят — камеру ловим.
    private val faceWakePackages = setOf(
        "com.bbk.facewake",                              // vivo
        "com.vivo.faceui",                               // vivo
        "com.vivo.faceunlock",                           // vivo
        "com.miui.face",                                 // Xiaomi / Redmi / POCO
        "com.samsung.android.biometrics.app.setting",    // Samsung
        "com.oplus.faceunlock",                          // OPPO / Realme / OnePlus
        "com.huawei.faceunlock",                         // Huawei
        "com.hihonor.face"                               // Honor
    )

    @Volatile
    private var foregroundPackage: String = ""

    @Volatile
    private var lastCameraEventAt = 0L

    private val watchdogHandler = Handler(Looper.getMainLooper())
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            if (isCameraInUse && now - lastCameraEventAt > CAMERA_WATCHDOG_MS) {
                Log.w(TAG, "Watchdog: camera stuck busy > ${CAMERA_WATCHDOG_MS}ms, force reset")
                busyCameras.clear()
                isCameraInUse = false
            }
            watchdogHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    private fun isFaceWakeForeground(): Boolean {
        val pkg = foregroundPackage
        if (pkg.isEmpty()) return false
        return faceWakePackages.any { pkg.contains(it, ignoreCase = true) }
    }

    private fun recomputeCameraInUse(cameraId: String, busy: Boolean) {
        lastCameraEventAt = System.currentTimeMillis()
        val faceWake = isFaceWakeForeground()
        isCameraInUse = busyCameras.isNotEmpty() && !faceWake
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Camera ${if (busy) "busy" else "free"}: $cameraId, " +
                    "foreground=$foregroundPackage, faceWake=$faceWake, " +
                    "isCameraInUse=$isCameraInUse")
        }
    }

    private val cameraAvailabilityCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraUnavailable(cameraId: String) {
            busyCameras.add(cameraId)
            recomputeCameraInUse(cameraId, busy = true)
        }

        override fun onCameraAvailable(cameraId: String) {
            busyCameras.remove(cameraId)
            recomputeCameraInUse(cameraId, busy = false)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        try {
            cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cameraManager.registerAvailabilityCallback(
                cameraAvailabilityCallback,
                Handler(Looper.getMainLooper())
            )

            lastCameraEventAt = System.currentTimeMillis()
            watchdogHandler.removeCallbacks(watchdogRunnable)
            watchdogHandler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS)

            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Camera callback registered, cameras=${cameraManager.cameraIdList.toList()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register camera callback", e)
        }

        if (BuildConfig.DEBUG) Log.d(TAG, "GhostAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString() ?: return

            // Важно: обновляем foreground ДО любых ранних return,
            // иначе Face Wake-пакет не попадёт в foregroundPackage.
            foregroundPackage = packageName

            // Пересчитываем isCameraInUse при смене foreground:
            // Face Wake → false, мессенджер → true (если камера занята).
            if (busyCameras.isNotEmpty()) {
                val faceWake = isFaceWakeForeground()
                isCameraInUse = !faceWake
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Foreground changed: $packageName, faceWake=$faceWake, " +
                            "isCameraInUse=$isCameraInUse")
                }
            }

            if (isSystemUiOrLauncher(packageName)) {
                return
            }

            isCameraOrGalleryActive = packageName.contains("camera", ignoreCase = true) ||
                    packageName.contains("gallery", ignoreCase = true) ||
                    packageName.contains("video", ignoreCase = true)

            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Window: $packageName, camera=$isCameraOrGalleryActive")
            }
        }
    }

    private fun isSystemUiOrLauncher(packageName: String): Boolean {
        if (packageName.contains("launcher")) {
            isCameraOrGalleryActive = false
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Window: $packageName (Launcher), camera=false")
            }
            return true
        }
        return packageName == "android" ||
                packageName == "com.android.systemui" ||
                packageName.contains("upslide") ||
                packageName.contains("overlay")
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()

        try {
            if (::cameraManager.isInitialized) {
                cameraManager.unregisterAvailabilityCallback(cameraAvailabilityCallback)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister camera callback", e)
        }

        watchdogHandler.removeCallbacks(watchdogRunnable)

        if (instance == this) {
            instance = null
        }
    }

    fun lockScreen(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
    }
}