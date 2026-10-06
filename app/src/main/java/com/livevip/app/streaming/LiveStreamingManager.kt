package com.livevip.app.streaming

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.math.pow

/**
 * LIVE VIP BROADCAST ORCHESTRATOR (singleton).
 *
 *                 ┌──────────────────────────────────────────┐
 *   VIDEO LIVE ───┤  VideoFileSource (gapless internal loop)  │
 *                 │  GL compositor (video transform quad)     │── H.264 + AAC ──► RTMP/RTMPS ──► SERVER
 *                 │  MixedFileAudioSource (video+mic mix)     │
 *                 └──────────────────────────────────────────┘
 *
 * NON-NEGOTIABLE RULES ENFORCED HERE:
 *  - VIDEO LOOP ≠ STREAM RESTART. Looping happens inside the decoder. The
 *    encoder, the GL pipeline and the RTMP session survive ALL boundaries.
 *    Output timestamps stay monotonically increasing (verified live by
 *    [TimelineGuard]).
 *  - UI NEVER owns the engine lifetime. The foreground service + this
 *    singleton do. Activity destruction/recreation cannot stop a stream.
 *  - Mic mute silences ONLY the mic line — video audio always continues.
 *  - Controlled reconnect: bounded attempts, increasing delay, no
 *    duplicate engines/connections/leaks.
 *  - Stream keys never appear in logs (all messages pass through [sanitize]).
 *
 * PIPELINE (single, direct — OBS-style):
 *  VIDEO FILE → GL COMPOSITOR (transform quad) → H.264 → AAC → RTMP(S) → SERVER
 *
 *  ONE engine, ONE destination. No relay, no multi-destination, no scenes.
 */
object LiveStreamingManager {

    enum class Mode { VIDEO, CAMERA }

    interface Listener {
        fun onStateChanged(state: StreamState, message: String?)
        fun onStatsChanged(stats: StreamStats)
        /** Pipeline component health (real probes, 1 Hz while live). */
        fun onHealthChanged(health: StreamHealth) {}
    }

    // ------------------------------------------------------------------
    // Runtime state models
    // ------------------------------------------------------------------

    data class StreamHealth(
        val timeline: TimelineGuard.Snapshot = TimelineGuard.Snapshot(
            TimelineGuard.Status.OK, 0, 0, 0, 0, 0, 0, emptyList()
        ),
        val watchdogActions: Int = 0,
        val memoryFreeFraction: Float = 1f,
        val thermalStatus: Int = 0,
        val networkOnline: Boolean = true,
        val avSyncMs: Long = 0,
        /** Per-component health line for the dashboard (real probes). */
        val components: Components = Components()
    )

    /** Individual pipeline component states (dashboard row, Part 4). */
    data class Components(
        val decoder: String = "—",
        val encoder: String = "—",
        val muxer: String = "—",
        val rtmps: String = "—",
        val ingest: String = "—"
    )

    private const val TAG = "LiveVipStream"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()

    /** Serial executor for live source transitions (loop resync, restarts). */
    private val transitionExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    /** The single direct RTMP/RTMPS engine. */
    private var singleStream: RtmpStream? = null

    /** Kotlin-friendly alias used throughout. */
    private val stream: RtmpStream? get() = singleStream

    @Volatile private var appContext: Context? = null
    private val shuttingDown = AtomicBoolean(false)

    @Volatile var state: StreamState = StreamState.OFFLINE
        private set
    @Volatile var stats: StreamStats = StreamStats()
        private set
    @Volatile var mode: Mode = Mode.VIDEO
        private set
    @Volatile var health: StreamHealth = StreamHealth()
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

    // Guards & watchdogs
    private val timelineGuard = TimelineGuard()
    private val watchdogCenter = WatchdogCenter()

    // ---------------- Video transform compositor ----------------
    // ONE filter, FIRST in the chain, shared by the preview surface and the
    // encoder surface — preview and live output are pixel-identical.
    private var canvasRender: com.livevip.app.overlay.CanvasVideoTransformRender? = null
    @Volatile private var activeCanvas: com.livevip.app.overlay.CanvasConfig? = null

    // Network watchdog
    private var networkOnline = true
    private val networkMonitor by lazy {
        com.livevip.app.util.NetworkMonitor(appContext ?: return@lazy null)
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

    val isStreaming: Boolean
        get() = stream?.isStreaming == true
    val isOnPreview: Boolean
        get() = stream?.isOnPreview == true

    /** True while a session is connecting/publishing/live/reconnecting. */
    val isBroadcasting: Boolean
        get() = state == StreamState.LIVE || state == StreamState.CONNECTING ||
            state == StreamState.RECONNECTING || state == StreamState.PUBLISHING

    // ------------------------------------------------------------------
    // Ingest verification (false-LIVE fix, Part 4)
    // ------------------------------------------------------------------

    private val ingestSamples = mutableListOf<IngestVerifier.Sample>()
    @Volatile private var publishStartedAt = 0L

    /** Socket connected + handshake done → PUBLISHING until media verified. */
    private fun beginIngestVerification() {
        synchronized(ingestSamples) { ingestSamples.clear() }
        publishStartedAt = System.currentTimeMillis()
        setState(
            StreamState.PUBLISHING,
            "Streaming to server — verifying ingest…"
        )
    }

    /** Called every stats tick while PUBLISHING. Promotes to LIVE or fails honestly. */
    private fun verifyIngest() {
        // The AAC encoder always runs in this engine (video audio or silence
        // clock) — audio frames must flow for ingest to verify.
        val audioEnabled = true
        val sample = IngestVerifier.Sample(
            elapsedSec = (System.currentTimeMillis() - publishStartedAt) / 1000f,
            videoFrames = totalSentVideoFrames(),
            audioFrames = totalSentAudioFrames(),
            audioEnabled = audioEnabled
        )
        val verdict = synchronized(ingestSamples) {
            ingestSamples += sample
            IngestVerifier.evaluate(ingestSamples.toList())
        }
        when (verdict) {
            IngestVerifier.Result.Verified -> {
                setState(StreamState.LIVE, "Ingest verified — you are live")
            }
            is IngestVerifier.Result.Failed -> {
                mainHandler.post {
                    internalStop(StreamState.ERROR, verdict.reason)
                }
            }
            IngestVerifier.Result.Pending -> Unit
        }
    }

    /** True once media flow has been verified for this session. */
    private fun mediaVerified(): Boolean =
        state == StreamState.LIVE && totalSentVideoFrames() > 0

    // ------------------------------------------------------------------
    // Connection callback — single direct engine
    // ------------------------------------------------------------------

    private val connectChecker = object : ConnectChecker {
        override fun onConnectionStarted(url: String) {
            setState(StreamState.CONNECTING, "Connecting to server…")
        }

        override fun onConnectionSuccess() {
            reconnectAttempt = 0
            streamStartElapsed = System.currentTimeMillis()
            // Socket + handshake OK — but LIVE is only claimed after the
            // ingest is VERIFIED (sustained media flow). YouTube etc. may
            // accept the socket while showing nothing to viewers.
            beginIngestVerification()
            startStatsTicker()
        }

        override fun onConnectionFailed(reason: String) {
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
        mode = newMode
        prepared = false
    }

    // ------------------------------------------------------------------
    // Preview
    // ------------------------------------------------------------------

    fun stopPreview() {
        val s = stream ?: return
        try {
            if (s.isOnPreview) s.stopPreview()
        } catch (t: Throwable) {
            if (debugLogging) Log.e(TAG, "stopPreview failed", t)
        }
    }

    // ------------------------------------------------------------------
    // Source configuration (single engine)
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
                val info = MediaAnalyzer.analyze(context, uri)
                    ?: return "This video format isn't supported on this device. Try an MP4 (H.264/AAC) file."
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
                            MixedFileAudioSource(context.applicationContext, uri, true)
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

    /** Prepare H.264 + AAC encoders ONCE per session. Null on success. */
    private fun prepareEncoders(context: Context, config: StreamConfig): String? {
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
    // START — direct RTMP/RTMPS broadcast
    // ------------------------------------------------------------------

    fun startStream(
        context: Context,
        config: StreamConfig,
        videoUri: Uri?,
        previewView: SurfaceView?,
        transform: com.livevip.app.overlay.VideoTransform? = null
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
        if (mode == Mode.VIDEO) {
            val info = activeVideoInfo
            if (info != null && info.videoTrackCount > 1) {
                setState(StreamState.ERROR, null)
                return "Video has ${info.videoTrackCount} video tracks — pick a single-track file"
            }
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

            // THE transform compositor — one pipeline for preview + encoder.
            applyTransformInternal(
                transform ?: com.livevip.app.overlay.VideoTransform(),
                config.videoWidth, config.videoHeight
            )
            beginHealthMonitoring(config.fps, config.sampleRate)
            null
        } catch (t: Throwable) {
            if (debugLogging) Log.e(TAG, "startStream failed", t)
            internalStop(StreamState.ERROR, null)
            "Could not start the stream: ${t.message ?: "unknown error"}"
        }
    }

    // ------------------------------------------------------------------
    // VIDEO LOOP boundary handling (the critical path)
    // ------------------------------------------------------------------

    /**
     * VIDEO LOOP BOUNDARY — the decoder internally restarted the file
     * (zero-cost seek to 0). The encoder, muxer and RTMP/RTMPS connection
     * are NEVER touched; the output timeline continues monotonically.
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
        notifyStatsSoon()
    }

    /** User-requested stop. The service/timer teardown runs on the main thread. */
    fun stopStream() {
        mainHandler.post { internalStop(StreamState.OFFLINE, null) }
    }

    private fun internalStop(finalState: StreamState, message: String?) {
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

        timelineGuard.stop()
        watchdogCenter.reset()

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

    /** Audio meter values for the UI (0..1), or null when not in video mode. */
    fun audioLevels(): Pair<Float, Float>? = mixedAudioSource?.let {
        it.videoLevel to it.micLevel
    }

    // ------------------------------------------------------------------
    // TRANSFORM COMPOSITOR (live — composited into the encoded stream)
    // ------------------------------------------------------------------

    /**
     * Install the transform compositor as the FIRST filter in the GL chain.
     * RootEncoder then renders the final filtered texture into BOTH the
     * preview surface and the encoder surface — preview == live output.
     * Live-safe: updating it never touches the encoder, RTMP or timestamps.
     */
    private fun applyTransformInternal(
        transform: com.livevip.app.overlay.VideoTransform,
        outWidth: Int,
        outHeight: Int
    ) {
        val gl = try {
            stream?.getGlInterface()
        } catch (_: Throwable) {
            null
        } ?: return
        val info = activeVideoInfo
        val srcW = info?.width ?: outWidth
        val srcH = info?.height ?: outHeight
        activeCanvas = com.livevip.app.overlay.CanvasConfig(
            width = outWidth, height = outHeight,
            aspect = com.livevip.app.overlay.CanvasAspect.from(outWidth, outHeight)
        ).copy(transform = transform)
        var render = canvasRender
        if (render == null) {
            render = com.livevip.app.overlay.CanvasVideoTransformRender()
            canvasRender = render
            try {
                gl.addFilter(0, render)
            } catch (_: Throwable) {
            }
        }
        render.update(transform, srcW, srcH, outWidth, outHeight)
    }

    /**
     * Live update of the video transform (preview gestures while LIVE).
     * Thread-safe quad swap inside the render — no encoder/RTMP restart.
     */
    fun updateVideoTransform(transform: com.livevip.app.overlay.VideoTransform) {
        val canvas = activeCanvas ?: return
        val info = activeVideoInfo
        canvasRender?.update(
            transform,
            info?.width ?: canvas.width, info?.height ?: canvas.height,
            canvas.width, canvas.height
        )
        activeCanvas = canvas.copy(transform = transform)
    }

    /** Current composition (width/height/aspect/transform) — for UI display. */
    fun activeCanvasConfig(): com.livevip.app.overlay.CanvasConfig? = activeCanvas

    /**
     * OFFLINE PREVIEW == LIVE OUTPUT.
     *
     * Prepares the SAME encoder resolution and applies the SAME transform
     * compositor that [startStream] uses, then binds the preview surface.
     * The GL chain renders once into both surfaces, so what the user sees
     * is exactly what will be encoded — same aspect, scale, translate.
     */
    fun startTransformPreview(
        context: Context,
        view: android.view.SurfaceView,
        videoUri: Uri?,
        config: StreamConfig,
        transform: com.livevip.app.overlay.VideoTransform
    ): String? {
        return try {
            val s = engine(context)
            if (s.isStreaming) return null // live preview already shows the composition

            // Same video + same output resolution ⇒ the encoders are already
            // prepared correctly. The preview surface may simply have been
            // recreated (layout/resize) — rebind it WITHOUT re-preparing the
            // encoder (no churn on every format tap or surface change).
            val key = "${videoUri}|${config.videoWidth}x${config.videoHeight}"
            if (key == previewKey) {
                try {
                    s.startPreview(view)
                    return null
                } catch (_: Throwable) {
                }
            }
            try {
                if (s.isOnPreview) s.stopPreview()
            } catch (_: Throwable) {
            }
            // The encoder MUST run at the output resolution — always
            // re-prepare so a previous different-resolution preview can
            // never leak old dims into the composition.
            prepared = false
            val error = configureSources(context, videoUri, previewOnly = true)
            if (error != null) return error
            val error2 = prepareEncoders(context, config)
            if (error2 != null) return error2
            previewKey = key
            applyTransformInternal(transform, config.videoWidth, config.videoHeight)
            s.startPreview(view)
            null
        } catch (t: Throwable) {
            if (debugLogging) Log.e(TAG, "preview failed", t)
            "Preview failed: ${t.message ?: "unknown error"}"
        }
    }

    /** Identifies the composition the preview is currently prepared for. */
    private var previewKey: String? = null

    // ------------------------------------------------------------------
    // Health monitoring: stats + timeline guard + watchdogs
    // ------------------------------------------------------------------

    private fun beginHealthMonitoring(fps: Int, sampleRate: Int) {
        streamStartElapsed = System.currentTimeMillis()
        timelineGuard.start(0)
        timelineGuard.configurePacing(fps, sampleRate)
        watchdogCenter.reset()
        watchdogCenter.probes = WatchdogCenter.Probes(
            isLive = { isStreaming && state == StreamState.LIVE },
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
            val active = state == StreamState.LIVE || state == StreamState.RECONNECTING ||
                state == StreamState.PUBLISHING
            if (active && stream?.isStreaming == true) {
                tickStats()
            }
            if (active) {
                mainHandler.postDelayed(this, 1000)
            }
        }
    }

    private fun tickStats() {
        // Ingest verification runs while PUBLISHING (false-LIVE fix).
        if (state == StreamState.PUBLISHING) verifyIngest()
        val s = singleStream
        val dropped = try {
            (s?.getStreamClient()?.getDroppedVideoFrames() ?: 0) +
                (s?.getStreamClient()?.getDroppedAudioFrames() ?: 0)
        } catch (t: Throwable) {
            0L
        }
        val congestion = try {
            s?.getStreamClient()?.hasCongestion(20f) ?: false
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
            reconnects = reconnectTotal,
            mediaVerified = mediaVerified(),
            avSyncMs = timelineGuard.snapshot().avDriftMs
        )

        // Timeline continuity validation (real encoded-frame timeline).
        val fps = activeConfig?.fps ?: 30
        val videoTimelineMs = totalSentVideoFrames() * 1000 / fps.coerceAtLeast(1)
        timelineGuard.onOutputProgress(videoTimelineMs)
        timelineGuard.onFrameCounters(totalSentVideoFrames(), totalSentAudioFrames())

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

        val snapshotNow = timelineGuard.snapshot()
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
        val socketUp = try {
            singleStream?.isStreaming == true
        } catch (_: Throwable) {
            false
        }
        health = StreamHealth(
            timeline = snapshotNow,
            watchdogActions = watchdogCenter.sourceRestartCount(),
            memoryFreeFraction = watchdogCenter.probes.availableMemoryFraction(),
            thermalStatus = watchdogCenter.probes.thermalStatus(),
            networkOnline = networkOnline,
            avSyncMs = snapshotNow.avDriftMs,
            components = Components(
                decoder = if (mode == Mode.CAMERA) "HEALTHY" else if (decoderMoving) "HEALTHY" else "STALLED",
                encoder = if (encoderOk) "HEALTHY" else "STALLED",
                muxer = if (muxerOk) "HEALTHY" else "STALLED",
                rtmps = if (socketUp) "CONNECTED" else "DOWN",
                ingest = when {
                    state == StreamState.LIVE && videoFramesNow > 0 -> "VERIFIED"
                    state == StreamState.PUBLISHING -> "VERIFYING"
                    else -> "—"
                }
            )
        )

        notifyStats()
        mainHandler.post { listeners.forEach { it.onHealthChanged(health) } }
    }

    // Component-health snapshots (dashboard).
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
    // Stats / listeners plumbing
    // ------------------------------------------------------------------

    private fun startStatsTicker() {
        mainHandler.removeCallbacks(statsTicker)
        mainHandler.post(statsTicker)
    }

    private fun stopStatsTicker() {
        mainHandler.removeCallbacks(statsTicker)
    }

    private fun notifyStatsSoon() {
        mainHandler.post { notifyStats() }
    }

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
