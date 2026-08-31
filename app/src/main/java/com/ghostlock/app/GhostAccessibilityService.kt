package com.ghostlock.app

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class GhostAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    fun lockScreen(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    companion object {
        @Volatile
        private var instance: GhostAccessibilityService? = null

        fun getInstance(): GhostAccessibilityService? = instance
        fun isRunning(): Boolean = instance != null
    }
}