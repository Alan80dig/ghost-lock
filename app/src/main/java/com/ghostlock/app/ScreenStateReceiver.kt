package com.ghostlock.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class ScreenStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d("GhostLock", "ScreenStateReceiver: received action $action")

        when (action) {
            Intent.ACTION_USER_PRESENT,
            Intent.ACTION_BOOT_COMPLETED -> {
                try {
                    LockService.stoppedByUser = false
                    LockService.startIfPermitted(context)
                } catch (e: Exception) {
                    Log.e("GhostLock", "Не удалось запустить LockService из бродкаста $action", e)
                }
            }
        }
    }
}