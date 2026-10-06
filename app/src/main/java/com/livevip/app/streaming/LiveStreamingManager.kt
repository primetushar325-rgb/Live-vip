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
import com.livevip.app.data.ProjectRepository
import com.livevip.app.data.SettingsRepository
import com.livevip.app.data.StreamSession
import com.livevip.app.media.MediaAnalyzer
import com.livevip.app.overlay.OverlayConfig
import com.livevip.app.overlay.OverlayFilterFactory
import com.livevip.app.overlay.SceneConfig
import com.livevip.app.relay.RelaySessionClient
import com.livevip.app.relay.Sanitizer
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.sources.audio.AudioSource
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.audio.SilenceAudioSource
import com.pedro.encoder.input.sources.video.Camera2Source
import com.pedro.encoder.input.sources.video.VideoFileSource
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.library.base.StreamBase
import com.pedro.library.multiple.MultiStream
import com.pedro.library.multiple.MultiType
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
 *   CAMERA LIVE ──┤                                          │
 *                 │  ONE encoder pipeline (RootEncoder)      │── H.264 + AAC ──┬─► RTMP/RTMPS A
 *   VIDEO LIVE ───┤  VideoFileSource (gapless loop/playlist) │                 ├─► RTMP/RTMPS B
 *    │            │  MixedFileAudioSource (video+mic mix)    │                 └─► … N
 *    └─ Playlist  │  GL filter chain (STREAM OVERLAYS)        │── or ONE upstream ──► LIVE VIP RELAY ──► fan-out
 *       Engine    └──────────────────────────────────────────┘
 *
 * NON-NEGOTIABLE RULES ENFORCED HERE:
 *  - VIDEO LOOP ≠ STREAM RESTART. Looping happens inside the decoder; playlist
 *    transitions swap the file source. The encoder, the GL pipeline and every
 *    live RTMP session survive ALL boundaries. Output timestamps stay
 *    monotonically increasing (verified live by [TimelineGuard]).
 *  - UI NEVER owns the engine lifetime. The foreground service + this
 *    singleton do. Activity destruction/recreation cannot stop a stream.
 *  - Mic mute silences ONLY the mic line — video audio always continues.
 *  - One destination failing never touches the others (independent
 *    ConnectCheckers with independent bounded-backoff reconnect).
 *  - Stream keys never appear in logs (all messages pass through [sanitize]).
 *
 * PATH SELECTION:
 *  - 1 destination / Smart Relay → the proven single [RtmpStream] engine
 *    (unchanged legacy path — Relay is one upstream, so still single).
 *  - 2+ destinations, DIRECT mode → [MultiStream]: one encoder, N RTMP
 *    clients, per-destination state/reconnect.
 */
object LiveStreamingManager {

    enum class Mode { VIDEO, CAMERA }

    interface Listener {
        fun onStateChanged(state: StreamState, message: String?)
        fun onStatsChanged(stats: StreamStats)
        /** Per-destination live status (multi-destination / relay). */
        fun onDestinationsChanged(statuses: List<DestinationRuntimeStatus>) {}
        /** Watchdog / timeline-guard health updates. */
        fun onHealthChanged(health: StreamHealth) {}
    }

    // ------------------------------------------------------------------
    // Runtime state models
    // ------------------------------------------------------------------

    enum class DestinationState { IDLE, CONNECTING, LIVE, RECONNECTING, FAILED, STOPPED }

    data class DestinationRuntimeStatus(
        val id: Long,
        val name: String,
        val platform: String,
        val state: DestinationState,
        val bitrateKbps: Long,
        val reconnectCount: Int,
        val droppedFrames: Long,
        val congestion: Boolean,
        val lastError: String?
    )

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

    /** Proven single-destination engine (kept from v1 — legacy + relay path). */
    private var singleStream: RtmpStream? = null

    /** Multi-destination engine (DIRECT, 2+ destinations). Null otherwise. */
    private var multiStream: MultiStream? = null

    /** Whatever engine is currently active. */
    private val stream: StreamBase?
        get() = multiStream ?: singleStream

    private var appContext: Context? = null
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

    // Plan / playlist / destinations
    private var activeConfig: StreamConfig? = null
    private var activePlan: BroadcastPlan? = null
    private var playlistEngine: PlaylistEngine<PlaylistMedia>? = null
    private val destinationRuntimes = CopyOnWriteArrayList<DestinationRuntime>()
    private var activeVideoUri: Uri? = null
    private var activeVideoInfo: MediaAnalyzer.VideoInfo? = null
    private var reconnectAttempt = 0
    private var reconnectTotal = 0
    private var streamStartElapsed = 0L
    private var lastBitrateKbps = 0L
    @Volatile private var loopCount = 0
    private var debugLogging = false
    private var prepared = false

    // Session history
    private var currentSessionId: Long = 0
    private var sessionStatus = "COMPLETED"

    // Relay
    private var relaySession: RelaySessionClient.Session? = null
    private var relayPollsEnabled = false

    // Playlist transitions run on their own executor — NEVER on decoder
    // threads (replaceFile must not join the thread that reports EOF).
    private val transitionExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { r -> Thread(r, "livevip-playlist") }

    // Guards & watchdogs
    private val timelineGuard = TimelineGuard()
    private val watchdogCenter = WatchdogCenter()
    private val overlayTicker = OverlayFilterFactory.TextTicker()
    private val activeOverlays = linkedMapOf<Long, OverlayFilterFactory.BuiltOverlay>()

    // ---------------- Live canvas (Part 2) ----------------
    // The canvas render is NOT an overlay: it sits FIRST in the filter chain
    // and is never touched by scene switches or overlay rebuilds.
    private var canvasRender: com.livevip.app.overlay.CanvasVideoTransformRender? = null
    @Volatile private var activeCanvas: com.livevip.app.overlay.CanvasConfig? = null
    /** Dims used to lay out layers when previewing a canvas without a plan. */
    @Volatile private var previewDims: Pair<Int, Int>? = null

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

    /** True when a plan-based broadcast is running. */
    val isBroadcasting: Boolean
        get() = activePlan != null && (
            state == StreamState.LIVE || state == StreamState.CONNECTING ||
                state == StreamState.RECONNECTING || state == StreamState.PUBLISHING
            )

    // ------------------------------------------------------------------
    // Per-destination runtime
    // ------------------------------------------------------------------

    private class DestinationRuntime(val config: DestinationConfig) {
        @Volatile var state: DestinationState = DestinationState.IDLE
        @Volatile var bitrateKbps = 0L
        @Volatile var reconnectAttempts = 0
        @Volatile var reconnects = 0
        @Volatile var droppedFrames = 0L
        @Volatile var congestion = false
        @Volatile var lastError: String? = null
    }

    fun destinationStatuses(): List<DestinationRuntimeStatus> =
        destinationRuntimes.map { it.toStatus() }

    private fun DestinationRuntime.toStatus() = DestinationRuntimeStatus(
        id = config.id,
        name = config.name,
        platform = config.platform.label,
        state = state,
        bitrateKbps = bitrateKbps,
        reconnectCount = reconnects,
        droppedFrames = droppedFrames,
        congestion = congestion,
        lastError = lastError
    )

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
    // Connection callback — SINGLE engine (legacy / relay upstream)
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
    // Connection callback — PER DESTINATION (multi-destination direct)
    // ------------------------------------------------------------------

    private class DestinationConnectChecker(
        private val runtime: DestinationRuntime
    ) : ConnectChecker {

        override fun onConnectionStarted(url: String) {
            runtime.state = DestinationState.CONNECTING
            notifyDestinations()
        }

        override fun onConnectionSuccess() {
            runtime.reconnectAttempts = 0
            runtime.state = DestinationState.LIVE
            runtime.lastError = null
            if (streamStartElapsed == 0L) streamStartElapsed = System.currentTimeMillis()
            if (state != StreamState.LIVE && state != StreamState.PUBLISHING) {
                // Per-socket LIVE; the global engine state waits for verified
                // media flow (ingest verification) before claiming LIVE.
                beginIngestVerification()
                startStatsTicker()
            }
            notifyDestinations()
        }

        override fun onConnectionFailed(reason: String) {
            val plan = activePlan ?: return
            val maxAttempts = plan.legacyReconnectAttempts()
            runtime.lastError = sanitize(reason)
            if (runtime.reconnectAttempts < maxAttempts) {
                runtime.reconnectAttempts++
                runtime.reconnects++
                val delayMs = min(
                    1000L * 2.0.pow(runtime.reconnectAttempts - 1).toLong(),
                    30_000L
                )
                val multi = multiStream
                val index = destinationRuntimes.indexOf(runtime)
                val scheduled = multi != null && index >= 0 && try {
                    multi.getStreamClient(MultiType.RTMP, index)
                        .reTry(delayMs, reason, null)
                } catch (t: Throwable) {
                    false
                }
                if (scheduled) {
                    runtime.state = DestinationState.RECONNECTING
                    notifyDestinations()
                    return
                }
            }
            // This destination is terminal — the OTHERS MUST CONTINUE.
            runtime.state = DestinationState.FAILED
            notifyDestinations()
            checkAllDestinationsTerminal()
        }

        override fun onDisconnect() {
            if (runtime.state == DestinationState.LIVE ||
                runtime.state == DestinationState.CONNECTING
            ) {
                runtime.state = DestinationState.RECONNECTING
                notifyDestinations()
            }
        }

        override fun onAuthError() {
            // Auth is fatal for THIS destination only.
            runtime.state = DestinationState.FAILED
            runtime.lastError = "Authentication failed — check the stream key"
            notifyDestinations()
            checkAllDestinationsTerminal()
        }

        override fun onAuthSuccess() { /* no-op */ }

        override fun onNewBitrate(bitrate: Long) {
            runtime.bitrateKbps = bitrate / 1000
        }
    }

    private fun checkAllDestinationsTerminal() {
        val runtimes = destinationRuntimes
        if (runtimes.isEmpty()) return
        val anyAlive = runtimes.any {
            it.state == DestinationState.LIVE ||
                it.state == DestinationState.CONNECTING ||
                it.state == DestinationState.RECONNECTING
        }
        if (!anyAlive) {
            // Every destination failed — end the broadcast, but this is the
            // ONLY case where one failure can affect another (all of them
            // failed independently).
            mainHandler.post {
                internalStop(StreamState.ERROR, "All destinations failed")
            }
        }
    }

    private fun notifyDestinations() {
        val statuses = destinationStatuses()
        mainHandler.post {
            listeners.forEach { it.onDestinationsChanged(statuses) }
        }
    }

    private fun BroadcastPlan.legacyReconnectAttempts(): Int =
        activeConfig?.maxReconnectAttempts ?: 5

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
                val error2 = prepareEncoders(
                    context,
                    StreamConfig.from(SettingsRepository.get(context))
                )
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
        previewDims = null
        canvasPreviewKey = null
    }

    // ------------------------------------------------------------------
    // Source configuration (single engine — legacy + relay path, unchanged)
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
                                loopCount++
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
    // LEGACY start (single destination — EXACT v1 behavior preserved)
    // ------------------------------------------------------------------

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
        activePlan = null
        previewDims = null
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
            if (previewView != null) {
                try {
                    if (!s.isOnPreview) s.startPreview(previewView)
                } catch (_: Throwable) {
                }
            }
            applyMixerState()
            beginHealthMonitoring(config.fps, config.sampleRate)
            null
        } catch (t: Throwable) {
            if (debugLogging) Log.e(TAG, "startStream failed", t)
            internalStop(StreamState.ERROR, null)
            "Could not start the stream: ${t.message ?: "unknown error"}"
        }
    }

    // ------------------------------------------------------------------
    // BROADCAST start (project plan: playlists, multi-destination, relay)
    // ------------------------------------------------------------------

    /**
     * Start a full broadcast from a [BroadcastPlan].
     *
     * Path selection:
     *  - SMART_RELAY (any count) or DIRECT with 1 destination → single engine
     *    upstream (identical to the proven legacy path).
     *  - DIRECT with 2+ destinations → MultiStream (one encoder, N clients).
     *
     * @param relaySession pre-created relay session (optional; created
     *        synchronously when null and the plan uses Smart Relay).
     * @return null on success or a readable error message.
     */
    @Synchronized
    fun startBroadcast(
        context: Context,
        plan: BroadcastPlan,
        previewView: SurfaceView?,
        relaySession: RelaySessionClient.Session? = null
    ): String? {
        if (isStreaming) return "Already streaming"

        val validationError = plan.validate()
        if (validationError != null) return validationError

        debugLogging = SettingsRepository.get(context).debugLogging
        mode = plan.mode
        appContext = context.applicationContext
        activePlan = plan
        activeConfig = StreamConfig.from(SettingsRepository.get(context)).copy(
            videoWidth = plan.quality.width,
            videoHeight = plan.quality.height,
            fps = plan.quality.fps,
            videoBitrateKbps = plan.quality.videoBitrateKbps,
            audioBitrateKbps = plan.audio.audioBitrateKbps,
            sampleRate = plan.audio.sampleRate,
            stereo = plan.audio.stereo,
            echoCanceler = plan.audio.echoCanceler,
            noiseSuppressor = plan.audio.noiseSuppressor
        )
        reconnectAttempt = 0
        reconnectTotal = 0
        loopCount = 0
        sessionStatus = "COMPLETED"

        // ---------------- Relay session (one upstream) ----------------
        var upstreamUrl: String? = null
        if (plan.broadcastMode == BroadcastMode.SMART_RELAY) {
            val session = relaySession ?: run {
                val relay = plan.relay ?: return "Smart Relay requires a relay server"
                setState(StreamState.CONNECTING, "Registering destinations with relay…")
                when (val r = RelaySessionClient.createSession(
                    relay.apiUrl, relay.token, plan.projectName, plan.activeDestinations
                )) {
                    is RelaySessionClient.Result.Error -> {
                        setState(StreamState.ERROR, null)
                        return "Relay error: ${r.message}"
                    }
                    is RelaySessionClient.Result.Ok -> r.value
                }
            }
            relaySessionHolder = session
            upstreamUrl = ingestFullUrl(session)
        }

        val useMulti =
            plan.broadcastMode == BroadcastMode.DIRECT && plan.activeDestinations.size > 1

        setState(StreamState.CONNECTING, "Preparing ${if (mode == Mode.VIDEO) "video" else "camera"}…")

        // ---------------- Playlist / sources ----------------
        if (mode == Mode.VIDEO) {
            val first = plan.playlist.first()
            val info = MediaAnalyzer.analyze(context, first.uri)
                ?: return "Video not playable on this device: ${first.displayName}"
            activeVideoInfo = info
            activeVideoUri = first.uri
            playlistEngine = PlaylistEngine(plan.playlist, plan.loopMode).also { engine ->
                engine.setMode(plan.loopMode)
            }

            val internalLoop = playlistEngine?.usesInternalLoop == true
            val vSource = VideoFileSource(context.applicationContext, first.uri, internalLoop) { isLoop ->
                // Decoder thread — dispatch transitions to the executor.
                if (isLoop) {
                    mainHandler.post { onInternalLoopBoundary() }
                } else {
                    transitionExecutor.execute { onPlaylistBoundary() }
                }
            }
            val aSource = MixedFileAudioSource(
                context.applicationContext, first.uri, internalLoop
            )
            videoFileSource = vSource
            mixedAudioSource = aSource
            activeVideoUri = first.uri

            // Apply saved mixer settings from the plan.
            videoVolume = plan.audio.videoVolume
            micVolume = plan.audio.micVolume
            videoAudioEnabled = true
            micEnabled = plan.audio.micEnabled

            if (useMulti) {
                buildMultiEngine(context, plan, vSource, aSource)
            } else {
                val s = engine(context)
                s.changeVideoSource(vSource)
                s.changeAudioSource(aSource)
            }
            prepared = false
        } else {
            // CAMERA mode
            playlistEngine = null
            videoFileSource = null
            mixedAudioSource = null
            val cam = Camera2Source(context.applicationContext)
            val mic = MicrophoneSource()
            cameraSource = cam
            microphoneSource = mic
            micEnabled = plan.audio.micEnabled
            micVolume = plan.audio.micVolume
            if (useMulti) {
                buildMultiEngine(context, plan, cam, mic)
            } else {
                val s = engine(context)
                s.changeVideoSource(cam)
                s.changeAudioSource(mic)
            }
            prepared = false
        }

        // ---------------- Encoders (ONCE) ----------------
        setState(StreamState.CONNECTING, "Initializing encoder…")
        val prepError = prepareEncoders(context, activeConfig!!)
        if (prepError != null) {
            setState(StreamState.ERROR, null)
            teardownMulti()
            return prepError
        }

        // ---------------- Connect ----------------
        return try {
            if (useMulti) {
                val multi = multiStream ?: return "Engine not ready"
                // Connect every destination — one encoder, N sessions.
                destinationRuntimes.forEachIndexed { index, runtime ->
                    runtime.state = DestinationState.CONNECTING
                    try {
                        multi.getStreamClient(MultiType.RTMP, index).setLogs(false)
                    } catch (_: Throwable) {
                    }
                    multi.startStream(MultiType.RTMP, index, runtime.config.fullUrl())
                }
                setState(StreamState.CONNECTING, "Connecting ${destinationRuntimes.size} destinations…")
            } else {
                val s = engine(context)
                val url = upstreamUrl ?: plan.activeDestinations.first().fullUrl()
                try {
                    s.getStreamClient().setReTries(activeConfig!!.maxReconnectAttempts)
                } catch (_: Throwable) {
                }
                setState(StreamState.CONNECTING, "Connecting to server…")
                s.startStream(url)
            }

            // Preview re-attach (encoders re-prepared above).
            if (previewView != null) {
                try {
                    if (stream?.isOnPreview != true) stream?.startPreview(previewView)
                } catch (_: Throwable) {
                }
            }

            applyMixerState()

            // Video mode: keep frames flowing across playlist gaps at the
            // configured fps — no viewer-visible freeze, continuous timeline.
            if (mode == Mode.VIDEO) {
                try {
                    stream?.getGlInterface()?.setForceRender(true, plan.quality.fps)
                } catch (_: Throwable) {
                }
            }

            // Canvas transform (Part 2) — first filter, before every layer.
            plan.canvas?.let { canvas -> applyCanvasInternal(canvas, activeVideoInfo) }

            // Overlays from the plan's initial scene.
            applyOverlays(plan.overlaysForInitialScene())

            // Relay status polling (real data from the relay server).
            relayPollsEnabled = plan.broadcastMode == BroadcastMode.SMART_RELAY

            recordSessionStart(plan)
            beginHealthMonitoring(plan.quality.fps, plan.audio.sampleRate)
            notifyDestinations()
            null
        } catch (t: Throwable) {
            if (debugLogging) Log.e(TAG, "startBroadcast failed", t)
            internalStop(StreamState.ERROR, null)
            "Could not start the broadcast: ${t.message ?: "unknown error"}"
        }
    }

    @Volatile private var relaySessionHolder: RelaySessionClient.Session? = null

    private fun buildMultiEngine(
        context: Context,
        plan: BroadcastPlan,
        videoSource: com.pedro.encoder.input.sources.video.VideoSource,
        audioSource: AudioSource
    ) {
        teardownMulti()
        // ONE runtime per destination, shared by the manager list and the
        // per-destination ConnectCheckers.
        destinationRuntimes.clear()
        plan.activeDestinations.forEach { dest ->
            destinationRuntimes += DestinationRuntime(dest)
        }
        val checkers: Array<ConnectChecker> =
            destinationRuntimes.map { DestinationConnectChecker(it) as ConnectChecker }
                .toTypedArray()
        val multi = MultiStream(
            context.applicationContext,
            connectCheckerRtmpList = checkers,
            connectCheckerRtspList = null,
            connectCheckerSrtList = null,
            connectCheckerUdpList = null,
            videoSource = videoSource,
            audioSource = audioSource
        )
        multiStream = multi
    }

    private fun teardownMulti() {
        val multi = multiStream ?: return
        try {
            multi.stopStream()
        } catch (_: Throwable) {
        }
        try {
            if (multi.isOnPreview) multi.stopPreview()
        } catch (_: Throwable) {
        }
        try {
            multi.release()
        } catch (_: Throwable) {
        }
        multiStream = null
    }

    // ------------------------------------------------------------------
    // PLAYLIST ENGINE — boundary handling (the critical path)
    // ------------------------------------------------------------------

    /**
     * Internal decoder loop of the SAME file (LOOP_ONE / single item):
     * zero-cost boundary — decoder seeks to 0, encoder + RTMP untouched.
     */
    private fun onInternalLoopBoundary() {
        val engine = playlistEngine ?: return
        engine.onInternalLoop()
        loopCount = engine.boundaryCount
        timelineGuard.onBoundary()
        // AUDIO LOOP SYNC (Part 4): the video decoder restarted the file.
        // If the audio decoder is not at the loop start too (different track
        // lengths), restart it from 0 — audio stays aligned with video.
        // Encoder/muxer/RTMP untouched; the mixed timeline continues.
        // Runs on the transition executor (never the main or decoder thread).
        transitionExecutor.execute {
            try {
                mixedAudioSource?.resyncToLoopStart()
            } catch (t: Throwable) {
                if (debugLogging) Log.w(TAG, "audio loop resync skipped: ${t.message}")
            }
        }
        notifyStatsSoon()
    }

    /**
     * End of a playlist item. THIS IS A CONTROLLED TRANSITION:
     *  1. end-of-source detected (video decoder EOF)
     *  2. next source prepared and swapped IN PLACE
     *  3. output timeline continues (force-render bridges the decoder gap)
     *  4. RTMP never reconnects, encoder never restarts, service/timer live on
     *
     * Runs on the playlist transition executor (never a decoder thread).
     */
    private fun onPlaylistBoundary() {
        val engine = playlistEngine ?: return
        val context = appContext ?: return
        if (!isStreaming) return

        timelineGuard.onBoundary()
        val next = engine.onItemFinished()
        // The counter reflects the COMPLETED boundary immediately — even if
        // the upcoming source switch fails, the dashboard must never lie
        // with "Loop #0" while the stream actually ran through a file.
        loopCount = engine.boundaryCount
        if (next == null) {
            // PLAY_ONCE finished the whole playlist — graceful, planned end.
            mainHandler.post {
                sessionStatus = "COMPLETED"
                stopBroadcast()
            }
            return
        }
        switchToItem(context, next)
        notifyStatsSoon()
    }

    /** Swap the current playlist item. Encoder/RTMP untouched. */
    private fun switchToItem(context: Context, item: PlaylistMedia) {
        val vSource = videoFileSource ?: return
        val shouldLoop = playlistEngine?.usesInternalLoop ?: false
        try {
            vSource.replaceFile(context, item.uri)
            vSource.setLoopMode(shouldLoop)
        } catch (t: Throwable) {
            if (debugLogging) Log.e(TAG, "video replaceFile failed: ${t.message}")
            // Targeted recovery: try the next item (bounded).
            val engine = playlistEngine ?: return
            val fallback = engine.onItemError()
            if (fallback != null) {
                try {
                    vSource.replaceFile(context, fallback.uri)
                    vSource.setLoopMode(playlistEngine?.usesInternalLoop ?: false)
                } catch (t2: Throwable) {
                    mainHandler.post {
                        internalStop(StreamState.ERROR, "Playlist item failed to load")
                    }
                }
            } else {
                mainHandler.post {
                    internalStop(StreamState.ERROR, "Playlist item failed to load")
                }
            }
            return
        }

        // Audio follows the same boundary. Format was validated at start.
        val audio = mixedAudioSource
        val config = activeConfig
        if (audio != null && config != null) {
            val ok = audio.replaceFile(
                context, item.uri,
                config.sampleRate.takeIf { it > 0 } ?: 44100,
                config.stereo
            )
            audio.setLoopMode(playlistEngine?.usesInternalLoop ?: false)
            if (!ok && debugLogging) {
                Log.w(TAG, "audio format mismatch at boundary — silence fallback active")
            }
        }
        activeVideoUri = item.uri
    }

    /** Manual skip to the next playlist item (user action, live). */
    fun skipToNext() {
        val engine = playlistEngine ?: return
        val context = appContext ?: return
        if (mode != Mode.VIDEO) return
        transitionExecutor.execute {
            timelineGuard.onBoundary()
            val item = engine.skipToNext()
            switchToItem(context, item)
            loopCount = engine.boundaryCount
            notifyStatsSoon()
        }
    }

    fun skipToPrevious() {
        val engine = playlistEngine ?: return
        val context = appContext ?: return
        if (mode != Mode.VIDEO) return
        transitionExecutor.execute {
            timelineGuard.onBoundary()
            val item = engine.skipToPrevious()
            switchToItem(context, item)
            loopCount = engine.boundaryCount
            notifyStatsSoon()
        }
    }

    /** Live playlist information for the dashboard. */
    data class PlaylistInfo(
        val itemCount: Int,
        val currentIndex: Int,
        val currentName: String,
        val loopMode: LoopMode,
        val boundaryCount: Int
    )

    fun playlistInfo(): PlaylistInfo? {
        val engine = playlistEngine ?: return null
        if (engine.size == 0) return null
        return PlaylistInfo(
            itemCount = engine.size,
            currentIndex = engine.currentIndex,
            currentName = engine.current.displayName,
            loopMode = engine.currentMode,
            boundaryCount = engine.boundaryCount
        )
    }

    /** Change loop mode DURING a live broadcast (never stops the stream). */
    fun setLoopMode(loopMode: LoopMode) {
        val engine = playlistEngine ?: return
        transitionExecutor.execute {
            val wasInternal = engine.usesInternalLoop
            engine.setMode(loopMode)
            val nowInternal = engine.usesInternalLoop
            if (wasInternal != nowInternal) {
                // Decoder loop flag must match: internal loop for LOOP_ONE /
                // single item; explicit boundaries otherwise.
                try {
                    videoFileSource?.setLoopMode(nowInternal)
                    mixedAudioSource?.setLoopMode(nowInternal)
                } catch (_: Throwable) {
                }
            }
            notifyStatsSoon()
        }
    }

    // ------------------------------------------------------------------
    // Stop / release
    // ------------------------------------------------------------------

    fun stopStream() {
        mainHandler.post { internalStop(StreamState.OFFLINE, null) }
    }

    /** Stop a plan-based broadcast (records history, ends relay session). */
    fun stopBroadcast() {
        mainHandler.post { internalStop(StreamState.OFFLINE, null) }
    }

    private fun internalStop(finalState: StreamState, message: String?) {
        stopStatsTicker()
        recordSessionEnd()

        val multi = multiStream
        if (multi != null) {
            try {
                destinationRuntimes.forEachIndexed { index, _ ->
                    try {
                        multi.stopStream(MultiType.RTMP, index)
                    } catch (_: Throwable) {
                    }
                }
            } catch (_: Throwable) {
            }
            destinationRuntimes.forEach { it.state = DestinationState.STOPPED }
            teardownMulti()
            notifyDestinations()
        } else {
            val s = singleStream
            try {
                if (s != null && s.isStreaming) s.stopStream()
            } catch (t: Throwable) {
                if (debugLogging) Log.e(TAG, "stopStream failed", t)
            }
        }

        // End relay session (fire-and-forget; relay also auto-expires).
        relayPollsEnabled = false
        val session = relaySessionHolder
        val plan = activePlan
        if (session != null && plan?.relay != null) {
            val relay = plan.relay!!
            Thread {
                try {
                    RelaySessionClient.endSession(relay.apiUrl, relay.token, session.sessionId)
                } catch (_: Throwable) {
                }
            }.apply { isDaemon = true; name = "relay-end" }.start()
        }
        relaySessionHolder = null

        try {
            stream?.getGlInterface()?.setForceRender(false, 5)
        } catch (_: Throwable) {
        }
        clearOverlays()
        overlayTicker.stop()
        timelineGuard.stop()
        watchdogCenter.reset()

        activeConfig = null
        activePlan = null
        playlistEngine = null
        reconnectAttempt = 0
        lastBitrateKbps = 0
        stats = StreamStats()
        prepared = false
        setState(finalState, message)
        notifyStats()
        notifyDestinations()
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
    // OVERLAY CONTROL (live — composited into the encoded stream)
    // ------------------------------------------------------------------

    /**
     * Apply a set of overlays to the ENCODED stream. Runs as a live GL filter
     * diff — the RTMP session and encoders are never touched.
     */
    fun applyOverlays(configs: List<OverlayConfig>) {
        val context = appContext ?: return
        val plan = activePlan
        val dims = plan?.let { it.quality.width to it.quality.height } ?: previewDims
        if (dims == null) return
        val width = dims.first
        val height = dims.second
        val gl = try {
            stream?.getGlInterface()
        } catch (_: Throwable) {
            null
        } ?: return

        // Z-ORDER: the requested order IS the draw order. If the order of
        // existing layers changed (editor drag), rebuild the chain in order —
        // a live rebuild is filter add/remove only (no stream impact).
        val activeOrder = activeOverlays.keys.toList()
        val requestedOrder = configs.map { it.id }
        val orderChanged = activeOrder.any { it in requestedOrder.toSet() } &&
            activeOrder.filter { it in requestedOrder.toSet() } !=
            requestedOrder.filter { it in activeOrder.toSet() }

        // Remove overlays that are no longer active (or all, on reorder).
        val activeIds = configs.map { it.id }.toSet()
        val iterator = activeOverlays.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key !in activeIds || orderChanged) {
                entry.value.renders.forEach { render ->
                    try {
                        OverlayFilterFactory.releaseLayer(render)
                    } catch (_: Throwable) {
                    }
                    try {
                        gl.removeFilter(render)
                    } catch (_: Throwable) {
                    }
                }
                iterator.remove()
            }
        }
        // Add new / update existing.
        configs.forEach { config ->
            val existingBuilt = activeOverlays[config.id]
            if (existingBuilt != null) {
                if (OverlayFilterFactory.needsRebuild(existingBuilt.sourceConfig, config)) {
                    // Structural change (type/media/text): rebuild this layer only.
                    existingBuilt.renders.forEach { render ->
                        try {
                            OverlayFilterFactory.releaseLayer(render)
                        } catch (_: Throwable) {
                        }
                        try {
                            gl.removeFilter(render)
                        } catch (_: Throwable) {
                        }
                    }
                    activeOverlays.remove(config.id)
                } else {
                    // Position/scale/rotation/opacity — live in-place update.
                    OverlayFilterFactory.updateInPlace(existingBuilt, config, width, height)
                    return@forEach
                }
            }
            val built = try {
                OverlayFilterFactory.build(
                    context, config, width, height, overlayTicker
                ) { uri, into ->
                    Thread {
                        val bmp = OverlayFilterFactory.decodeImage(context, uri)
                        mainHandler.post { into(bmp) }
                    }.apply { isDaemon = true }.start()
                }
            } catch (_: Throwable) {
                null
            } ?: return@forEach
            activeOverlays[config.id] = built
            built.renders.forEach { render ->
                try {
                    gl.addFilter(render)
                    OverlayFilterFactory.startLayer(render)
                } catch (_: Throwable) {
                }
            }
        }
        if (activeOverlays.isNotEmpty()) overlayTicker.start()
    }

    /** Switch scene live: apply the scene's overlay set. */
    fun applyScene(scene: SceneConfig, allOverlays: List<OverlayConfig>) {
        applyOverlays(allOverlays.filter { it.id in scene.overlayIds && it.enabled })
    }

    fun activeOverlayIds(): List<Long> = activeOverlays.keys.toList()

    private fun clearOverlays() {
        val gl = try {
            stream?.getGlInterface()
        } catch (_: Throwable) {
            null
        }
        if (gl != null && gl.isRunning) {
            try {
                gl.clearFilters()
            } catch (_: Throwable) {
            }
        }
        activeOverlays.values.forEach { built ->
            built.renders.forEach { render ->
                try {
                    OverlayFilterFactory.releaseLayer(render)
                } catch (_: Throwable) {
                }
            }
        }
        activeOverlays.clear()
        overlayTicker.stop()
        canvasRender = null
        activeCanvas = null
        previewDims = null
    }

    // ------------------------------------------------------------------
    // Live canvas control (Part 2)
    // ------------------------------------------------------------------

    /**
     * Apply the canvas as the FIRST filter in the chain. Live-safe: adding or
     * updating it never touches the encoder, RTMP or timestamps.
     */
    private fun applyCanvasInternal(
        canvas: com.livevip.app.overlay.CanvasConfig,
        sourceInfo: com.livevip.app.media.MediaAnalyzer.VideoInfo?
    ) {
        val gl = try {
            stream?.getGlInterface()
        } catch (_: Throwable) {
            null
        } ?: return
        val srcW = sourceInfo?.width ?: canvas.width
        val srcH = sourceInfo?.height ?: canvas.height
        activeCanvas = canvas
        var render = canvasRender
        if (render == null) {
            render = com.livevip.app.overlay.CanvasVideoTransformRender()
            canvasRender = render
            try {
                gl.addFilter(0, render)
            } catch (_: Throwable) {
            }
        }
        render.update(
            canvas.transform, srcW, srcH, canvas.width, canvas.height, canvas.backgroundColor
        )
    }

    /** Live update of the main-video transform (editor gestures while LIVE). */
    fun updateVideoTransform(transform: com.livevip.app.overlay.VideoTransform) {
        val canvas = activeCanvas ?: return
        val info = activeVideoInfo
        canvasRender?.update(
            transform,
            info?.width ?: canvas.width, info?.height ?: canvas.height,
            canvas.width, canvas.height, canvas.backgroundColor
        )
        activeCanvas = canvas.copy(transform = transform)
    }

    fun activeCanvasConfig(): com.livevip.app.overlay.CanvasConfig? = activeCanvas

    /**
     * Canvas preview for the editor: prepares encoders at the CANVAS
     * resolution and applies the canvas + layers, so the editor preview is
     * pixel-equivalent to the broadcast. UI-only previews never fake this.
     */
    /** Identifies the composition the preview is currently prepared for. */
    private var canvasPreviewKey: String? = null

    fun previewCanvas(
        context: Context,
        view: android.view.SurfaceView,
        videoUri: Uri?,
        canvas: com.livevip.app.overlay.CanvasConfig,
        overlays: List<OverlayConfig>
    ): String? {
        return try {
            val s = engine(context)
            if (s.isStreaming) return null // live preview already shows the canvas

            // Same video + same canvas resolution ⇒ the encoders are already
            // prepared correctly. The preview surface may simply have been
            // recreated (layout/resize) — rebind it WITHOUT re-preparing the
            // encoder (no churn on every aspect-row tap or surface change).
            val key = "${videoUri}|${canvas.width}x${canvas.height}"
            if (key == canvasPreviewKey) {
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
            // The encoder MUST run at the canvas resolution — always re-prepare
            // so a previous non-canvas preview can never leak old dims.
            prepared = false
            val error = configureSources(context, videoUri, previewOnly = true)
            if (error != null) return error
            val config = StreamConfig.from(SettingsRepository.get(context)).copy(
                videoWidth = canvas.width,
                videoHeight = canvas.height
            )
            val error2 = prepareEncoders(context, config)
            if (error2 != null) return error2
            previewDims = canvas.width to canvas.height
            val info = if (videoUri != null) MediaAnalyzer.analyze(context, videoUri) else null
            applyCanvasInternal(canvas, info)
            applyOverlays(overlays)
            s.startPreview(view)
            canvasPreviewKey = key
            null
        } catch (t: Throwable) {
            if (debugLogging) Log.e(TAG, "previewCanvas failed", t)
            "Canvas preview failed: ${t.message ?: "unknown error"}"
        }
    }

    private fun BroadcastPlan.overlaysForInitialScene(): List<OverlayConfig> =
        if (initialSceneOverlayIds.isEmpty()) {
            overlays.filter { it.enabled }
        } else {
            overlays.filter { it.id in initialSceneOverlayIds && it.enabled }
        }

    // ------------------------------------------------------------------
    // Health monitoring: stats + timeline guard + watchdogs + relay poll
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
        val multi = multiStream
        if (multi != null) {
            (0 until destinationRuntimes.size).sumOf { i ->
                try {
                    multi.getStreamClient(MultiType.RTMP, i).getSentVideoFrames()
                } catch (_: Throwable) {
                    0L
                }
            }
        } else {
            singleStream?.getStreamClient()?.getSentVideoFrames() ?: 0L
        }
    } catch (_: Throwable) {
        0L
    }

    private fun totalSentAudioFrames(): Long = try {
        val multi = multiStream
        if (multi != null) {
            (0 until destinationRuntimes.size).sumOf { i ->
                try {
                    multi.getStreamClient(MultiType.RTMP, i).getSentAudioFrames()
                } catch (_: Throwable) {
                    0L
                }
            }
        } else {
            singleStream?.getStreamClient()?.getSentAudioFrames() ?: 0L
        }
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
        val multi = multiStream
        if (multi != null) {
            var droppedTotal = 0L
            var congestion = false
            destinationRuntimes.forEachIndexed { index, runtime ->
                try {
                    val client = multi.getStreamClient(MultiType.RTMP, index)
                    runtime.droppedFrames =
                        client.getDroppedVideoFrames() + client.getDroppedAudioFrames()
                    runtime.congestion = client.hasCongestion(20f)
                    droppedTotal += runtime.droppedFrames
                    congestion = congestion || runtime.congestion
                } catch (_: Throwable) {
                }
            }
            val durationSec = (System.currentTimeMillis() - streamStartElapsed) / 1000
            stats = StreamStats(
                bitrateKbps = destinationRuntimes.maxOfOrNull { it.bitrateKbps } ?: 0,
                durationSec = durationSec,
                droppedFrames = droppedTotal,
                congestion = congestion,
                fps = activeConfig?.fps ?: 0,
                loopCount = loopCount,
                reconnects = reconnectTotal,
                destinationsLive = destinationRuntimes.count { it.state == DestinationState.LIVE },
                destinationsTotal = destinationRuntimes.size,
                mediaVerified = mediaVerified(),
                avSyncMs = timelineGuard.snapshot().avDriftMs
            )
        } else {
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
            val liveDestinations = if (relayPollsEnabled) 0 else 1
            stats = StreamStats(
                bitrateKbps = lastBitrateKbps,
                durationSec = (System.currentTimeMillis() - streamStartElapsed) / 1000,
                droppedFrames = dropped,
                congestion = congestion,
                fps = activeConfig?.fps ?: 0,
                loopCount = loopCount,
                reconnects = reconnectTotal,
                destinationsLive = if (relayPollsEnabled) relayDestinationLiveCount else liveDestinations,
                destinationsTotal = destinationRuntimes.size.takeIf { it > 0 }
                    ?: if (relayPollsEnabled) relayDestinationTotal else 1,
                mediaVerified = mediaVerified(),
                avSyncMs = timelineGuard.snapshot().avDriftMs
            )
        }

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

        // Relay status poll (real per-destination data from the relay).
        if (relayPollsEnabled) pollRelayStatus()

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
            (singleStream?.isStreaming == true) || (multiStream?.isStreaming == true)
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
                rtmps = when {
                    !socketUp -> "DOWN"
                    relayPollsEnabled -> "RELAY"
                    else -> "CONNECTED"
                },
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

    @Volatile private var relayDestinationLiveCount = 0
    @Volatile private var relayDestinationTotal = 0

    // Component-health snapshots (dashboard).
    @Volatile private var lastDecoderTimeSnapshot = 0.0
    @Volatile private var lastVideoFramesSnapshot = 0L
    @Volatile private var lastAudioFramesSnapshot = 0L
    private var lastRelayPollMs = 0L

    private fun pollRelayStatus() {
        val now = System.currentTimeMillis()
        if (now - lastRelayPollMs < 5000) return
        lastRelayPollMs = now
        val session = relaySessionHolder ?: return
        val relay = activePlan?.relay ?: return
        Thread {
            when (val r = RelaySessionClient.sessionStatus(relay.apiUrl, relay.token, session.sessionId)) {
                is RelaySessionClient.Result.Ok -> {
                    relayDestinationTotal = r.value.destinations.size
                    relayDestinationLiveCount = r.value.destinations.count {
                        it.state.equals("live", true)
                    }
                    // Surface relay statuses as destination statuses.
                    val statuses = r.value.destinations.map {
                        DestinationRuntimeStatus(
                            id = it.name.hashCode().toLong(),
                            name = it.name,
                            platform = "Relay",
                            state = when (it.state.lowercase()) {
                                "live" -> DestinationState.LIVE
                                "connecting" -> DestinationState.CONNECTING
                                "reconnecting" -> DestinationState.RECONNECTING
                                "failed" -> DestinationState.FAILED
                                else -> DestinationState.STOPPED
                            },
                            bitrateKbps = 0,
                            reconnectCount = it.restarts,
                            droppedFrames = 0,
                            congestion = false,
                            lastError = it.detail?.let { d -> Sanitizer.shorten(d) }
                        )
                    }
                    mainHandler.post {
                        listeners.forEach { l -> l.onDestinationsChanged(statuses) }
                    }
                }
                is RelaySessionClient.Result.Error -> Unit // keep streaming; next poll retries
            }
        }.apply { isDaemon = true; name = "relay-poll" }.start()
    }

    /** TARGETED recovery: restart only the video source (decoder). */
    private fun restartVideoSource() {
        val context = appContext ?: return
        val uri = activeVideoUri ?: return
        val engine = playlistEngine
        transitionExecutor.execute {
            try {
                // replaceFile creates a FRESH decoder whose loop flag defaults
                // to false — re-apply the engine's loop mode or a single-video
                // stream would EOS straight into a boundary after recovery.
                val shouldLoop = playlistEngine?.usesInternalLoop ?: false
                if (engine != null) {
                    // Re-queue the current item from its start.
                    val current = engine.current
                    videoFileSource?.replaceFile(context, current.uri)
                    videoFileSource?.setLoopMode(shouldLoop)
                    mixedAudioSource?.replaceFile(
                        context, current.uri,
                        activeConfig?.sampleRate ?: 44100,
                        activeConfig?.stereo ?: true
                    )
                    mixedAudioSource?.setLoopMode(shouldLoop)
                } else {
                    videoFileSource?.replaceFile(context, uri)
                    videoFileSource?.setLoopMode(shouldLoop)
                }
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
                val internalLoop = playlistEngine?.usesInternalLoop ?: false
                val fresh = MixedFileAudioSource(context, uri, internalLoop)
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
    // Session history
    // ------------------------------------------------------------------

    private fun recordSessionStart(plan: BroadcastPlan) {
        val context = appContext ?: return
        try {
            val repo = ProjectRepository.get(context)
            repo.recordStreamStarted(plan.projectId)
            currentSessionId = repo.startSession(
                StreamSession(
                    projectId = plan.projectId,
                    projectName = plan.projectName,
                    destinationsCount = plan.activeDestinations.size,
                    broadcastMode = plan.broadcastMode,
                    status = "LIVE"
                )
            )
        } catch (_: Throwable) {
        }
    }

    private fun recordSessionEnd() {
        val plan = activePlan ?: return
        val context = appContext ?: return
        val sessionId = currentSessionId
        if (sessionId == 0L) return
        currentSessionId = 0
        try {
            val repo = ProjectRepository.get(context)
            repo.finishSession(
                sessionId,
                stats.durationSec,
                loopCount,
                sessionStatus
            )
            repo.recordStreamStats(plan.projectId, stats.durationSec, loopCount)
        } catch (_: Throwable) {
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

    // ------------------------------------------------------------------
    // Static helpers
    // ------------------------------------------------------------------

    fun ingestFullUrl(session: RelaySessionClient.Session): String {
        val base = session.ingestUrl.trim().trimEnd('/')
        return if (session.ingestKey.isBlank()) base else "$base/${session.ingestKey}"
    }
}
