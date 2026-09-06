package com.ghostlock.app

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class GhostAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        private var instance: GhostAccessibilityService? = null

        @Volatile
        var isCameraOrGalleryActive: Boolean = false
            private set

        fun getInstance(): GhostAccessibilityService? = instance

        fun resetCameraFlag() {
            isCameraOrGalleryActive = false
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d("GhostLock", "GhostAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString() ?: return

            // Игнорируем системные пакеты
            if (isSystemUiOrLauncher(packageName)) {
                return
            }

            // Обновляем флаг: true если камера/галерея/видео, false если обычное приложение
            isCameraOrGalleryActive = packageName.contains("camera", ignoreCase = true) ||
                    packageName.contains("gallery", ignoreCase = true) ||
                    packageName.contains("video", ignoreCase = true)

            Log.d("GhostLock", "Window: $packageName, camera=$isCameraOrGalleryActive")
        }
    }

    private fun isSystemUiOrLauncher(packageName: String): Boolean {
        // Лончер НЕ игнорируем — он сбрасывает флаг камеры
        if (packageName.contains("launcher")) {
            isCameraOrGalleryActive = false
            Log.d("GhostLock", "Window: $packageName (Launcher), camera=false")
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
        if (instance == this) {
            instance = null
        }
    }

    fun lockScreen(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
    }
}