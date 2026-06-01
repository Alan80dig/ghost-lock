package com.ghostlock.app

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

class OverlayManager(private val context: Context) {

    private var overlayView: View? = null
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null

    var onTimeout: (() -> Unit)? = null

    fun show() {
        if (overlayView != null) return
        if (!Settings.canDrawOverlays(context)) return

        overlayView = View(context).apply {
            setBackgroundColor(0xFF000000.toInt())
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    // Перехватываем касание
                }
                true
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        try {
            windowManager.addView(overlayView, params)
            startTimeout()
        } catch (_: Exception) {}
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
        stopTimeout()
    }

    fun isShowing(): Boolean = overlayView != null

    private fun startTimeout() {
        stopTimeout()
        timeoutRunnable = Runnable {
            hide()
            onTimeout?.invoke()
        }
        handler.postDelayed(timeoutRunnable!!, 15_000)
    }

    private fun stopTimeout() {
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        timeoutRunnable = null
    }

    fun destroy() {
        hide()
    }
}