package com.livevip.app.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.livevip.app.LiveVipApplication
import com.livevip.app.R
import com.livevip.app.streaming.LiveStreamingManager
import com.livevip.app.streaming.StreamState
import com.livevip.app.streaming.StreamStats
import com.livevip.app.ui.HomeActivity
import java.util.Locale

/**
 * Foreground service that keeps an active live stream alive while the
 * app is in the background and shows the persistent notification:
 *
 *   LIVE VIP — Streaming live  [duration, connection status, Stop action]
 *
 * The service never owns streaming logic — it only reflects the state
 * of [LiveStreamingManager] and guarantees foreground priority.
 * It is started when a stream starts and fully released when it stops.
 */
class LiveStreamingService : Service(), LiveStreamingManager.Listener {

    private var lastState: StreamState = StreamState.OFFLINE
    private var lastStats: StreamStats = StreamStats()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                LiveStreamingManager.stopStream()
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }

            else -> {
                startAsForeground()
                LiveStreamingManager.addListener(this)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        LiveStreamingManager.removeListener(this)
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Manager callbacks
    // ------------------------------------------------------------------

    override fun onStateChanged(state: StreamState, message: String?) {
        lastState = state
        when (state) {
            StreamState.OFFLINE, StreamState.ERROR -> {
                // Stream ended — release foreground + service immediately.
                stopForegroundCompat()
                stopSelf()
            }

            else -> updateNotification()
        }
    }

    override fun onStatsChanged(stats: StreamStats) {
        lastStats = stats
        if (lastState == StreamState.LIVE) updateNotification()
    }

    // ------------------------------------------------------------------
    // Notification
    // ------------------------------------------------------------------

    private fun startAsForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 1,
            Intent(this, HomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 2,
            Intent(this, LiveStreamingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val statusText = when (lastState) {
            StreamState.LIVE -> {
                val connection =
                    if (lastStats.congestion) getString(R.string.connection_poor)
                    else getString(R.string.connection_good)
                getString(
                    R.string.notification_live_format,
                    formatDuration(lastStats.durationSec),
                    connection
                )
            }

            StreamState.CONNECTING -> getString(R.string.state_connecting)
            StreamState.RECONNECTING -> getString(R.string.state_reconnecting)
            else -> getString(R.string.state_offline)
        }

        return NotificationCompat.Builder(this, LiveVipApplication.STREAM_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_live)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                R.drawable.ic_stop,
                getString(R.string.action_stop_stream),
                stopIntent
            )
            .build()
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun formatDuration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }

    companion object {
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.livevip.app.action.STOP_STREAM"

        fun start(context: Context) {
            val intent = Intent(context, LiveStreamingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LiveStreamingService::class.java))
        }
    }
}
