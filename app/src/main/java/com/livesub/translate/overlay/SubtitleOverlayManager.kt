package com.livesub.translate.overlay

import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.livesub.translate.R

class SubtitleOverlayManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlayView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var tvOriginal: TextView? = null
    private var tvTranslated: TextView? = null
    private var isConfigCallbackRegistered = false

    private val hideRunnable = Runnable {
        overlayView?.visibility = View.GONE
    }

    private val configCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            val view = overlayView ?: return
            val params = layoutParams ?: return
            params.y = bottomOffsetPx(newConfig)
            runCatching { windowManager.updateViewLayout(view, params) }
        }

        override fun onLowMemory() = Unit
    }

    fun show() {
        mainHandler.post {
            if (overlayView != null) {
                overlayView?.visibility = View.VISIBLE
                return@post
            }

            val view = LayoutInflater.from(context).inflate(R.layout.overlay_subtitle, null)
            tvOriginal = view.findViewById(R.id.tvOverlayOriginal)
            tvTranslated = view.findViewById(R.id.tvOverlayTranslated)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = bottomOffsetPx(context.resources.configuration)
            }

            view.visibility = View.GONE
            windowManager.addView(view, params)
            overlayView = view
            layoutParams = params

            if (!isConfigCallbackRegistered) {
                context.registerComponentCallbacks(configCallbacks)
                isConfigCallbackRegistered = true
            }
        }
    }

    fun updateSubtitle(
        original: String,
        translated: String,
        showOriginal: Boolean,
        isFinal: Boolean = true
    ) {
        mainHandler.post {
            val view = overlayView ?: return@post
            tvOriginal?.apply {
                text = original
                visibility = if (showOriginal && original.isNotBlank()) View.VISIBLE else View.GONE
                alpha = if (isFinal) 0.95f else 0.72f
                setTypeface(null, if (isFinal) Typeface.NORMAL else Typeface.ITALIC)
            }
            tvTranslated?.apply {
                text = translated
                alpha = if (isFinal) 1.0f else 0.85f
                setTypeface(null, if (isFinal) Typeface.BOLD else Typeface.ITALIC)
            }
            view.visibility = View.VISIBLE

            mainHandler.removeCallbacks(hideRunnable)
            mainHandler.postDelayed(hideRunnable, AUTO_HIDE_DELAY_MS)
        }
    }

    fun hide() {
        mainHandler.post {
            mainHandler.removeCallbacks(hideRunnable)
            if (isConfigCallbackRegistered) {
                runCatching { context.unregisterComponentCallbacks(configCallbacks) }
                isConfigCallbackRegistered = false
            }
            overlayView?.let {
                runCatching { windowManager.removeView(it) }
            }
            overlayView = null
            layoutParams = null
            tvOriginal = null
            tvTranslated = null
        }
    }

    private fun bottomOffsetPx(config: Configuration): Int {
        val dp = if (config.orientation == Configuration.ORIENTATION_LANDSCAPE) 28 else 56
        return (dp * context.resources.displayMetrics.density).toInt()
    }

    companion object {
        private const val AUTO_HIDE_DELAY_MS = 5500L
    }
}
