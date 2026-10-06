package com.livevip.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.livevip.app.R
import com.livevip.app.data.SettingsRepository
import com.livevip.app.databinding.ActivityHomeBinding
import com.livevip.app.media.SelectedVideo
import com.livevip.app.media.SelectedVideoStore
import com.livevip.app.overlay.CanvasAspect
import com.livevip.app.overlay.CanvasPreviewMath
import com.livevip.app.overlay.FitMode
import com.livevip.app.overlay.VideoTransform
import com.livevip.app.service.LiveBubbleService
import com.livevip.app.service.LiveStreamingService
import com.livevip.app.streaming.CanvasPresets
import com.livevip.app.streaming.LiveStreamingManager
import com.livevip.app.streaming.StreamConfig
import com.livevip.app.streaming.StreamState
import com.livevip.app.streaming.StreamStats
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * THE ONE SCREEN — OBS-style mobile broadcaster.
 *
 * PREVIEW SURFACE LIFECYCLE (Part 5.1 regression fix):
 * RootEncoder's `startPreview(surfaceView)` binds `holder.surface` ONE-SHOT:
 * it THROWS on an invalid surface and never registers holder callbacks. The
 * Part 5 regression started/resized the surface and bound it in the same
 * tick — the surface was destroyed by the pending resize right after, so the
 * preview (and every later rebind, blocked by isOnPreview) stayed BLACK.
 *
 * The proven pattern (restored): bind ONLY from `surfaceCreated`, stop the
 * preview in `onPause` when not live, and resize via layoutParams without
 * starting — surface recreation re-triggers `surfaceCreated` → rebind.
 *
 * Preview IS the live output: the same GL transform compositor renders to
 * the preview surface and the encoder. Gestures live-update the composition
 * without touching the encoder, RTMP or timestamps.
 */
class HomeActivity : AppCompatActivity(), LiveStreamingManager.Listener {

    private lateinit var binding: ActivityHomeBinding
    private val manager get() = LiveStreamingManager

    // ---- Saved Live state ----
    private var selectedVideo: SelectedVideo? = null
    private var aspect: CanvasAspect = CanvasAspect.LANDSCAPE_16_9
    private var transform: VideoTransform = VideoTransform()
    private var sessionBannerShown = false

    // ---- Preview surface lifecycle (THE gate — see class doc) ----
    private var surfaceReady = false
    private var lastHealth: LiveStreamingManager.StreamHealth? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()

    // ---- Pickers / permissions ----
    private val pickVideo =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) onVideoPicked(uri)
        }

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) setMicInternal(true) else {
                Toast.makeText(this, R.string.mic_permission_denied, Toast.LENGTH_SHORT).show()
                refreshAudioButtons()
            }
        }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best-effort */ }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyWindowInsets()
        loadSavedLive()
        wireUI()
        manager.addListener(this)
        updateStateUi(manager.state, null)
        enterLiveUiIfStreaming()
        if (SettingsRepository.get(this).debugLogging) {
            binding.diagnosticsCard.visibility = View.VISIBLE
        }
    }

    override fun onResume() {
        super.onResume()
        // Rebind after pause (onPause stopped the preview when not live) or
        // after the surface was recreated. Never while live — the engine
        // owns the preview then; surfaceCreated handles that case.
        if (!manager.isBroadcasting && !manager.isOnPreview) {
            startPreviewIfReady()
        }
    }

    override fun onPause() {
        // PROVEN pattern: releasing the preview while paused resets the
        // engine's isOnPreview flag, so the next startPreview cannot hit the
        // "Preview already started" exception that caused the black screen.
        // While LIVE the preview is never touched — the stream must survive.
        if (!manager.isBroadcasting) {
            manager.stopPreview()
        }
        // Persist the gesture transform once, not on every ACTION_MOVE.
        persistTransform()
        super.onPause()
    }

    override fun onDestroy() {
        manager.removeListener(this)
        ioExecutor.shutdown()
        if (isFinishing && !manager.isBroadcasting) {
            manager.stopPreview()
        }
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // NEVER stop the stream on back — move behind the UI instead.
        moveTaskToBack(true)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (manager.isBroadcasting &&
            SettingsRepository.get(this).floatingBubbleEnabled &&
            android.provider.Settings.canDrawOverlays(this)
        ) {
            LiveBubbleService.start(this)
        }
    }

    /** Insets: content never hides behind status/nav bars on any device. */
    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootScroll) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    // ------------------------------------------------------------------
    // Saved Live
    // ------------------------------------------------------------------

    private fun loadSavedLive() {
        val settings = SettingsRepository.get(this)
        aspect = CanvasAspect.from(settings.outputAspect)
        transform = runCatching {
            org.json.JSONObject(settings.transformJson.takeIf { it.isNotBlank() } ?: "{}")
                .let { VideoTransform.fromJson(it) }
        }.getOrDefault(VideoTransform())

        binding.inputUrl.setText(settings.streamUrl)
        binding.inputKey.setText(settings.streamKey)
        selectedVideo = SelectedVideoStore.current(this)

        refreshQualitySelection()
        refreshFpsSelection()
        refreshFormatButtons()
        refreshAudioButtons()
        refreshVideoInfoLine()
        refreshPreviewBadges()
    }

    private fun persistStreamInputs() {
        val settings = SettingsRepository.get(this)
        settings.streamUrl = binding.inputUrl.text.toString()
        settings.streamKey = binding.inputKey.text.toString()
        settings.outputAspect = aspect.name
    }

    private fun persistTransform() {
        SettingsRepository.get(this).transformJson = transform.toJson().toString()
    }

    // ------------------------------------------------------------------
    // UI wiring
    // ------------------------------------------------------------------

    private fun wireUI() {
        // Preview surface lifecycle — bind ONLY when the surface exists.
        binding.surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceReady = true
                if (manager.isBroadcasting) {
                    // Activity recreated while LIVE: rebind the preview only.
                    manager.rebindLivePreview(binding.surfaceView)
                } else {
                    startPreviewIfReady()
                }
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) =
                Unit // same surface, new size — the GL pipeline keeps drawing

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
            }
        })

        // Layout changes only RE-SIZE the surface (aspect/format changes);
        // binding happens exclusively in surfaceCreated — never here.
        binding.previewCard.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            applyPreviewAspect()
        }

        // Developer diagnostics toggle (Phase 14) — hidden gesture.
        binding.appTitle.setOnLongClickListener {
            val settings = SettingsRepository.get(this)
            settings.debugLogging = !settings.debugLogging
            binding.diagnosticsCard.visibility =
                if (settings.debugLogging) View.VISIBLE else View.GONE
            toast(if (settings.debugLogging) "Diagnostics ON" else "Diagnostics OFF")
            true
        }

        binding.btnSelectVideo.setOnClickListener { pickVideo.launch(arrayOf("video/*")) }

        binding.btnFormatLandscape.setOnClickListener { setAspect(CanvasAspect.LANDSCAPE_16_9) }
        binding.btnFormatPortrait.setOnClickListener { setAspect(CanvasAspect.PORTRAIT_9_16) }

        binding.btnQualityAuto.setOnClickListener { setQuality("auto") }
        binding.btnQuality480.setOnClickListener { setQuality("480p") }
        binding.btnQuality720.setOnClickListener { setQuality("720p") }
        binding.btnQuality1080.setOnClickListener { setQuality("1080p") }

        binding.btnFps24.setOnClickListener { setFps(24) }
        binding.btnFps30.setOnClickListener { setFps(30) }
        binding.btnFps60.setOnClickListener { setFps(60) }

        binding.btnFit.setOnClickListener { setFitMode(FitMode.FIT) }
        binding.btnFill.setOnClickListener { setFitMode(FitMode.FILL) }
        binding.btnResetTransform.setOnClickListener { resetTransform() }

        binding.btnVideoAudio.setOnClickListener {
            val settings = SettingsRepository.get(this)
            val on = !settings.videoAudioEnabled
            settings.videoAudioEnabled = on
            manager.setVideoAudioOn(on)
            refreshAudioButtons()
        }
        binding.btnMicAudio.setOnClickListener { toggleMic() }

        binding.btnStartLive.setOnClickListener { startLive() }
        binding.btnStopLive.setOnClickListener { stopLive() }

        wireGestures()
    }

    /** OBS-style transform gestures directly on the preview. */
    @SuppressLint("ClickableViewAccessibility")
    private fun wireGestures() {
        var lastX = 0f
        var lastY = 0f

        val tapDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                resetTransform()
                return true
            }
        })

        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val newScale = (transform.scale * detector.scaleFactor)
                    .coerceIn(MIN_SCALE, MAX_SCALE)
                transform = transform.copy(
                    scale = newScale,
                    fitMode = FitMode.CUSTOM
                )
                pushTransform()
                return true
            }
        })

        binding.previewCard.setOnTouchListener { _, event ->
            tapDetector.onTouchEvent(event)
            scaleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x; lastY = event.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!scaleDetector.isInProgress) {
                        val w = binding.previewCard.width.coerceAtLeast(1).toFloat()
                        val h = binding.previewCard.height.coerceAtLeast(1).toFloat()
                        val dx = (event.x - lastX) / w
                        val dy = (event.y - lastY) / h
                        lastX = event.x; lastY = event.y
                        if (abs(dx) > 0f || abs(dy) > 0f) {
                            transform = transform.copy(
                                offsetX = (transform.offsetX + dx).coerceIn(-1.5f, 1.5f),
                                offsetY = (transform.offsetY + dy).coerceIn(-1.5f, 1.5f),
                                fitMode = FitMode.CUSTOM
                            )
                            pushTransform()
                        }
                    } else {
                        lastX = event.x; lastY = event.y
                    }
                    true
                }
                else -> false
            }
        }
    }

    /** Apply the current transform to the SHARED preview+encoder pipeline. */
    private fun pushTransform() {
        if (manager.activeCanvasConfig() != null) {
            manager.updateVideoTransform(transform)
        }
    }

    private fun setFitMode(mode: FitMode) {
        transform = when (mode) {
            FitMode.FIT, FitMode.FILL -> VideoTransform(fitMode = mode)
            else -> VideoTransform()
        }
        pushTransform()
    }

    private fun resetTransform() {
        transform = VideoTransform()
        pushTransform()
    }

    // ------------------------------------------------------------------
    // Format / quality / fps
    // ------------------------------------------------------------------

    private fun currentPreset(): CanvasPresets.CanvasPreset {
        val quality = SettingsRepository.get(this).videoQuality
        return if (quality == "auto") {
            CanvasPresets.optionsFor(aspect)[0]
        } else {
            CanvasPresets.presetFor(aspect, quality)
        }
    }

    private fun setAspect(newAspect: CanvasAspect) {
        if (manager.isBroadcasting) {
            toast(R.string.settings_locked_while_live); return
        }
        if (newAspect == aspect) return
        aspect = newAspect
        SettingsRepository.get(this).outputAspect = aspect.name
        // Pan/zoom is relative to the frame — start fresh on a new format,
        // never carry a stale transform matrix across aspect ratios.
        transform = VideoTransform()
        persistTransform()
        refreshFormatButtons()
        refreshPreviewBadges()
        startPreviewIfReady()
        val preset = currentPreset()
        toast(getString(R.string.format_switched, aspect.label, "${preset.width}×${preset.height}"))
    }

    private fun setQuality(quality: String) {
        if (manager.isBroadcasting) {
            toast(R.string.settings_locked_while_live); return
        }
        SettingsRepository.get(this).videoQuality = quality
        refreshQualitySelection()
        refreshPreviewBadges()
        startPreviewIfReady()
    }

    private fun setFps(fps: Int) {
        if (manager.isBroadcasting) {
            toast(R.string.settings_locked_while_live); return
        }
        SettingsRepository.get(this).videoFps = fps
        refreshFpsSelection()
        refreshPreviewBadges()
        startPreviewIfReady()
    }

    private fun refreshFormatButtons() {
        val landscape = aspect == CanvasAspect.LANDSCAPE_16_9
        highlightButton(binding.btnFormatLandscape, landscape)
        highlightButton(binding.btnFormatPortrait, !landscape)
    }

    private fun refreshQualitySelection() {
        val q = SettingsRepository.get(this).videoQuality
        highlightButton(binding.btnQualityAuto, q == "auto")
        highlightButton(binding.btnQuality480, q == "480p")
        highlightButton(binding.btnQuality720, q == "720p")
        highlightButton(binding.btnQuality1080, q == "1080p")
    }

    private fun refreshFpsSelection() {
        val fps = SettingsRepository.get(this).videoFps
        highlightButton(binding.btnFps24, fps == 24)
        highlightButton(binding.btnFps30, fps == 30)
        highlightButton(binding.btnFps60, fps == 60)
    }

    private fun highlightButton(button: android.widget.Button, selected: Boolean) {
        button.backgroundTintList = android.content.res.ColorStateList.valueOf(
            getColor(if (selected) R.color.primary_purple_dark else R.color.card_graphite_high)
        )
    }

    // ------------------------------------------------------------------
    // Video selection
    // ------------------------------------------------------------------

    private fun onVideoPicked(uri: Uri) {
        if (manager.isBroadcasting) {
            toast(R.string.settings_locked_while_live); return
        }
        ioExecutor.execute {
            val info = runCatching {
                com.livevip.app.media.MediaAnalyzer.analyze(this, uri)
            }.getOrNull()
            mainHandler.post {
                if (info == null) {
                    toast(R.string.video_unreadable)
                    return@post
                }
                if (info.videoTrackCount > 1) {
                    toast(getString(R.string.error_start_failed,
                        "video has ${info.videoTrackCount} video tracks — pick a single-track file"))
                    return@post
                }
                selectedVideo = SelectedVideoStore.select(this@HomeActivity, uri, info)
                refreshVideoInfoLine()
                refreshPreviewBadges()
                toast(getString(R.string.video_selected_toast, selectedVideo?.name ?: "?"))
                startPreviewIfReady()
            }
        }
    }

    private fun refreshVideoInfoLine() {
        val video = selectedVideo
        binding.videoInfo.text = video?.let {
            "${it.name} • ${it.infoLabel()}"
        } ?: getString(R.string.no_video_selected)
        binding.previewPlaceholder.visibility = if (video == null) View.VISIBLE else View.GONE
    }

    private fun refreshPreviewBadges() {
        val preset = currentPreset()
        binding.formatBadge.text = if (aspect == CanvasAspect.PORTRAIT_9_16) {
            "9:16 • ${preset.width}×${preset.height}"
        } else {
            "16:9 • ${preset.width}×${preset.height}"
        }
        binding.previewInfo.text = selectedVideo?.infoLabel() ?: ""
    }

    // ------------------------------------------------------------------
    // Preview (== live output). Sizing here, binding in surfaceCreated.
    // ------------------------------------------------------------------

    /**
     * Size the preview surface to the OUTPUT aspect ratio. Sizing ONLY —
     * never binds (the pending layout/resize would destroy the surface and
     * blacken the preview; binding happens in surfaceCreated).
     */
    private fun applyPreviewAspect() {
        val preset = currentPreset()
        val card = binding.previewCard
        val cardW = card.width
        if (cardW <= 0) return
        // Responsive: largest output-aspect rect fitting the card width and
        // ≤ 42% of the screen height. No fixed dp anywhere.
        val maxH = resources.displayMetrics.heightPixels * 0.42f
        val (w, h) = CanvasPreviewMath.fit(
            cardW.toFloat(), maxH, preset.width.toFloat(), preset.height.toFloat()
        )
        if (w <= 0f || h <= 0f) return
        card.layoutParams = card.layoutParams.apply { height = h.toInt() + 2 }
        binding.surfaceView.layoutParams = FrameLayout.LayoutParams(
            w.toInt(), h.toInt(), android.view.Gravity.CENTER
        )
    }

    /**
     * Start (or rebind) the offline preview — called ONLY when the surface
     * exists (surfaceCreated / user action while surface is up).
     * Works fully OFFLINE: no URL, no key, no RTMP, no encoder output.
     */
    private fun startPreviewIfReady() {
        if (!surfaceReady || isFinishing || isDestroyed) return
        val video = selectedVideo
        if (video == null) {
            binding.previewPlaceholder.visibility = View.VISIBLE
            return
        }
        if (manager.isBroadcasting) {
            manager.rebindLivePreview(binding.surfaceView)
            return
        }
        applyPreviewAspect()
        binding.previewPlaceholder.visibility = View.GONE
        val config = StreamConfig.from(SettingsRepository.get(this))
        val error = manager.startTransformPreview(
            this, binding.surfaceView, video.uriParsed(), config, transform
        )
        if (error != null) {
            toast(getString(R.string.preview_failed, error))
        }
    }

    // ------------------------------------------------------------------
    // Audio
    // ------------------------------------------------------------------

    private fun toggleMic() {
        val settings = SettingsRepository.get(this)
        if (!settings.microphoneEnabled) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                setMicInternal(true)
            } else {
                micPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        } else {
            setMicInternal(false)
        }
    }

    private fun setMicInternal(enabled: Boolean) {
        SettingsRepository.get(this).microphoneEnabled = enabled
        manager.setMicrophoneEnabled(enabled) // mic only — video audio untouched
        refreshAudioButtons()
    }

    private fun refreshAudioButtons() {
        val settings = SettingsRepository.get(this)
        binding.btnVideoAudio.text = getString(
            if (settings.videoAudioEnabled) R.string.audio_video_on else R.string.audio_video_off
        )
        binding.btnMicAudio.text = getString(
            if (settings.microphoneEnabled) R.string.audio_mic_on else R.string.audio_mic_off
        )
    }

    // ------------------------------------------------------------------
    // START / STOP — Phase 7: never start RTMP on a dead preview
    // ------------------------------------------------------------------

    private fun startLive() {
        if (manager.isBroadcasting) return
        val video = selectedVideo
        if (video == null) { toast(R.string.error_no_video); return }
        if (!surfaceReady) { toast(R.string.error_preview_not_ready); return }

        val url = binding.inputUrl.text.toString().trim()
        if (url.isEmpty()) { toast(R.string.error_url_empty); return }
        val urlLower = url.lowercase()
        if (!urlLower.startsWith("rtmp://") && !urlLower.startsWith("rtmps://")) {
            toast(R.string.error_url_invalid); return
        }
        val key = binding.inputKey.text.toString().trim()
        if (key.isEmpty()) { toast(R.string.error_key_empty); return }

        // Pre-flight on the IO thread: the saved URI must still be readable
        // AND the decoder must be producing frames (the preview is alive).
        // A black/failed preview NEVER starts an RTMP session.
        ioExecutor.execute {
            val videoStillReadable = runCatching {
                contentResolver.openFileDescriptor(video.uriParsed(), "r")?.use { true } ?: false
            }.getOrDefault(false)
            val flowing = runCatching { manager.decoderFlowing() }.getOrDefault(false)
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                if (!videoStillReadable) {
                    toast(R.string.error_preview_failed)
                    return@post
                }
                if (!flowing) {
                    toast(R.string.error_preview_failed)
                    return@post
                }
                reallyStartLive()
            }
        }
    }

    private fun reallyStartLive() {
        persistStreamInputs()
        val settings = SettingsRepository.get(this)
        val config = StreamConfig.from(settings)
        if (!config.isValidUrl()) { toast(R.string.error_url_invalid); return }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Foreground service owns the session — the stream now survives
        // activity destroy / lock / background until explicit STOP.
        LiveStreamingService.start(this)
        val error = manager.startStream(
            this, config, selectedVideo?.uriParsed(), binding.surfaceView, transform
        )
        if (error != null) {
            toast(getString(R.string.error_start_failed, error))
            LiveStreamingService.stop(this)
        }
    }

    private fun stopLive() {
        // The ONLY path that ends a stream: explicit user press.
        LiveStreamingService.stop(this)
        manager.stopStream()
        LiveBubbleService.stop(this)
    }

    private fun toast(resId: Int) =
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()

    private fun toast(text: String) =
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------------
    // Manager listener — real states, real stats, real health
    // ------------------------------------------------------------------

    override fun onStateChanged(state: StreamState, message: String?) {
        runOnUiThread {
            updateStateUi(state, message)
            when (state) {
                StreamState.OFFLINE, StreamState.ERROR -> {
                    if (message != null && state == StreamState.ERROR) {
                        toast(getString(R.string.error_start_failed, message))
                    }
                    exitLiveUi()
                    // Rebind the offline preview with the same composition.
                    if (surfaceReady) startPreviewIfReady()
                }
                else -> enterLiveUi()
            }
        }
    }

    private fun updateStateUi(state: StreamState, message: String?) {
        val (label, color) = when (state) {
            StreamState.CONNECTING -> R.string.state_connecting to R.color.status_connecting
            StreamState.PUBLISHING -> R.string.state_publishing to R.color.status_connecting
            StreamState.LIVE -> R.string.state_live to R.color.status_live
            StreamState.RECONNECTING -> R.string.state_reconnecting to R.color.status_reconnecting
            StreamState.ERROR -> R.string.state_error to R.color.status_error
            else -> R.string.state_offline to R.color.status_offline
        }
        binding.statusPill.text = getString(label)
        binding.statusPill.setTextColor(getColor(color))
    }

    private fun enterLiveUiIfStreaming() {
        if (manager.isBroadcasting) {
            enterLiveUi()
        } else {
            exitLiveUi()
        }
    }

    private fun enterLiveUi() {
        binding.btnStartLive.visibility = View.GONE
        binding.btnStopLive.visibility = View.VISIBLE
        binding.statsCard.visibility = View.VISIBLE
        setControlsEnabled(false)
    }

    private fun exitLiveUi() {
        binding.btnStartLive.visibility = View.VISIBLE
        binding.btnStopLive.visibility = View.GONE
        binding.statsCard.visibility = View.GONE
        setControlsEnabled(true)
        sessionBannerShown = false
        binding.sessionBanner.visibility = View.GONE
    }

    private fun setControlsEnabled(enabled: Boolean) {
        val views = listOf(
            binding.btnSelectVideo, binding.btnFormatLandscape, binding.btnFormatPortrait,
            binding.btnQualityAuto, binding.btnQuality480, binding.btnQuality720,
            binding.btnQuality1080, binding.btnFps24, binding.btnFps30, binding.btnFps60,
            binding.inputUrl, binding.inputKey
        )
        views.forEach { it.isEnabled = enabled }
    }

    override fun onStatsChanged(stats: StreamStats) {
        runOnUiThread {
            binding.statTime.text = formatDuration(stats.durationSec)
            binding.statBitrate.text = getString(R.string.stats_bitrate_value, stats.bitrateKbps)
            binding.statFps.text = stats.fps.toString()
            binding.statDropped.text = stats.droppedFrames.toString()
            binding.statConnection.text = getString(
                if (stats.congestion) R.string.connection_poor else R.string.connection_good
            )
            binding.statConnection.setTextColor(
                getColor(if (stats.congestion) R.color.warning_amber else R.color.success_green)
            )
            binding.statAvSync.text = formatAvSync(stats.avSyncMs)
            binding.statLoop.text =
                "Loop #${stats.loopCount} • Reconnects ${stats.reconnects}"

            // "Live session detected" — restored UI on an already-running
            // session (activity recreated while live).
            if (manager.state == StreamState.LIVE && stats.loopCount > 0 && !sessionBannerShown) {
                sessionBannerShown = true
                val preset = currentPreset()
                binding.sessionBanner.text = getString(
                    R.string.session_detected_format,
                    "${preset.width}×${preset.height}",
                    stats.loopCount,
                    formatDuration(stats.durationSec)
                )
                binding.sessionBanner.visibility = View.VISIBLE
            }

            renderDiagnostics(stats)
        }
    }

    override fun onHealthChanged(health: LiveStreamingManager.StreamHealth) {
        runOnUiThread {
            lastHealth = health
            val c = health.components
            binding.statComponents.text =
                "Decoder ${c.decoder} • Encoder ${c.encoder} • Muxer ${c.muxer} • " +
                    "RTMP ${c.rtmps} • Ingest ${c.ingest}"
            renderDiagnostics(manager.stats)
        }
    }

    // ------------------------------------------------------------------
    // Developer diagnostics (Phase 14) — real probes only, no keys ever
    // ------------------------------------------------------------------

    private fun renderDiagnostics(stats: StreamStats) {
        if (!SettingsRepository.get(this).debugLogging) return
        if (binding.diagnosticsCard.visibility != View.VISIBLE) {
            binding.diagnosticsCard.visibility = View.VISIBLE
        }
        val c = lastHealth?.components
        binding.diagVideo.text =
            if (selectedVideo != null) "READY" else "ERROR — no video selected"
        binding.diagDecoder.text = c?.decoder ?: "—"
        binding.diagPreview.text = when {
            manager.isBroadcasting && stats.sentVideoFrames > 0 -> "FRAMES RECEIVED (${stats.sentVideoFrames})"
            c?.decoder == "HEALTHY" -> "FRAMES RECEIVED"
            else -> "NO FRAMES"
        }
        binding.diagEncoder.text = buildString {
            append(c?.encoder ?: "—")
            if (manager.encoderFallbackActive()) append(" (854×480 fallback)")
        }
        binding.diagAudio.text = when {
            manager.state == StreamState.LIVE || manager.state == StreamState.PUBLISHING ->
                "SENDING (${stats.sentAudioFrames} frames)"
            else -> c?.muxer ?: "—"
        }
        binding.diagRtmp.text = c?.rtmps ?: "—"
        binding.diagFps.text = "${stats.fps} FPS • dropped ${stats.droppedFrames}"
        binding.diagPackets.text =
            "video ${stats.sentVideoFrames} • audio ${stats.sentAudioFrames}"
        binding.diagBytes.text = formatBytes(stats.bytesSent)
        binding.diagError.text = stats.lastError ?: "none"
    }

    // ------------------------------------------------------------------
    // Formatting helpers
    // ------------------------------------------------------------------

    private fun formatDuration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }

    private fun formatAvSync(ms: Long): String = when {
        abs(ms) <= 1 -> "±0 ms"
        ms > 0 -> "+${ms} ms"
        else -> "${ms} ms"
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> String.format(Locale.US, "%.2f GB", bytes / 1e9)
        bytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1e6)
        bytes >= 1_000 -> String.format(Locale.US, "%.1f KB", bytes / 1e3)
        else -> "$bytes B"
    }

    private companion object {
        const val MIN_SCALE = 0.5f
        const val MAX_SCALE = 5f
    }
}
