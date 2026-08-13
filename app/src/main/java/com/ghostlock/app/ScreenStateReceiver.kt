package com.ghostlock.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ScreenStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_USER_PRESENT,
            Intent.ACTION_BOOT_COMPLETED -> {
                LockService.startIfPermitted(context)
            }
        }
    }
}