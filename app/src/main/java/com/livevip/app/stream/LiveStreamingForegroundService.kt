package com.livevip.app.stream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.livevip.app.R
import com.livevip.app.model.EngineSnapshot
import com.livevip.app.model.StreamSettings
import com.livevip.app.ui.MainActivity

class LiveStreamingForegroundService : Service(), LiveStreamingEngine.Listener {
    private var engine: LiveStreamingEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            engine?.stop()
            stopStreamingService()
            return START_NOT_STICKY
        }
        val settings = intent?.extras?.let(StreamSettings::fromBundle)
        if (settings == null) {
            broadcast(EngineSnapshot(lastError = "VIDEO_URI_INVALID"))
            stopSelf()
            return START_NOT_STICKY
        }
        engine?.stop()
        startForeground(NOTIFICATION_ID, notification("PREPARING"))
        acquireWakeLock()
        engine = LiveStreamingEngine(this, settings, this).also { it.start() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        engine?.stop()
        engine = null
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onSnapshot(snapshot: EngineSnapshot) {
        val state = snapshot.state.name
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification(state))
        broadcast(snapshot)
        if (state == "STOPPED" || state == "ERROR") {
            releaseWakeLock()
            if (state == "ERROR") stopSelf()
        }
    }

    private fun broadcast(snapshot: EngineSnapshot) {
        sendBroadcast(Intent(ACTION_ENGINE_UPDATE).setPackage(packageName).apply {
            putExtra(EXTRA_STATE, snapshot.state.name)
            putExtra(EXTRA_DIAGNOSTICS, snapshot.asDiagnostics())
        })
    }

    private fun notification(state: String): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_launcher)
        .setContentTitle("LIVE VIP")
        .setContentText("Stream status: $state")
        .setOngoing(state !in setOf("STOPPED", "ERROR"))
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        .addAction(NotificationCompat.Action(0, "Stop", PendingIntent.getService(this, 1, Intent(this, LiveStreamingForegroundService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)))
        .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Live streaming", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LiveVip:Streaming").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    private fun stopStreamingService() {
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        const val ACTION_STOP = "com.livevip.app.STOP_STREAM"
        const val ACTION_ENGINE_UPDATE = "com.livevip.app.ENGINE_UPDATE"
        const val EXTRA_STATE = "state"
        const val EXTRA_DIAGNOSTICS = "diagnostics"
        private const val CHANNEL_ID = "live_streaming"
        private const val NOTIFICATION_ID = 7101
    }
}

private fun EngineSnapshot.asDiagnostics(): String = buildString {
    appendLine("Video: ${if (decoderReady) "READY" else "NOT READY"}")
    appendLine("Decoder: ${if (decoderReady) "READY" else "NOT READY"}")
    appendLine("First Frame: ${if (firstFrameReceived) "YES" else "NO"}")
    appendLine("Preview Frames: $previewFrames")
    appendLine("Encoder: ${if (encoderReady) "READY" else "NOT READY"}")
    appendLine("Encoded Frames: $encodedFrames")
    appendLine("Encoded Bytes: $encodedBytes")
    appendLine("Audio: ${if (audioReady) "READY" else "NOT READY"}")
    appendLine("RTMP: ${state.name}")
    appendLine("Packets Sent: $packetsSent")
    appendLine("Bytes Sent: $bytesSent")
    appendLine("Bitrate: ${bitrate}bps")
    appendLine("FPS: %.2f".format(actualFps))
    appendLine("A/V Offset: ${audioVideoOffsetUs}us")
    appendLine("Loop: $loopCount")
    appendLine("Reconnect: $reconnectCount")
    appendLine("Last Error: $lastError")
}
