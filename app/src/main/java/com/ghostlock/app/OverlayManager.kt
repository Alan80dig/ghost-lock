package com.ghostlock.app

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager

class OverlayManager(private val context: Context) {

    private var overlayView: View? = null
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var isTouchBlocked = false

    var onTimeout: (() -> Unit)? = null
    var onDismiss: (() -> Unit)? = null

    fun show() {
        if (overlayView != null) return
        if (!Settings.canDrawOverlays(context)) return

        overlayView = object : View(context) {
            override fun onAttachedToWindow() {
                super.onAttachedToWindow()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    windowInsetsController?.let { controller ->
                        controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                        controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    }
                }
            }
        }.apply {
            setBackgroundColor(0xFF000000.toInt())
            setOnTouchListener { _, event ->
                if (isTouchBlocked && event.action == MotionEvent.ACTION_DOWN) true else false
            }
        }

        val flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        try {
            windowManager.addView(overlayView, params)
        } catch (_: Exception) {}
    }

    fun setTouchBlocking(blocked: Boolean) {
        isTouchBlocked = blocked
    }

    fun hide() {
        overlayView?.let {
            try {
                if (it.isAttachedToWindow) {
                    windowManager.removeView(it)
                }
            } catch (_: Exception) {}
        }
        overlayView = null
        onDismiss?.invoke()
    }

    fun isShowing(): Boolean = overlayView != null

    fun destroy() {
        hide()
    }
}