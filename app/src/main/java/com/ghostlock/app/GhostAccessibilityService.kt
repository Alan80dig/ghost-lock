package com.ghostlock.app

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class GhostAccessibilityService : AccessibilityService() {

    companion object {
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

    private val cameraAvailabilityCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraUnavailable(cameraId: String) {
            busyCameras.add(cameraId)
            isCameraInUse = busyCameras.isNotEmpty()
            if (BuildConfig.DEBUG) {
                Log.d("GhostLock", "Camera busy: $cameraId, total busy=${busyCameras.size}")
            }
        }

        override fun onCameraAvailable(cameraId: String) {
            busyCameras.remove(cameraId)
            isCameraInUse = busyCameras.isNotEmpty()
            if (BuildConfig.DEBUG) {
                Log.d("GhostLock", "Camera free: $cameraId, total busy=${busyCameras.size}")
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        // Регистрируем отслеживание камеры
        try {
            cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cameraManager.registerAvailabilityCallback(
                cameraAvailabilityCallback,
                Handler(Looper.getMainLooper())
            )
            if (BuildConfig.DEBUG) {
                Log.d("GhostLock", "Camera availability callback registered")
            }
        } catch (e: Exception) {
            Log.e("GhostLock", "Failed to register camera callback", e)
        }

        if (BuildConfig.DEBUG) Log.d("GhostLock", "GhostAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString() ?: return

            if (isSystemUiOrLauncher(packageName)) {
                return
            }

            isCameraOrGalleryActive = packageName.contains("camera", ignoreCase = true) ||
                    packageName.contains("gallery", ignoreCase = true) ||
                    packageName.contains("video", ignoreCase = true)

            if (BuildConfig.DEBUG) {
                Log.d("GhostLock", "Window: $packageName, camera=$isCameraOrGalleryActive")
            }
        }
    }

    private fun isSystemUiOrLauncher(packageName: String): Boolean {
        if (packageName.contains("launcher")) {
            isCameraOrGalleryActive = false
            if (BuildConfig.DEBUG) {
                Log.d("GhostLock", "Window: $packageName (Launcher), camera=false")
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

        // Снимаем callback
        try {
            if (::cameraManager.isInitialized) {
                cameraManager.unregisterAvailabilityCallback(cameraAvailabilityCallback)
            }
        } catch (e: Exception) {
            Log.e("GhostLock", "Failed to unregister camera callback", e)
        }

        if (instance == this) {
            instance = null
        }
    }

    fun lockScreen(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
    }
}