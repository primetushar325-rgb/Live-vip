package com.livevip.app.engine

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.SurfaceView
import com.livevip.app.audio.MixedFileAudioSource
import com.livevip.app.core.CompositionRenderer
import com.livevip.app.core.LiveCompositionState
import com.livevip.app.media.MediaAnalyzer
import com.livevip.app.streaming.CapabilityDetector
import com.livevip.app.streaming.IngestVerifier
import com.livevip.app.streaming.TimelineGuard
import com.livevip.app.streaming.WatchdogCenter
import com.livevip.app.util.NetworkMonitor
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.sources.audio.AudioSource
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.audio.SilenceAudioSource
import com.pedro.encoder.input.sources.video.Camera2Source
import com.pedro.encoder.input.sources.video.VideoFileSource
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.library.rtmp.RtmpStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.math.pow

/**
 * LIVE ENGINE — the single direct RTMP/RTMPS orchestrator.
 *
 *   VIDEO FILE ──► GL COMPOSITOR (LiveCompositionState) ──► H.264
 *                                                         ──► AAC ◄── video+mic mix
 *                                                         ──► RTMP/RTMPS ──► DESTINATION
 *
 * Direct connection only — NO intermediate server. ONE engine, ONE destination.
 *
 * NON-NEGOTIABLE RULES ENFORCED HERE:
 *  - VIDEO LOOP ≠ STREAM RESTART. Looping happens inside the decoder; the
 *    encoder, the GL pipeline and the RTMP session survive ALL boundaries.
 *    Output timestamps stay monotonically increasing (verified live by
 *    [TimelineGuard]).
 *  - PREVIEW == ENCODER OUTPUT. One [CompositionRenderer] filter, first in
 *    the GL chain, renders the same filtered texture to the preview surface
 *    and the encoder surface.
 *  - UI NEVER owns the engine lifetime. The foreground service + this
 *    singleton do. Activity destruction/recreation cannot stop a stream.
 *  - Mic mute silences ONLY the mic line — video audio always continues.
 *  - Controlled reconnect: bounded attempts with increasing delay, never a
 *    duplicate engine/player/RTMP connection.
 *  - Stream keys never appear in logs or error messages ([sanitize]).
 */
object LiveEngine {

    enum class Mode { VIDEO, CAMERA }

    interface Listener {
        fun onLiveStateChanged(state: LiveState, message: String?)
        fun onLiveSnapshot(snapshot: LiveSnapshot)
    }

    private const val TAG = "LiveVipEngine"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()

    /** Serial executor for live source transitions (loop resync, restarts). */
    private val transitionExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    /** The single direct RTMP/RTMPS engine. */
    private var singleStream: RtmpStream? = null
    private val stream: RtmpStream? get() = singleStream

    @Volatile private var appContext: Context? = null
    private val shuttingDown = AtomicBoolean(false)

    @Volatile var state: LiveState = LiveState.OFFLINE
        private set
    @Volatile var snapshot: LiveSnapshot = LiveSnapshot()
        private set
    @Volatile var mode: Mode = Mode.VIDEO
        private set

    // Active sources (valid between prepare and stop)
    private var videoFileSource: VideoFileSource? = null
    private var mixedAudioSource: MixedFileAudioSource? = null
    private var microphoneSource: MicrophoneSource? = null
    private var cameraSource: Camera2Source? = null

    private var activeConfig: EngineConfig? = null
    private var activeVideoUri: Uri? = null
    private var activeVideoInfo: MediaAnalyzer.VideoInfo? = null
    private var reconnectAttempt = 0
    private var reconnectTotal = 0
    private var streamStartElapsed = 0L
    private var lastBitrateKbps = 0L
    @Volatile private var loopCount = 0
    private var debugLogging = false
    private var prepared = false

    // Guards & watchdogs
    private val timelineGuard = TimelineGuard()
    private val watchdogCenter = WatchdogCenter()

    // ---------------- Composition (preview == encoder) ----------------
    private var renderer: CompositionRenderer? = null
    @Volatile private var composition: LiveCompositionState? = null

    /** Identifies the composition the preview is currently prepared for. */
    private var previewKey: String? = null

    // Network watchdog
    private var networkOnline = true
    private val networkMonitor by lazy {
        NetworkMonitor(appContext ?: return@lazy null)
    }

    // User-facing mixer state (persists across prepare cycles)
    @Volatile var micEnabled = false
        private set
    @Volatile var videoAudioEnabled = true
        private set
    @Volatile var videoVolume = 1f
        private set
    @Volatile var micVolume = 1f
        private set

    // Honest diagnostics — real values only, sanitized errors.
    @Volatile private var lastRtmpError: String? = null
    @Volatile private var lastEncoderError: String? = null
    @Volatile private var lastDecoderError: String? = null
    @Volatile private var bytesSentTotal = 0L
    @Volatile private var encoderFellBack = false

    val isStreaming: Boolean
        get() = stream?.isStreaming == true
    val isOnPreview: Boolean
        get() = stream?.isOnPreview == true

    /** True while a session is connecting/connected/sending/streaming/reconnecting. */
    val isBroadcasting: Boolean
        get() = state == LiveState.CONNECTING || state == LiveState.CONNECTED ||
            state == LiveState.SENDING || state == LiveState.STREAMING ||
            state == LiveState.RECONNECTING

    /** True while the destination is a YouTube ingest (honest UI hints). */
    fun isYoutubeDestination(): Boolean =
        activeConfig?.url?.lowercase()?.contains("youtube") == true

    fun lastRtmpError(): String? = lastRtmpError
    fun lastEncoderError(): String? = lastEncoderError
    fun lastDecoderError(): String? = lastDecoderError
    fun encoderFallbackActive(): Boolean = encoderFellBack

    // ------------------------------------------------------------------
    // Ingest verification (socket ≠ STREAMING)
    // ------------------------------------------------------------------

    private val ingestSamples = mutableListOf<IngestVerifier.Sample>()
    @Volatile private var publishStartedAt = 0L

    // ------------------------------------------------------------------
    // Engine lifecycle
    // ------------------------------------------------------------------

    @Synchronized
    private fun engine(context: Context): RtmpStream {
        val existing = singleStream
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
        singleStream = s
        return s
    }

    fun setMode(newMode: Mode) {
        if (mode == newMode) return
        if (isBroadcasting) return // locked while live
        mode = newMode
        prepared = false
    }

    // ------------------------------------------------------------------
    // Connection callback — the real state machine
    // ------------------------------------------------------------------

    private val connectChecker = object : ConnectChecker {
        override fun onConnectionStarted(url: String) {
            setState(LiveState.CONNECTING, "Connecting to server…")
        }

        override fun onConnectionSuccess() {
            // Socket + RTMP handshake OK — CONNECTED, nothing more yet.
            reconnectAttempt = 0
            streamStartElapsed = System.currentTimeMillis()
            lastRtmpError = null
            synchronized(ingestSamples) { ingestSamples.clear() }
            publishStartedAt = System.currentTimeMillis()
            setState(LiveState.CONNECTED, "Connected — preparing media…")
            startStatsTicker()
        }

        override fun onConnectionFailed(reason: String) {
            lastRtmpError = sanitize(reason)
            if (debugLogging) Log.w(TAG, "Connection failed: ${sanitize(reason)}")
            val cfg = activeConfig
            val s = singleStream ?: return
            if (cfg != null && cfg.autoReconnect &&
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
                        LiveState.RECONNECTING,
                        "Reconnecting… attempt $reconnectAttempt of ${cfg.maxReconnectAttempts}"
                    )
                    return
                }
            }
            mainHandler.post {
                internalStop(
                    LiveState.ERROR,
                    lastRtmpError ?: "Stream disconnected"
                )
            }
        }

        override fun onDisconnect() {
            if (state != LiveState.RECONNECTING && state != LiveState.ERROR) {
                setState(LiveState.OFFLINE, null)
            }
        }

        override fun onAuthError() {
            lastRtmpError = "Authentication failed — check your stream key"
            mainHandler.post {
                internalStop(LiveState.ERROR, lastRtmpError)
            }
        }

        override fun onAuthSuccess() { /* no-op */ }

        override fun onNewBitrate(bitrate: Long) {
            lastBitrateKbps = bitrate / 1000
            // Library-measured socket throughput (1 Hz): integrate → bytes sent.
            bytesSentTotal += bitrate / 8
        }
    }

    // ------------------------------------------------------------------
    // PREVIEW — offline, real, surface-created-gated
    // ------------------------------------------------------------------

    /**
     * Prepare the engine at the OUTPUT resolution and bind the preview
     * surface. Works fully OFFLINE: no URL, no key, no RTMP — the decoder,
     * GL compositor and surface alone produce the visible preview.
     *
     * RootEncoder binds holder.surface ONE-SHOT: it throws on an invalid
     * surface and throws while isOnPreview — the guards below make every
     * failure honest instead of a silent black screen.
     */
    fun startPreview(
        context: Context,
        view: SurfaceView,
        videoUri: Uri?,
        config: EngineConfig,
        state: LiveCompositionState
    ): String? {
        return try {
            val s = engine(context)
            if (s.isStreaming) return null // live preview already shows the composition

            if (!view.holder.surface.isValid) {
                return "Preview surface not ready yet"
            }

            // Same source + same output + fps ⇒ already prepared. The
            // surface may simply have been recreated (resume/resize):
            // rebind WITHOUT re-preparing the encoder.
            val key = "${videoUri}|${config.videoWidth}x${config.videoHeight}|${config.fps}"
            if (key == previewKey) {
                try {
                    if (s.isOnPreview) s.stopPreview()
                    s.startPreview(view)
                    return null
                } catch (_: Throwable) {
                    // fall through: full re-prepare heals any stale state
                }
            }
            try {
                if (s.isOnPreview) s.stopPreview()
            } catch (_: Throwable) {
            }
            prepared = false
            val error = configureSources(context, videoUri, previewOnly = true)
            if (error != null) return error
            val error2 = prepareEncoders(context, config)
            if (error2 != null) return error2
            previewKey = key
            applyCompositionInternal(state)
            s.startPreview(view)
            null
        } catch (t: Throwable) {
            if (debugLogging) Log.e(TAG, "preview failed", t)
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

    /**
     * Re-bind the live preview to a (re)created activity surface while a
     * stream runs. Encoder, RTMP session and timestamps are untouched.
     */
    fun rebindLivePreview(view: SurfaceView) {
        val s = stream ?: return
        if (!s.isStreaming) return
        try {
            if (!view.holder.surface.isValid) return
            if (s.isOnPreview) s.stopPreview()
            s.startPreview(view)
        } catch (t: Throwable) {
            if (debugLogging) Log.w(TAG, "live preview rebind failed: ${t.message}")
        }
    }

    // ------------------------------------------------------------------
    // COMPOSITION — one state for preview AND encoder
    // ------------------------------------------------------------------

    /** Install/refresh the composition. Thread-safe quad swap. */
    private fun applyCompositionInternal(state: LiveCompositionState) {
        val gl = try {
            stream?.getGlInterface()
        } catch (_: Throwable) {
            null
        } ?: return
        composition = state
        var render = renderer
        if (render == null) {
            render = CompositionRenderer()
            renderer = render
            try {
                gl.addFilter(0, render)
            } catch (_: Throwable) {
            }
        }
        render.update(state)
    }

    /**
     * Live update from the preview gestures (drag/pinch/double-tap reset)
     * and format switches. No encoder restart, no RTMP reconnect, no
     * timestamp change — the SAME state drives preview and encoder.
     */
    fun updateComposition(state: LiveCompositionState) {
        composition = state
        renderer?.update(state)
    }

    fun currentComposition(): LiveCompositionState? = composition

    /**
     * HONEST PRE-FLIGHT: true when the video decoder is actively producing
     * frames. START LIVE must never begin an RTMP session on top of a
     * dead/black preview.
     */
    fun decoderFlowing(): Boolean {
        if (mode != Mode.VIDEO) return true
        val src = videoFileSource ?: return false
        return try {
            val t1 = src.getTime()
            Thread.sleep(250)
            val t2 = src.getTime()
            t1 >= 0 && t2 != t1
        } catch (_: Throwable) {
            false
        }
    }

    // ------------------------------------------------------------------
    // Source configuration
    // ------------------------------------------------------------------

    private fun configureSources(
        context: Context,
        videoUri: Uri?,
        previewOnly: Boolean
    ): String? {
        val s = stream ?: return "Engine not ready"
        return when (mode) {
            Mode.VIDEO -> {
                val uri = videoUri ?: return "Select a video first"
                val info = MediaAnalyzer.analyze(context, uri) ?: run {
                    lastDecoderError = "Unsupported video file"
                    return "This video format isn't supported on this device. Try an MP4 (H.264/AAC) file."
                }
                try {
                    if (activeVideoUri != uri || videoFileSource == null) {
                        val vSource = VideoFileSource(context.applicationContext, uri, true) { isLoop ->
                            if (isLoop) {
                                // Full loop-boundary handling on the main
                                // thread: counter, timeline mark, audio resync.
                                mainHandler.post { onVideoLoop() }
                            }
                        }
                        s.changeVideoSource(vSource)
                        videoFileSource = vSource

                        val aSource: AudioSource = if (info.hasAudio) {
                            MixedFileAudioSource(context.applicationContext, uri, loopMode = true)
                        } else {
                            SilenceAudioSource()
                        }
                        s.changeAudioSource(aSource)
                        mixedAudioSource = aSource as? MixedFileAudioSource
                        activeVideoUri = uri
                        activeVideoInfo = info
                        lastDecoderError = null
                        prepared = false
                    }
                    null
                } catch (t: Throwable) {
                    lastDecoderError = t.message ?: "unsupported format"
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

    /** Prepare H.264 + AAC encoders ONCE per session. Null on success. */
    private fun prepareEncoders(context: Context, config: EngineConfig): String? {
        val s = stream ?: return "Engine not ready"
        try {
            if (s.isOnPreview) s.stopPreview()

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
                lastEncoderError = "Audio setup failed: ${t.message ?: "unsupported audio configuration"}"
                return lastEncoderError
            }
            if (!audioOk) {
                lastEncoderError = "Audio encoder unavailable on this device"
                return lastEncoderError
            }

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
                // Honest fallback (visible in diagnostics, never silent):
                videoOk = try {
                    s.prepareVideo(854, 480, 1200 * 1024, 30, 2, rotation)
                } catch (t: Throwable) {
                    false
                }
                if (videoOk) {
                    encoderFellBack = true
                    lastEncoderError = "Encoder fell back to 854×480 — " +
                        "${config.videoWidth}×${config.videoHeight} is not supported on this device"
                }
            }
            if (!videoOk) {
                lastEncoderError = "Video encoder unavailable — no compatible H.264 encoder"
                return lastEncoderError
            }

            prepared = true
            return null
        } catch (t: Throwable) {
            if (debugLogging) Log.e(TAG, "prepareEncoders failed", t)
            lastEncoderError = "Encoder initialization failed: ${t.message ?: "unknown error"}"
            return lastEncoderError
        }
    }

    // ------------------------------------------------------------------
    // START / STOP — direct RTMP/RTMPS
    // ------------------------------------------------------------------

    fun startStream(
        context: Context,
        config: EngineConfig,
        videoUri: Uri?,
        previewView: SurfaceView?,
        state: LiveCompositionState
    ): String? {
        val s = engine(context)
        if (s.isStreaming) return "Already streaming"
        if (!config.isValidUrl()) {
            return "Invalid RTMP URL — it must start with rtmp:// or rtmps://"
        }
        if (mode == Mode.VIDEO && videoUri == null) return "Select a video first"

        debugLogging = com.livevip.app.store.SettingsStore.get(context).debugDiagnostics
        activeConfig = config
        reconnectAttempt = 0
        reconnectTotal = 0
        loopCount = 0
        lastRtmpError = null
        lastEncoderError = null
        lastDecoderError = null
        bytesSentTotal = 0
        encoderFellBack = false
        lastBitrateKbps = 0

        setState(LiveState.CONNECTING, "Preparing ${if (mode == Mode.VIDEO) "video" else "camera"}…")
        val sourceError = configureSources(context, videoUri, previewOnly = false)
        if (sourceError != null) {
            setState(LiveState.ERROR, null)
            return sourceError
        }
        if (mode == Mode.VIDEO) {
            val info = activeVideoInfo
            if (info != null && info.videoTrackCount > 1) {
                setState(LiveState.ERROR, null)
                return "Video has ${info.videoTrackCount} video tracks — pick a single-track file"
            }
        }

        setState(LiveState.CONNECTING, "Initializing encoder…")
        val prepError = prepareEncoders(context, config)
        if (prepError != null) {
            setState(LiveState.ERROR, null)
            return prepError
        }

        return try {
            try {
                s.getStreamClient().setReTries(config.maxReconnectAttempts)
            } catch (_: Throwable) {
            }
            setState(LiveState.CONNECTING, "Connecting to server…")
            s.startStream(config.fullUrl())
            if (previewView != null) {
                try {
                    if (!s.isOnPreview) s.startPreview(previewView)
                } catch (_: Throwable) {
                }
            }
            applyMixerState()

            // Video mode: keep frames flowing across decoder gaps at the
            // configured fps — no viewer-visible freeze, continuous timeline.
            if (mode == Mode.VIDEO) {
                try {
                    stream?.getGlInterface()?.setForceRender(true, config.fps)
                } catch (_: Throwable) {
                }
            }

            // THE composition — one pipeline for preview + encoder.
            applyCompositionInternal(state)
            beginHealthMonitoring(config.fps, config.sampleRate)
            null
        } catch (t: Throwable) {
            if (debugLogging) Log.e(TAG, "startStream failed", t)
            lastRtmpError = "Could not start the stream: ${t.message ?: "unknown error"}"
            internalStop(LiveState.ERROR, null)
            lastRtmpError
        }
    }

    /** User-requested stop — the ONLY path that ends a stream. */
    fun stopStream() {
        setState(LiveState.STOPPED, null)
        mainHandler.post { internalStop(LiveState.OFFLINE, null) }
    }

    private fun internalStop(finalState: LiveState, message: String?) {
        stopStatsTicker()

        val s = singleStream
        try {
            if (s != null && s.isStreaming) s.stopStream()
        } catch (t: Throwable) {
            if (debugLogging) Log.e(TAG, "stopStream failed", t)
        }
        try {
            if (s?.isOnPreview == true) s.stopPreview()
        } catch (t: Throwable) {
            if (debugLogging) Log.w(TAG, "stopPreview failed: ${t.message}")
        }
        try {
            stream?.getGlInterface()?.setForceRender(false, 5)
        } catch (_: Throwable) {
        }

        timelineGuard.stop()
        watchdogCenter.reset()

        activeConfig = null
        reconnectAttempt = 0
        lastBitrateKbps = 0
        snapshot = LiveSnapshot()
        prepared = false
        setState(finalState, message)
        notifySnapshot()
    }

    /** Full release (app exit). */
    fun release() {
        internalStop(LiveState.OFFLINE, null)
        try {
            singleStream?.stopPreview()
        } catch (_: Throwable) {
        }
        try {
            singleStream?.release()
        } catch (_: Throwable) {
        }
        singleStream = null
        videoFileSource = null
        mixedAudioSource = null
        microphoneSource = null
        cameraSource = null
        activeVideoUri = null
        activeVideoInfo = null
        appContext = null
        transitionExecutor.shutdownNow()
    }

    fun invalidatePreparation() {
        if (!isStreaming) prepared = false
    }

    // ------------------------------------------------------------------
    // VIDEO LOOP boundary — the critical path
    // ------------------------------------------------------------------

    /**
     * VIDEO LOOP BOUNDARY — the decoder internally restarted the file
     * (zero-cost seek to 0). The encoder, muxer and RTMP/RTMPS connection
     * are NEVER touched; the output timeline continues monotonically
     * (Loop 1: 0→38:02, Loop 2: 38:02→1:16:04, …).
     *
     * Runs on the main thread (posted from the decoder callback).
     */
    private fun onVideoLoop() {
        loopCount++
        timelineGuard.onBoundary()
        // AUDIO LOOP SYNC: if the audio decoder is not back at the file
        // start too (different track lengths), restart it from 0 so audio
        // content stays aligned with video content.
        transitionExecutor.execute {
            try {
                mixedAudioSource?.resyncToLoopStart()
            } catch (t: Throwable) {
                if (debugLogging) Log.w(TAG, "audio loop resync skipped: ${t.message}")
            }
        }
        notifySnapshotSoon()
    }

    // ------------------------------------------------------------------
    // In-stream controls
    // ------------------------------------------------------------------

    /**
     * Toggle the microphone line.
     * VIDEO mode: mic joins/leaves the mix — video audio is untouched.
     * CAMERA mode: classic mic mute/unmute.
     */
    fun setMicrophoneEnabled(enabled: Boolean): Boolean {
        micEnabled = enabled
        applyMixerState()
        return micEnabled
    }

    fun setVideoAudioEnabled(enabled: Boolean) {
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

    /** Audio meter values for the UI (0..1), or null when not in video mode. */
    fun audioLevels(): Pair<Float, Float>? = mixedAudioSource?.let {
        it.videoLevel to it.micLevel
    }

    // ------------------------------------------------------------------
    // Health monitoring: stats + ingest verification + watchdogs
    // ------------------------------------------------------------------

    private fun beginHealthMonitoring(fps: Int, sampleRate: Int) {
        streamStartElapsed = System.currentTimeMillis()
        timelineGuard.start(0)
        timelineGuard.configurePacing(fps, sampleRate)
        watchdogCenter.reset()
        watchdogCenter.probes = WatchdogCenter.Probes(
            isLive = { isStreaming && state == LiveState.STREAMING },
            decoderTimeSec = {
                if (mode == Mode.VIDEO) {
                    try {
                        videoFileSource?.getTime()
                    } catch (_: Throwable) {
                        null
                    }
                } else null
            },
            videoFramesSent = { totalSentVideoFrames() },
            audioFramesSent = { totalSentAudioFrames() },
            networkOnline = { networkOnline },
            availableMemoryFraction = {
                val rt = Runtime.getRuntime()
                val used = rt.totalMemory() - rt.freeMemory()
                1f - (used.toFloat() / rt.maxMemory().toFloat())
            },
            thermalStatus = {
                try {
                    val pm = appContext?.getSystemService(Context.POWER_SERVICE) as? PowerManager
                    if (Build.VERSION.SDK_INT >= 29) pm?.getCurrentThermalStatus() ?: 0 else 0
                } catch (_: Throwable) {
                    0
                }
            }
        )
        registerNetworkWatchdog()
        startStatsTicker()
    }

    private fun totalSentVideoFrames(): Long = try {
        singleStream?.getStreamClient()?.getSentVideoFrames() ?: 0L
    } catch (_: Throwable) {
        0L
    }

    private fun totalSentAudioFrames(): Long = try {
        singleStream?.getStreamClient()?.getSentAudioFrames() ?: 0L
    } catch (_: Throwable) {
        0L
    }

    private var networkCallbackRegistered = false
    private fun registerNetworkWatchdog() {
        val monitor = networkMonitor ?: return
        monitor.onLost = { networkOnline = false }
        monitor.onAvailable = { networkOnline = true }
        try {
            monitor.register()
            networkCallbackRegistered = true
        } catch (_: Throwable) {
        }
    }

    private val statsTicker = object : Runnable {
        override fun run() {
            val active = isBroadcasting
            if (active && stream?.isStreaming == true) {
                tickStats()
            }
            if (active) {
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

    private fun notifySnapshotSoon() {
        mainHandler.post { notifySnapshot() }
    }

    private fun tickStats() {
        // CONNECTED → SENDING: only once REAL media packets are flowing.
        val videoFrames = totalSentVideoFrames()
        if (state == LiveState.CONNECTED && videoFrames > 0) {
            setState(
                LiveState.SENDING,
                if (isYoutubeDestination()) "Sending to YouTube…" else "Sending…"
            )
        }

        // SENDING → STREAMING: only after sustained verified media flow.
        if (state == LiveState.SENDING) {
            val sample = IngestVerifier.Sample(
                elapsedSec = (System.currentTimeMillis() - publishStartedAt) / 1000f,
                videoFrames = videoFrames,
                audioFrames = totalSentAudioFrames(),
                audioEnabled = true // the AAC encoder always runs (audio or silence clock)
            )
            val verdict = synchronized(ingestSamples) {
                ingestSamples += sample
                IngestVerifier.evaluate(ingestSamples.toList())
            }
            when (verdict) {
                IngestVerifier.Result.Verified -> {
                    setState(LiveState.STREAMING, "Streaming — media verified")
                }
                is IngestVerifier.Result.Failed -> {
                    lastRtmpError = verdict.reason
                    mainHandler.post {
                        internalStop(LiveState.ERROR, verdict.reason)
                    }
                    return
                }
                IngestVerifier.Result.Pending -> Unit
            }
        }

        val s = singleStream
        val dropped = try {
            (s?.getStreamClient()?.getDroppedVideoFrames() ?: 0) +
                (s?.getStreamClient()?.getDroppedAudioFrames() ?: 0)
        } catch (_: Throwable) {
            0L
        }
        val congestion = try {
            s?.getStreamClient()?.hasCongestion(20f) ?: false
        } catch (_: Throwable) {
            false
        }
        val guardSnapshot = timelineGuard.snapshot()
        snapshot = LiveSnapshot(
            state = state,
            durationSec = (System.currentTimeMillis() - streamStartElapsed) / 1000,
            bitrateKbps = lastBitrateKbps,
            droppedFrames = dropped,
            congestion = congestion,
            fps = activeConfig?.fps ?: 0,
            loopCount = loopCount,
            reconnects = reconnectTotal,
            sentVideoFrames = videoFrames,
            sentAudioFrames = totalSentAudioFrames(),
            bytesSent = bytesSentTotal,
            avSyncMs = if (streamStartElapsed > 0) guardSnapshot.avDriftMs else null,
            lastError = lastRtmpError,
            components = Components()
        )

        // Timeline continuity validation (real encoded-frame timeline).
        val fps = activeConfig?.fps ?: 30
        val videoTimelineMs = videoFrames * 1000 / fps.coerceAtLeast(1)
        timelineGuard.onOutputProgress(videoTimelineMs)
        timelineGuard.onFrameCounters(videoFrames, totalSentAudioFrames())

        // Watchdogs with targeted recovery.
        val alerts = watchdogCenter.tick()
        alerts.forEach { alert ->
            if (debugLogging) Log.w(TAG, "watchdog[${alert.component}]: ${alert.message}")
            when (alert.action) {
                WatchdogCenter.Action.RESTART_VIDEO_SOURCE -> restartVideoSource()
                WatchdogCenter.Action.RESTART_AUDIO_SOURCE -> restartAudioSource()
                WatchdogCenter.Action.REQUEST_KEYFRAME -> {
                    try {
                        stream?.requestKeyframe()
                    } catch (_: Throwable) {
                    }
                }
                else -> Unit
            }
        }

        val videoFramesNow = totalSentVideoFrames()
        val audioFramesNow = totalSentAudioFrames()
        val decoderMoving = try {
            (videoFileSource?.getTime() ?: -1.0) >= 0 &&
                (videoFileSource?.getTime() ?: -1.0) > lastDecoderTimeSnapshot - 0.001
        } catch (_: Throwable) {
            true // camera mode / no file source
        }
        try {
            lastDecoderTimeSnapshot = videoFileSource?.getTime() ?: 0.0
        } catch (_: Throwable) {
        }
        val encoderOk = videoFramesNow > lastVideoFramesSnapshot
        val muxerOk = videoFramesNow > lastVideoFramesSnapshot ||
            audioFramesNow > lastAudioFramesSnapshot
        lastVideoFramesSnapshot = videoFramesNow
        lastAudioFramesSnapshot = audioFramesNow
        if (!decoderMoving && mode == Mode.VIDEO) {
            lastDecoderError = "Decoder stalled — recovery attempted"
        }
        val socketUp = try {
            singleStream?.isStreaming == true
        } catch (_: Throwable) {
            false
        }
        snapshot = snapshot.copy(
            components = Components(
                decoder = when {
                    mode == Mode.CAMERA -> "READY"
                    decoderMoving -> "READY"
                    else -> "STALLED"
                },
                encoder = if (encoderOk || !isBroadcasting) "READY" else "STALLED",
                muxer = if (muxerOk || !isBroadcasting) "READY" else "STALLED",
                rtmp = if (socketUp) "CONNECTED" else "DISCONNECTED",
                ingest = when (state) {
                    LiveState.STREAMING -> if (videoFramesNow > 0) "VERIFIED" else "—"
                    LiveState.SENDING -> "VERIFYING"
                    else -> "—"
                },
                preview = when {
                    !isOnPreview -> "NO SURFACE"
                    mode == Mode.CAMERA -> "FRAMES RECEIVED"
                    decoderMoving -> "FRAMES RECEIVED"
                    else -> "NO FRAMES"
                }
            )
        )

        notifySnapshot()
    }

    // Component-health snapshots (diagnostics).
    @Volatile private var lastDecoderTimeSnapshot = 0.0
    @Volatile private var lastVideoFramesSnapshot = 0L
    @Volatile private var lastAudioFramesSnapshot = 0L

    /** TARGETED recovery: restart only the video source (decoder). */
    private fun restartVideoSource() {
        val context = appContext ?: return
        val uri = activeVideoUri ?: return
        transitionExecutor.execute {
            try {
                // replaceFile creates a FRESH decoder whose loop flag defaults
                // to false — re-apply looping or the video would EOS straight
                // into a stop after recovery.
                videoFileSource?.replaceFile(context, uri)
                videoFileSource?.setLoopMode(true)
                mixedAudioSource?.replaceFile(
                    context, uri,
                    activeConfig?.sampleRate ?: 44100,
                    activeConfig?.stereo ?: true
                )
                mixedAudioSource?.setLoopMode(true)
            } catch (t: Throwable) {
                lastDecoderError = "video source restart failed: ${t.message}"
                if (debugLogging) Log.e(TAG, "video source restart failed", t)
            }
        }
    }

    /** TARGETED recovery: rebuild only the audio source. */
    private fun restartAudioSource() {
        val context = appContext ?: return
        val s = stream ?: return
        try {
            if (mode == Mode.VIDEO) {
                val uri = activeVideoUri ?: return
                val fresh = MixedFileAudioSource(context, uri, loopMode = true)
                s.changeAudioSource(fresh) // encoder + RTMP untouched
                mixedAudioSource = fresh
                applyMixerState()
            } else {
                val fresh = MicrophoneSource()
                s.changeAudioSource(fresh)
                microphoneSource = fresh
                applyMixerState()
            }
        } catch (t: Throwable) {
            if (debugLogging) Log.e(TAG, "audio source restart failed", t)
        }
    }

    // ------------------------------------------------------------------
    // Listener plumbing
    // ------------------------------------------------------------------

    fun addListener(listener: Listener) {
        listeners.addIfAbsent(listener)
        listener.onLiveStateChanged(state, null)
        listener.onLiveSnapshot(snapshot)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    private fun setState(newState: LiveState, message: String?) {
        state = newState
        mainHandler.post {
            listeners.forEach { it.onLiveStateChanged(newState, message) }
        }
    }

    private fun notifySnapshot() {
        mainHandler.post {
            listeners.forEach { it.onLiveSnapshot(snapshot) }
        }
    }

    /** Credentials never appear in any message. */
    private fun sanitize(text: String): String =
        text.replace(Regex("rtmps?://\\S+"), "rtmps://***")
}
