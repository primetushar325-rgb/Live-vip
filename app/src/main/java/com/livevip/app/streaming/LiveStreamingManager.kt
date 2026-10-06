package com.livevip.app.streaming

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import com.livevip.app.camera.CameraConfig
import com.livevip.app.data.SettingsRepository
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.library.rtmp.RtmpCamera2
import com.pedro.library.view.OpenGlView
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.min
import kotlin.math.pow

/**
 * Central streaming engine (singleton).
 *
 * UI (HomeActivity)          LiveStreamingService (foreground)
 *        \                       /
 *         LiveStreamingManager
 *                  |
 *             RtmpCamera2  (RootEncoder)
 *                  |-- Camera2 capture
 *                  |-- H.264 hardware VideoEncoder
 *                  |-- AAC AudioEncoder
 *                  |-- FLV Muxer
 *                  '-- RTMP / RTMPS client
 *
 * Nothing here is created at app startup — the engine is built lazily
 * the first time the user enables the camera preview or starts a stream.
 */
object LiveStreamingManager {

    interface Listener {
        fun onStateChanged(state: StreamState, message: String?)
        fun onStatsChanged(stats: StreamStats)
    }

    private const val TAG = "LiveVipStream"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()

    private var camera: RtmpCamera2? = null
    private var appContext: Context? = null

    @Volatile var state: StreamState = StreamState.OFFLINE
        private set
    @Volatile var stats: StreamStats = StreamStats()
        private set

    private var activeConfig: StreamConfig? = null
    private var reconnectAttempt = 0
    private var streamStartElapsed = 0L
    private var lastBitrateKbps = 0L
    private var debugLogging = false

    val isStreaming: Boolean get() = camera?.isStreaming == true
    val isOnPreview: Boolean get() = camera?.isOnPreview == true
    val isMuted: Boolean get() = camera?.isAudioMuted == true

    // ------------------------------------------------------------------
    // Engine lifecycle
    // ------------------------------------------------------------------

    private val connectChecker = object : ConnectChecker {
        override fun onConnectionStarted(url: String) {
            // Never log the url — it contains the stream key.
            setState(StreamState.CONNECTING, null)
        }

        override fun onConnectionSuccess() {
            reconnectAttempt = 0
            streamStartElapsed = System.currentTimeMillis()
            setState(StreamState.LIVE, null)
            startStatsTicker()
        }

        override fun onConnectionFailed(reason: String) {
            if (debugLogging) Log.w(TAG, "Connection failed: ${sanitize(reason)}")
            val cfg = activeConfig
            val cam = camera
            if (cfg != null && cam != null && cfg.autoReconnect &&
                reconnectAttempt < cfg.maxReconnectAttempts
            ) {
                reconnectAttempt++
                // Exponential backoff: 2s, 4s, 8s ... capped at 30s.
                val delayMs = min(2000L * 2.0.pow(reconnectAttempt - 1).toLong(), 30_000L)
                val scheduled = try {
                    cam.getStreamClient().reTry(delayMs, reason, null)
                } catch (t: Throwable) {
                    false
                }
                if (scheduled) {
                    setState(
                        StreamState.RECONNECTING,
                        "Reconnecting… attempt $reconnectAttempt of ${cfg.maxReconnectAttempts}"
                    )
                    return
                }
            }
            // No more retries: release everything safely.
            mainHandler.post { internalStop(StreamState.ERROR, "Stream disconnected") }
        }

        override fun onDisconnect() {
            if (state != StreamState.RECONNECTING && state != StreamState.ERROR) {
                setState(StreamState.OFFLINE, null)
            }
        }

        override fun onAuthError() {
            mainHandler.post {
                internalStop(StreamState.ERROR, "Authentication failed — check your stream key")
            }
        }

        override fun onAuthSuccess() { /* no-op */ }

        override fun onNewBitrate(bitrate: Long) {
            lastBitrateKbps = bitrate / 1000
        }
    }

    /** Lazily create (or return) the engine. Safe to call repeatedly. */
    @Synchronized
    private fun engine(context: Context): RtmpCamera2 {
        val existing = camera
        if (existing != null) return existing
        appContext = context.applicationContext
        val cam = RtmpCamera2(context.applicationContext, connectChecker)
        try {
            cam.getStreamClient().setLogs(false) // never leak urls/keys into logcat
        } catch (_: Throwable) {
        }
        camera = cam
        return cam
    }

    // ------------------------------------------------------------------
    // Preview control
    // ------------------------------------------------------------------

    /** Attach the on-screen preview and start the camera. */
    fun startPreview(context: Context, view: OpenGlView): Boolean {
        return try {
            val cam = engine(context)
            cam.replaceView(view)
            if (!cam.isOnPreview) cam.startPreview(CameraHelper.Facing.BACK)
            true
        } catch (t: Throwable) {
            if (debugLogging) Log.e(TAG, "startPreview failed", t)
            false
        }
    }

    /** Detach the on-screen view (keeps streaming in background if live). */
    fun detachPreview() {
        val cam = camera ?: return
        val ctx = appContext ?: return
        try {
            if (cam.isStreaming) {
                cam.replaceView(ctx)
            } else if (cam.isOnPreview) {
                cam.stopPreview()
            }
        } catch (t: Throwable) {
            if (debugLogging) Log.e(TAG, "detachPreview failed", t)
        }
    }

    /** Re-attach preview when returning to the app. */
    fun reattachPreview(view: OpenGlView) {
        val cam = camera ?: return
        try {
            if (cam.isStreaming) {
                cam.replaceView(view)
            }
        } catch (t: Throwable) {
            if (debugLogging) Log.e(TAG, "reattachPreview failed", t)
        }
    }

    fun stopPreview() {
        val cam = camera ?: return
        try {
            if (!cam.isStreaming && cam.isOnPreview) cam.stopPreview()
        } catch (t: Throwable) {
            if (debugLogging) Log.e(TAG, "stopPreview failed", t)
        }
    }

    // ------------------------------------------------------------------
    // Streaming control
    // ------------------------------------------------------------------

    /**
     * Prepare encoders and start the RTMP/RTMPS stream.
     * Returns null on success or a human-readable error message.
     */
    fun startStream(context: Context, config: StreamConfig): String? {
        val cam = engine(context)
        if (cam.isStreaming) return "Already streaming"
        if (!config.isValidUrl()) return "Invalid RTMP URL — it must start with rtmp:// or rtmps://"

        debugLogging = SettingsRepository.get(context).debugLogging
        activeConfig = config
        reconnectAttempt = 0

        return try {
            // --- Closest supported resolution fallback ---
            val requested = Size(config.videoWidth, config.videoHeight)
            val supported = try {
                when (cam.cameraFacing) {
                    CameraHelper.Facing.FRONT -> cam.resolutionsFront
                    else -> cam.resolutionsBack
                }
            } catch (t: Throwable) {
                emptyList()
            }
            val size = CameraConfig.closestSupported(requested, supported)

            val audioOk = cam.prepareAudio(
                config.audioBitrateKbps * 1024,
                config.sampleRate,
                config.stereo,
                config.echoCanceler,
                config.noiseSuppressor
            )
            if (!audioOk) return "Audio encoder unavailable — unsupported audio configuration"

            val rotation = CameraHelper.getCameraOrientation(context)
            var videoOk = cam.prepareVideo(
                size.width,
                size.height,
                config.fps,
                config.videoBitrateKbps * 1024,
                config.keyframeIntervalSec,
                rotation
            )
            if (!videoOk) {
                // Fallback: safe baseline 640x480@30.
                videoOk = cam.prepareVideo(640, 480, 30, 1200 * 1024, 2, rotation)
            }
            if (!videoOk) return "Video encoder unavailable — this device has no compatible H.264 encoder"

            try {
                cam.getStreamClient().setReTries(config.maxReconnectAttempts)
            } catch (_: Throwable) {
            }

            setState(StreamState.CONNECTING, null)
            cam.startStream(config.fullUrl())
            null
        } catch (t: Throwable) {
            if (debugLogging) Log.e(TAG, "startStream failed", t)
            internalStop(StreamState.ERROR, null)
            "Could not start the stream: ${t.message ?: "unknown error"}"
        }
    }

    /** Stop streaming and release encoder/network resources. */
    fun stopStream() {
        mainHandler.post { internalStop(StreamState.OFFLINE, null) }
    }

    private fun internalStop(finalState: StreamState, message: String?) {
        stopStatsTicker()
        val cam = camera
        try {
            if (cam != null && cam.isStreaming) cam.stopStream()
        } catch (t: Throwable) {
            if (debugLogging) Log.e(TAG, "stopStream failed", t)
        }
        activeConfig = null
        reconnectAttempt = 0
        lastBitrateKbps = 0
        stats = StreamStats()
        setState(finalState, message)
        notifyStats()
    }

    /** Full release (app exit). */
    fun release() {
        internalStop(StreamState.OFFLINE, null)
        try {
            camera?.stopPreview()
        } catch (_: Throwable) {
        }
        camera = null
        appContext = null
    }

    // ------------------------------------------------------------------
    // In-stream controls
    // ------------------------------------------------------------------

    fun switchCamera(): Boolean = try {
        camera?.switchCamera(); true
    } catch (t: Throwable) {
        if (debugLogging) Log.e(TAG, "switchCamera failed", t)
        false
    }

    /** Returns new lantern state, or null when flash is unsupported. */
    fun toggleLantern(): Boolean? {
        val cam = camera ?: return null
        return try {
            if (cam.isLanternEnabled) {
                cam.disableLantern(); false
            } else {
                cam.enableLantern(); true
            }
        } catch (t: Throwable) {
            null
        }
    }

    /** Returns new muted state. Only effective while streaming. */
    fun toggleMute(): Boolean {
        val cam = camera ?: return false
        return try {
            if (cam.isAudioMuted) {
                cam.enableAudio(); false
            } else {
                cam.disableAudio(); true
            }
        } catch (t: Throwable) {
            false
        }
    }

    // ------------------------------------------------------------------
    // Stats ticker
    // ------------------------------------------------------------------

    private val statsTicker = object : Runnable {
        override fun run() {
            val cam = camera
            if (cam != null && cam.isStreaming && state == StreamState.LIVE) {
                val dropped = try {
                    cam.getStreamClient().getDroppedVideoFrames() + cam.getStreamClient().getDroppedAudioFrames()
                } catch (t: Throwable) {
                    0L
                }
                val congestion = try {
                    cam.getStreamClient().hasCongestion(20f)
                } catch (t: Throwable) {
                    false
                }
                stats = StreamStats(
                    bitrateKbps = lastBitrateKbps,
                    durationSec = (System.currentTimeMillis() - streamStartElapsed) / 1000,
                    droppedFrames = dropped,
                    congestion = congestion,
                    fps = activeConfig?.fps ?: 0
                )
                notifyStats()
            }
            if (state == StreamState.LIVE || state == StreamState.RECONNECTING) {
                mainHandler.postDelayed(this, 1000)
            }
        }
    }

    private fun startStatsTicker() {
        mainHandler.removeCallbacks(statsTicker)
        mainHandler.post(statsTicker)
    }

    private fun stopStatsTicker() {
        mainHandler.removeCallbacks(statsTicker)
    }

    // ------------------------------------------------------------------
    // Listeners
    // ------------------------------------------------------------------

    fun addListener(listener: Listener) {
        listeners.addIfAbsent(listener)
        listener.onStateChanged(state, null)
        listener.onStatsChanged(stats)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    private fun setState(newState: StreamState, message: String?) {
        state = newState
        mainHandler.post {
            listeners.forEach { it.onStateChanged(newState, message) }
        }
    }

    private fun notifyStats() {
        mainHandler.post {
            listeners.forEach { it.onStatsChanged(stats) }
        }
    }

    /** Strip anything that could contain credentials from log text. */
    private fun sanitize(text: String): String =
        text.replace(Regex("rtmps?://\\S+"), "rtmps://***")
}
