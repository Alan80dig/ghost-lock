package com.ghostlock.app

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

class AdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        // Права админа выданы — можно запускать сервис
        LockService.startIfPermitted(context)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        // Права админа отозваны — останавливаем сервис
        LockService.stop(context)
    }
}
