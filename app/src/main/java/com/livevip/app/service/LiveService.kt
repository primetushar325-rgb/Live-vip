package com.livevip.app.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.livevip.app.LiveVipApp
import com.livevip.app.R
import com.livevip.app.engine.LiveEngine
import com.livevip.app.engine.LiveState
import com.livevip.app.engine.LiveSnapshot
import com.livevip.app.ui.HomeActivity
import java.util.Locale

/**
 * FOREGROUND SERVICE — owns the live session while the app is in the
 * background: video playback, audio, encoder and the RTMP/RTMPS connection
 * all survive here. Activity destruction/recreation can never stop the
 * stream; only an explicit user STOP does.
 *
 * OWNERSHIP RULE: the service never contains streaming logic — it reflects
 * the state of [LiveEngine] and guarantees foreground priority.
 *
 * Android 14+ correctness: the foreground-service type is chosen at start
 * time from what the broadcast actually uses (mediaPlayback for VIDEO mode,
 * camera|microphone for CAMERA mode), so a video-only broadcast needs no
 * camera permission.
 */
class LiveService : Service(), LiveEngine.Listener {

    private var lastState: LiveState = LiveState.OFFLINE
    private var lastSnapshot: LiveSnapshot = LiveSnapshot()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                LiveEngine.stopStream()
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }

            else -> {
                startAsForeground()
                LiveEngine.addListener(this)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        LiveEngine.removeListener(this)
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Engine callbacks
    // ------------------------------------------------------------------

    override fun onLiveStateChanged(state: LiveState, message: String?) {
        lastState = state
        when (state) {
            LiveState.OFFLINE, LiveState.ERROR, LiveState.STOPPED -> {
                // Session ended — release foreground + service immediately.
                stopForegroundCompat()
                stopSelf()
            }

            else -> updateNotification()
        }
    }

    override fun onLiveSnapshot(snapshot: LiveSnapshot) {
        lastSnapshot = snapshot
        if (lastState == LiveState.SENDING || lastState == LiveState.STREAMING) {
            updateNotification()
        }
    }

    // ------------------------------------------------------------------
    // Notification
    // ------------------------------------------------------------------

    private fun startAsForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Typed FGS (Android 11+): camera|microphone types only exist from
            // API 30 — never pass them to older platforms.
            startForeground(NOTIFICATION_ID, notification, foregroundServiceTypes())
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun foregroundServiceTypes(): Int {
        val cameraGranted = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        val micGranted = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        return when (LiveEngine.mode) {
            LiveEngine.Mode.CAMERA -> {
                var types = 0
                if (cameraGranted) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                if (micGranted) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                if (types == 0) types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                types
            }

            LiveEngine.Mode.VIDEO -> {
                var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                if (micGranted && LiveEngine.micEnabled) {
                    types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
                types
            }
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
            Intent(this, LiveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val statusText = when (lastState) {
            LiveState.CONNECTING -> getString(R.string.state_connecting)
            LiveState.CONNECTED -> getString(R.string.state_connected)
            LiveState.SENDING -> getString(R.string.state_sending)
            LiveState.STREAMING -> {
                val connection =
                    if (lastSnapshot.congestion) getString(R.string.connection_poor)
                    else getString(R.string.connection_good)
                val base = getString(
                    R.string.notification_live_format,
                    formatDuration(lastSnapshot.durationSec),
                    connection
                )
                if (lastSnapshot.bitrateKbps > 0) {
                    "$base • ${lastSnapshot.bitrateKbps} kbps • ${lastSnapshot.fps} FPS"
                } else base
            }

            LiveState.RECONNECTING -> getString(R.string.state_reconnecting)
            else -> getString(R.string.state_offline)
        }

        return NotificationCompat.Builder(this, LiveVipApp.STREAM_CHANNEL_ID)
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
            val intent = Intent(context, LiveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LiveService::class.java))
        }
    }
}
