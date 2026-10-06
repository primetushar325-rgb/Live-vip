package com.livevip.app.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.livevip.app.R
import com.livevip.app.streaming.LiveStreamingManager
import com.livevip.app.streaming.StreamState
import com.livevip.app.ui.HomeActivity
import java.util.Locale

/**
 * FLOATING LIVE CONTROL — optional bubble shown while the app is in the
 * background and a broadcast is running.
 *
 *   🔴 LIVE 01:23:45
 *
 *   Tap        → open the Live Dashboard
 *   Double tap → minimize (collapse to a small dot)
 *   Close (×)  → closes the BUBBLE ONLY — the live stream is NEVER stopped
 *                by closing the bubble (stopping live is an explicit action
 *                in the dashboard / notification).
 *
 * Requires SYSTEM_ALERT_WINDOW (canDrawOverlays) — requested from the
 * dashboard with the standard settings intent when missing.
 */
class LiveBubbleService : Service(), LiveStreamingManager.Listener {

    private var windowManager: WindowManager? = null
    private var bubble: View? = null
    private var label: TextView? = null
    private var minimized = false
    private val handler = Handler(Looper.getMainLooper())
    private var lastTapMs = 0L

    private val uiTicker = object : Runnable {
        override fun run() {
            updateBubble()
            if (LiveStreamingManager.isStreaming ||
                LiveStreamingManager.state == StreamState.CONNECTING ||
                LiveStreamingManager.state == StreamState.RECONNECTING
            ) {
                handler.postDelayed(this, 1000)
            } else {
                stopSelf()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CLOSE) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (bubble == null) showBubble()
        LiveStreamingManager.addListener(this)
        handler.removeCallbacks(uiTicker)
        handler.post(uiTicker)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        LiveStreamingManager.removeListener(this)
        handler.removeCallbacks(uiTicker)
        removeBubble()
        super.onDestroy()
    }

    override fun onStateChanged(state: StreamState, message: String?) {
        if (state == StreamState.OFFLINE || state == StreamState.ERROR) stopSelf()
    }

    override fun onStatsChanged(stats: com.livevip.app.streaming.StreamStats) {
        updateBubble()
    }

    // ------------------------------------------------------------------
    // Bubble UI
    // ------------------------------------------------------------------

    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    private fun showBubble() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val density = resources.displayMetrics.density
        val padding = (12 * density).toInt()
        val radius = (22 * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(padding, (8 * density).toInt(), padding, (8 * density).toInt()
            )
            background = makeBubbleBackground(this@LiveBubbleService, radius)
        }

        val dot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams((10 * density).toInt(), (10 * density).toInt())
        }
        dot.background = makeDot(this)

        val labelView = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            text = "LIVE"
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = (8 * density).toInt() }
        }
        label = labelView

        val close = TextView(this).apply {
            text = "×"
            setTextColor(0xB3FFFFFF.toInt())
            textSize = 15f
            setPadding((6 * density).toInt(), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                // Close the BUBBLE only. The live stream keeps running.
                stopSelf()
            }
        }

        root.addView(dot)
        root.addView(labelView)
        root.addView(close)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (24 * density).toInt()
            y = (120 * density).toInt()
        }

        // Drag + tap handling.
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        root.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) moved = true
                    params.x = startX + dx.toInt()
                    params.y = startY + dy.toInt()
                    try {
                        wm.updateViewLayout(root, params)
                    } catch (_: Throwable) {
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!moved) {
                        val now = System.currentTimeMillis()
                        if (now - lastTapMs < 300) {
                            // Double tap → minimize/expand toggle.
                            minimized = !minimized
                            text.visibility =
                                if (minimized) View.GONE else View.VISIBLE
                            close.visibility =
                                if (minimized) View.GONE else View.VISIBLE
                        } else {
                            handler.postDelayed({
                                // Single tap → open the Live Dashboard.
                                startActivity(
                                    Intent(this@LiveBubbleService, HomeActivity::class.java)
                                        .addFlags(
                                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                                        )
                                )
                            }, 280)
                        }
                        lastTapMs = now
                    }
                    true
                }

                else -> false
            }
        }

        try {
            wm.addView(root, params)
            bubble = root
        } catch (_: Throwable) {
            stopSelf()
        }
    }

    private fun updateBubble() {
        val stats = LiveStreamingManager.stats
        label?.text = when {
            LiveStreamingManager.state == StreamState.CONNECTING -> "CONNECTING"
            LiveStreamingManager.state == StreamState.RECONNECTING -> "RECONNECTING"
            else -> String.format(Locale.US, "LIVE %02d:%02d:%02d",
                stats.durationSec / 3600, (stats.durationSec % 3600) / 60, stats.durationSec % 60)
        }
    }

    private fun removeBubble() {
        val wm = windowManager ?: return
        val view = bubble ?: return
        try {
            wm.removeView(view)
        } catch (_: Throwable) {
        }
        bubble = null
    }

    private fun makeBubbleBackground(context: android.content.Context, radiusPx: Int) =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(0xE615151D.toInt())
            cornerRadius = radiusPx.toFloat()
            setStroke(1, 0x337C4DFF)
        }

    private fun makeDot(context: android.content.Context) =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(0xFFEF4444.toInt())
        }

    @Suppress("unused")
    private fun keepFrameLayoutImport() = FrameLayout(this)

    companion object {
        const val ACTION_CLOSE = "com.livevip.app.action.CLOSE_BUBBLE"

        fun start(context: android.content.Context) {
            if (!Settings.canDrawOverlays(context)) return
            context.startService(Intent(context, LiveBubbleService::class.java))
        }

        fun stop(context: android.content.Context) {
            context.startService(
                Intent(context, LiveBubbleService::class.java).setAction(ACTION_CLOSE)
            )
        }
    }
}
