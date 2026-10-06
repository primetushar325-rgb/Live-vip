package com.livevip.app.streaming

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceView
import com.livevip.app.audio.MixedFileAudioSource
import com.livevip.app.data.SettingsRepository
import com.livevip.app.media.MediaAnalyzer
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.sources.audio.AudioSource
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.audio.SilenceAudioSource
import com.pedro.encoder.input.sources.video.Camera2Source
import com.pedro.encoder.input.sources.video.VideoFileSource
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.library.rtmp.RtmpStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.min
import kotlin.math.pow

/**
 * LIVE VIP broadcast pipeline (singleton).
 *
 *                 ┌──────────────┐
 *   CAMERA LIVE ──┤              │
 *                 │  RtmpStream  │── H.264 + AAC ──► RTMP/RTMPS
 *   VIDEO LIVE ───┤ (RootEncoder)│
 *    │            └──────────────┘
 *    ├─ VideoFileSource  (decoder + GL timeline, gapless loop)
 *    └─ MixedFileAudioSource (video audio + independent microphone)
 *
 * Key stability rules implemented here:
 *  - VIDEO LOOP ≠ STREAM RESTART: the loop happens inside the decoder;
 *    the encoder surface timeline and the RTMP session are never touched,
 *    so output timestamps stay monotonic (no 50→60→50 regression).
 *  - Mic mute only silences the microphone line — video audio continues.
 *  - Reconnects use exponential backoff with a bounded retry budget.
 *  - Everything is created lazily; nothing runs at app startup.
 */
object LiveStreamingManager {

    enum class Mode { VIDEO, CAMERA }

    interface Listener {
        fun onStateChanged(state: StreamState, message: String?)
        fun onStatsChanged(stats: StreamStats)
    }

    private const val TAG = "LiveVipStream"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()

    private var stream: RtmpStream? = null
    private var appContext: Context? = null

    @Volatile var state: StreamState = StreamState.OFFLINE
        private set
    @Volatile var stats: StreamStats = StreamStats()
        private set
    @Volatile var mode: Mode = Mode.VIDEO
        private set

    // Active sources (only valid between prepare and stop)
    private var videoFileSource: VideoFileSource? = null
    private var mixedAudioSource: MixedFileAudioSource? = null
    private var microphoneSource: MicrophoneSource? = null
    private var cameraSource: Camera2Source? = null

    private var activeConfig: StreamConfig? = null
    private var activeVideoUri: Uri? = null
    private var activeVideoInfo: MediaAnalyzer.VideoInfo? = null
    private var reconnectAttempt = 0
    private var reconnectTotal = 0
    private var streamStartElapsed = 0L
    private var lastBitrateKbps = 0L
    @Volatile private var loopCount = 0
    private var debugLogging = false
    private var prepared = false

    // User-facing mixer state (persists across prepare cycles)
    @Volatile var micEnabled = false
        private set
    @Volatile var videoAudioEnabled = true
        private set
    @Volatile var videoVolume = 1f
        private set
    @Volatile var micVolume = 1f
        private set

    val isStreaming: Boolean get() = stream?.isStreaming == true
    val isOnPreview: Boolean get() = stream?.isOnPreview == true

    // ------------------------------------------------------------------
    // Connection callbacks
    // ------------------------------------------------------------------

    private val connectChecker = object : ConnectChecker {
        override fun onConnectionStarted(url: String) {
            setState(StreamState.CONNECTING, "Connecting to server…")
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
            val s = stream
            if (cfg != null && s != null && cfg.autoReconnect &&
                reconnectAttempt < cfg.maxReconnectAttempts
            ) {
                reconnectAttempt++
                reconnectTotal++
                val delayMs = min(1000L * 2.0.pow(reconnectAttempt - 1).toLong(), 30_000L)
                val scheduled = try {
                    s.getStreamClient().reTry(delayMs, reason, null)
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

    // ------------------------------------------------------------------
    // Engine lifecycle
    // ------------------------------------------------------------------

    @Synchronized
    private fun engine(context: Context): RtmpStream {
        val existing = stream
        if (existing != null) return existing
        appContext = context.applicationContext
        val cam = Camera2Source(context.applicationContext)
        val mic = MicrophoneSource()
        cameraSource = cam
        microphoneSource = mic
        val s = RtmpStream(context.applicationContext, connectChecker, cam, mic)
        try {
            s.getStreamClient().setLogs(false) // never leak urls/keys
        } catch (_: Throwable) {
        }
        stream = s
        return s
    }

    fun setMode(newMode: Mode) {
        if (mode == newMode) return
        mode = newMode
        prepared = false
    }

    // ------------------------------------------------------------------
    // Preview
    // ------------------------------------------------------------------

    /**
     * Start/attach the preview for the current mode.
     * VIDEO mode shows the selected file; CAMERA mode shows the camera.
     * Returns null on success, or an error message.
     */
    fun startPreview(context: Context, view: SurfaceView, videoUri: Uri?): String? {
        return try {
            val s = engine(context)
            if (s.isStreaming) {
                if (!s.isOnPreview) s.startPreview(view)
                return null
            }
            if (s.isOnPreview) s.stopPreview()
            val error = configureSources(context, videoUri, previewOnly = true)
            if (error != null) return error
            if (!prepared) {
                val error2 = prepareEncoders(context, StreamConfig.from(SettingsRepository.get(context)))
                if (error2 != null) return error2
            }
            s.startPreview(view)
            null
        } catch (t: Throwable) {
            if (debugLogging) Log.e(TAG, "startPreview failed", t)
            "Preview failed: ${t.message ?: "unknown error"}"
        }
    }

    fun stopPreview() {
        val s = stream ?: return
        try {
            if (s.isOnPreview) s.stopPreview()
        } catch (t: Throwable) {
            if (debugLogging) Log.e(TAG, "stopPreview failed", t)
        }
    }

    // ------------------------------------------------------------------
    // Source configuration
    // ------------------------------------------------------------------

    /**
     * Point the pipeline at the right sources for the current mode.
     * Returns null on success or a readable error.
     */
    private fun configureSources(
        context: Context,
        videoUri: Uri?,
        previewOnly: Boolean
    ): String? {
        val s = stream ?: return "Engine not ready"
        return when (mode) {
            Mode.VIDEO -> {
                val uri = videoUri ?: return "Select a video first"
                val info = MediaAnalyzer.analyze(context, uri)
                    ?: return "This video format isn't supported on this device. Try an MP4 (H.264/AAC) file."
                try {
                    if (activeVideoUri != uri || videoFileSource == null) {
                        val vSource = VideoFileSource(context.applicationContext, uri, true) { isLoop ->
                            if (isLoop) {
                                loopCount++
                            }
                        }
                        s.changeVideoSource(vSource)
                        videoFileSource = vSource

                        val aSource: AudioSource = if (info.hasAudio) {
                            MixedFileAudioSource(context.applicationContext, uri, true).apply {
                                videoVolume = this@LiveStreamingManager.videoVolume
                                micVolume = this@LiveStreamingManager.micVolume
                                setVideoAudioEnabled(videoAudioEnabled)
                            }
                        } else {
                            SilenceAudioSource()
                        }
                        s.changeAudioSource(aSource)
                        mixedAudioSource = aSource as? MixedFileAudioSource
                        activeVideoUri = uri
                        activeVideoInfo = info
                        prepared = false
                    }
                    null
                } catch (t: Throwable) {
                    if (debugLogging) Log.e(TAG, "video source failed", t)
                    "Could not open this video: ${t.message ?: "unsupported format"}"
                }
            }

            Mode.CAMERA -> {
                try {
                    if (videoFileSource != null || cameraSource?.let { s.videoSource !== it } != false) {
                        val cam = Camera2Source(context.applicationContext)
                        s.changeVideoSource(cam)
                        cameraSource = cam
                        videoFileSource = null
                        val mic = MicrophoneSource()
                        s.changeAudioSource(mic)
                        microphoneSource = mic
                        mixedAudioSource = null
                        activeVideoUri = null
                        activeVideoInfo = null
                        prepared = false
                    }
                    null
                } catch (t: Throwable) {
                    "Camera unavailable: ${t.message ?: "unknown error"}"
                }
            }
        }
    }

    /** Prepare H.264 + AAC encoders. Null on success or readable error. */
    private fun prepareEncoders(context: Context, config: StreamConfig): String? {
        val s = stream ?: return "Engine not ready"
        try {
            if (s.isOnPreview) s.stopPreview()

            // Audio parameters: VIDEO mode must follow the file's format,
            // CAMERA mode follows user settings.
            val info = activeVideoInfo
            val sampleRate: Int
            val stereo: Boolean
            if (mode == Mode.VIDEO && info != null && info.hasAudio) {
                sampleRate = info.sampleRate
                stereo = info.isStereo
            } else {
                sampleRate = config.sampleRate
                stereo = config.stereo
            }

            val audioOk = try {
                s.prepareAudio(
                    sampleRate,
                    stereo,
                    config.audioBitrateKbps * 1024,
                    config.echoCanceler,
                    config.noiseSuppressor
                )
            } catch (t: Throwable) {
                return "Audio setup failed: ${t.message ?: "unsupported audio configuration"}"
            }
            if (!audioOk) return "Audio encoder unavailable on this device"

            val rotation = if (mode == Mode.CAMERA) {
                CameraHelper.getCameraOrientation(context)
            } else 0

            var videoOk = try {
                s.prepareVideo(
                    config.videoWidth,
                    config.videoHeight,
                    config.videoBitrateKbps * 1024,
                    config.fps,
                    config.keyframeIntervalSec,
                    rotation
                )
            } catch (t: Throwable) {
                false
            }
            if (!videoOk) {
                videoOk = try {
                    s.prepareVideo(854, 480, 1200 * 1024, 30, 2, rotation)
                } catch (t: Throwable) {
                    false
                }
            }
            if (!videoOk) return "Video encoder unavailable — no compatible H.264 encoder"

            prepared = true
            return null
        } catch (t: Throwable) {
            if (debugLogging) Log.e(TAG, "prepareEncoders failed", t)
            return "Encoder initialization failed: ${t.message ?: "unknown error"}"
        }
    }

    // ------------------------------------------------------------------
    // Streaming control
    // ------------------------------------------------------------------

    /**
     * Full start flow with staged status updates:
     * Preparing video → Initializing encoder → Connecting → LIVE.
     * Returns null on success or a readable error message.
     */
    fun startStream(
        context: Context,
        config: StreamConfig,
        videoUri: Uri?,
        previewView: SurfaceView?
    ): String? {
        val s = engine(context)
        if (s.isStreaming) return "Already streaming"
        if (!config.isValidUrl()) {
            return "Invalid RTMP URL — it must start with rtmp:// or rtmps://"
        }

        debugLogging = SettingsRepository.get(context).debugLogging
        activeConfig = config
        reconnectAttempt = 0
        reconnectTotal = 0
        loopCount = 0

        setState(StreamState.CONNECTING, "Preparing ${if (mode == Mode.VIDEO) "video" else "camera"}…")
        val sourceError = configureSources(context, videoUri, previewOnly = false)
        if (sourceError != null) {
            setState(StreamState.ERROR, null)
            return sourceError
        }

        setState(StreamState.CONNECTING, "Initializing encoder…")
        val prepError = prepareEncoders(context, config)
        if (prepError != null) {
            setState(StreamState.ERROR, null)
            return prepError
        }

        return try {
            try {
                s.getStreamClient().setReTries(config.maxReconnectAttempts)
            } catch (_: Throwable) {
            }
            setState(StreamState.CONNECTING, "Connecting to server…")
            s.startStream(config.fullUrl())
            // Re-attach on-screen preview (encoders had to re-prepare).
            if (previewView != null) {
                try {
                    if (!s.isOnPreview) s.startPreview(previewView)
                } catch (_: Throwable) {
                }
            }
            // Apply mixer state to live sources.
            applyMixerState()
            null
        } catch (t: Throwable) {
            if (debugLogging) Log.e(TAG, "startStream failed", t)
            internalStop(StreamState.ERROR, null)
            "Could not start the stream: ${t.message ?: "unknown error"}"
        }
    }

    fun stopStream() {
        mainHandler.post { internalStop(StreamState.OFFLINE, null) }
    }

    private fun internalStop(finalState: StreamState, message: String?) {
        stopStatsTicker()
        val s = stream
        try {
            if (s != null && s.isStreaming) s.stopStream()
        } catch (t: Throwable) {
            if (debugLogging) Log.e(TAG, "stopStream failed", t)
        }
        activeConfig = null
        reconnectAttempt = 0
        lastBitrateKbps = 0
        stats = StreamStats()
        prepared = false
        setState(finalState, message)
        notifyStats()
    }

    /** Full release (app exit). */
    fun release() {
        internalStop(StreamState.OFFLINE, null)
        try {
            stream?.stopPreview()
        } catch (_: Throwable) {
        }
        try {
            stream?.release()
        } catch (_: Throwable) {
        }
        stream = null
        videoFileSource = null
        mixedAudioSource = null
        microphoneSource = null
        cameraSource = null
        activeVideoUri = null
        activeVideoInfo = null
        appContext = null
    }

    /** Invalidate prepared encoders after settings change. */
    fun invalidatePreparation() {
        if (!isStreaming) prepared = false
    }

    /** Force source rebuild on next preview/stream (video changed). */
    fun invalidateVideoSource() {
        if (!isStreaming) {
            activeVideoUri = null
            prepared = false
        }
    }

    // ------------------------------------------------------------------
    // In-stream controls
    // ------------------------------------------------------------------

    fun switchCamera(): Boolean = try {
        cameraSource?.switchCamera()
        cameraSource != null && mode == Mode.CAMERA
    } catch (t: Throwable) {
        false
    }

    fun toggleLantern(): Boolean? {
        val cam = cameraSource ?: return null
        if (mode != Mode.CAMERA) return null
        return try {
            if (cam.isLanternEnabled()) {
                cam.disableLantern(); false
            } else {
                cam.enableLantern(); true
            }
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Toggle the microphone line.
     * VIDEO mode: mic joins/leaves the mix — video audio is untouched.
     * CAMERA mode: classic mic mute/unmute.
     * Returns the new "mic on" state.
     */
    fun setMicrophoneEnabled(enabled: Boolean): Boolean {
        micEnabled = enabled
        applyMixerState()
        return micEnabled
    }

    fun setVideoAudioOn(enabled: Boolean) {
        videoAudioEnabled = enabled
        applyMixerState()
    }

    fun setVolumes(video: Float, mic: Float) {
        videoVolume = video.coerceIn(0f, 1f)
        micVolume = mic.coerceIn(0f, 1f)
        applyMixerState()
    }

    private fun applyMixerState() {
        val mixed = mixedAudioSource
        if (mixed != null) {
            mixed.videoVolume = videoVolume
            mixed.micVolume = micVolume
            mixed.setVideoAudioEnabled(videoAudioEnabled)
            val ok = mixed.setMicEnabled(micEnabled)
            if (!ok) micEnabled = false
        }
        val mic = microphoneSource
        if (mic != null && mode == Mode.CAMERA) {
            try {
                if (micEnabled) mic.unMute() else mic.mute()
            } catch (_: Throwable) {
            }
        }
    }

    // ------------------------------------------------------------------
    // Stats
    // ------------------------------------------------------------------

    private val statsTicker = object : Runnable {
        override fun run() {
            val s = stream
            if (s != null && s.isStreaming && state == StreamState.LIVE) {
                val dropped = try {
                    s.getStreamClient().getDroppedVideoFrames() +
                        s.getStreamClient().getDroppedAudioFrames()
                } catch (t: Throwable) {
                    0L
                }
                val congestion = try {
                    s.getStreamClient().hasCongestion(20f)
                } catch (t: Throwable) {
                    false
                }
                stats = StreamStats(
                    bitrateKbps = lastBitrateKbps,
                    durationSec = (System.currentTimeMillis() - streamStartElapsed) / 1000,
                    droppedFrames = dropped,
                    congestion = congestion,
                    fps = activeConfig?.fps ?: 0,
                    loopCount = loopCount,
                    reconnects = reconnectTotal
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

    private fun sanitize(text: String): String =
        text.replace(Regex("rtmps?://\\S+"), "rtmps://***")
}
