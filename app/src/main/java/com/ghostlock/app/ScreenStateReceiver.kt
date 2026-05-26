package com.ghostlock.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ScreenStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SCREEN_ON -> {
                LockService.startIfPermitted(context)
            }
            Intent.ACTION_SCREEN_OFF -> {
                val stopIntent = Intent(context, LockService::class.java).apply {
                    action = "STOP_LISTENING"
                }
                context.startService(stopIntent)
            }
            Intent.ACTION_BOOT_COMPLETED -> {
                // После перезагрузки — запускаем, если права есть
                LockService.startIfPermitted(context)
            }
        }
    }
}
