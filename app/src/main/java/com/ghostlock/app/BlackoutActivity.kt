package com.ghostlock.app

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager

class BlackoutActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        window.navigationBarColor = Color.BLACK
        window.statusBarColor = Color.BLACK
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)

        Handler(Looper.getMainLooper()).postDelayed({
            finish()
        }, 5000)
    }

    override fun onPause() {
        super.onPause()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        LockService.getInstance()?.resetLockState()
    }
}