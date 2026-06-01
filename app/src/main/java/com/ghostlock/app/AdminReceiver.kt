package com.ghostlock.app

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

class AdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        LockService.startIfPermitted(context)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        LockService.stop(context)
    }
}