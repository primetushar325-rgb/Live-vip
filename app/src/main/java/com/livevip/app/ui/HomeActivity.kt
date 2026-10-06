package com.livevip.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.livevip.app.R
import com.livevip.app.core.ContentFit
import com.livevip.app.core.LiveCompositionState
import com.livevip.app.core.OutputFormat
import com.livevip.app.core.OutputPresets
import com.livevip.app.core.PreviewMath
import com.livevip.app.databinding.ActivityHomeBinding
import com.livevip.app.engine.BitratePolicy
import com.livevip.app.engine.EngineConfig
import com.livevip.app.engine.LiveEngine
import com.livevip.app.engine.LiveSnapshot
import com.livevip.app.engine.LiveState
import com.livevip.app.media.MediaAnalyzer
import com.livevip.app.service.LiveBubbleService
import com.livevip.app.service.LiveService
import com.livevip.app.store.LibraryStore
import com.livevip.app.store.LibraryVideo
import com.livevip.app.store.SavedLive
import com.livevip.app.store.SavedLiveStore
import com.livevip.app.store.SecureStore
import com.livevip.app.store.SettingsStore
import com.livevip.app.streaming.CapabilityDetector
import com.livevip.app.util.NetworkMonitor
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * THE ONE SCREEN — OBS-style mobile broadcaster.
 *
 * PREVIEW SURFACE LIFECYCLE (the black-preview lesson, enforced forever):
 * RootEncoder's `startPreview(surfaceView)` binds `holder.surface` ONE-SHOT
 * (no holder callbacks, throws on invalid surface, throws while
 * isOnPreview). Binding therefore happens ONLY from `surfaceCreated`;
 * layout changes only re-size; `onPause` stops the preview when not live;
 * while LIVE, `surfaceCreated` rebinds through `LiveEngine.rebindLivePreview`
 * (encoder/RTMP untouched).
 *
 * ONE COMPOSITION: every gesture/format change goes through
 * [LiveCompositionState] → [LiveEngine.updateComposition] — the SAME state
 * drives the preview and the encoder.
 */
class HomeActivity : AppCompatActivity(), LiveEngine.Listener {

    private lateinit var binding: ActivityHomeBinding
    private val engine get() = LiveEngine

    // ---- Current UI state ----
    private var selectedVideo: LibraryVideo? = null
    private var format: OutputFormat = OutputFormat.LANDSCAPE_16_9
    private var composition: LiveCompositionState? = null
    private var networkNote: String? = null

    // ---- Preview surface lifecycle (THE gate) ----
    private var surfaceReady = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private var networkMonitor: NetworkMonitor? = null

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

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) setSource(LiveEngine.Mode.CAMERA) else {
                refreshSourceButtons()
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
        networkMonitor = NetworkMonitor(this)

        applyWindowInsets()
        loadSaved()
        wireUI()
        engine.addListener(this)
        updateStateUi(engine.state)
        enterLiveUiIfStreaming()
        if (SettingsStore.get(this).debugDiagnostics) {
            binding.diagnosticsCard.visibility = View.VISIBLE
        }
    }

    override fun onResume() {
        super.onResume()
        // Rebind after onPause stopped the preview (not live) or after a
        // surface recreation. Never while live — surfaceCreated handles it.
        if (!engine.isBroadcasting && !engine.isOnPreview) {
            startPreviewIfReady()
        }
    }

    override fun onPause() {
        // PROVEN pattern: releasing the preview while paused resets the
        // engine's isOnPreview flag, so the next startPreview can never hit
        // the "Preview already started" exception that blackens the screen.
        // While LIVE the preview is never touched — the stream must survive.
        if (!engine.isBroadcasting) {
            engine.stopPreview()
        }
        persistTransform()
        super.onPause()
    }

    override fun onDestroy() {
        engine.removeListener(this)
        ioExecutor.shutdown()
        if (isFinishing && !engine.isBroadcasting) {
            engine.stopPreview()
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
        if (engine.isBroadcasting &&
            SettingsStore.get(this).floatingBubbleEnabled &&
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
    // Saved state
    // ------------------------------------------------------------------

    private fun loadSaved() {
        val settings = SettingsStore.get(this)
        format = OutputFormat.from(settings.outputFormat)
        selectedVideo = currentSavedVideo()
        composition = restoreComposition(settings.compositionJson)

        binding.inputUrl.setText(SecureStore.serverUrl(this))
        binding.inputKey.setText(SecureStore.streamKey(this))

        refreshSourceButtons()
        refreshFormatButtons()
        refreshQualitySelection()
        refreshFpsSelection()
        refreshAudioButtons()
        refreshVideoInfoLine()
        refreshPreviewBadges()
        renderSavedLives()
    }

    private fun currentSavedVideo(): LibraryVideo? {
        val settings = SettingsStore.get(this)
        val uri = settings.currentVideoUri
        if (uri.isBlank()) return null
        return LibraryStore.byUri(this, Uri.parse(uri)) ?: run {
            // Reference expired/removed — honest clear.
            settings.currentVideoUri = ""
            settings.currentVideoJson = ""
            null
        }
    }

    private fun restoreComposition(json: String): LiveCompositionState? {
        if (json.isBlank()) return null
        return runCatching {
            LiveCompositionState.fromJson(org.json.JSONObject(json))
        }.getOrNull()
    }

    private fun persistTransform() {
        composition?.let {
            SettingsStore.get(this).compositionJson = it.toJson().toString()
        }
    }

    private fun persistStreamInputs() {
        SecureStore.setServerUrl(this, binding.inputUrl.text.toString())
        SecureStore.setStreamKey(this, binding.inputKey.text.toString())
        SettingsStore.get(this).outputFormat = format.name
    }

    // ------------------------------------------------------------------
    // UI wiring
    // ------------------------------------------------------------------

    private fun wireUI() {
        // Preview surface lifecycle — bind ONLY when the surface exists.
        binding.surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceReady = true
                if (engine.isBroadcasting) {
                    // Activity recreated while LIVE: rebind the preview only.
                    engine.rebindLivePreview(binding.surfaceView)
                } else {
                    startPreviewIfReady()
                }
            }

            override fun surfaceChanged(
                holder: SurfaceHolder, format: Int, width: Int, height: Int
            ) = Unit // same surface, new size — the GL pipeline keeps drawing

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
            }
        })

        // Layout changes only RE-SIZE the surface; binding happens
        // exclusively in surfaceCreated — never here.
        binding.previewCard.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            applyPreviewAspect()
        }

        // Developer diagnostics toggle — long-press the app title.
        binding.appTitle.setOnLongClickListener {
            val settings = SettingsStore.get(this)
            settings.debugDiagnostics = !settings.debugDiagnostics
            binding.diagnosticsCard.visibility =
                if (settings.debugDiagnostics) View.VISIBLE else View.GONE
            toast(if (settings.debugDiagnostics) "Diagnostics ON" else "Diagnostics OFF")
            true
        }

        binding.sourceVideo.setOnClickListener { setSource(LiveEngine.Mode.VIDEO) }
        binding.sourceCamera.setOnClickListener {
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                setSource(LiveEngine.Mode.CAMERA)
            } else {
                cameraPermission.launch(Manifest.permission.CAMERA)
            }
        }

        binding.btnSelectVideo.setOnClickListener { pickVideo.launch(arrayOf("video/*")) }
        binding.btnOpenLibrary.setOnClickListener {
            startActivity(android.content.Intent(this, LibraryActivity::class.java))
        }

        binding.btnFormatLandscape.setOnClickListener { setFormat(OutputFormat.LANDSCAPE_16_9) }
        binding.btnFormatPortrait.setOnClickListener { setFormat(OutputFormat.VERTICAL_9_16) }

        binding.btnQualityAuto.setOnClickListener { setQuality("auto") }
        binding.btnQuality480.setOnClickListener { setQuality("480p") }
        binding.btnQuality720.setOnClickListener { setQuality("720p") }
        binding.btnQuality1080.setOnClickListener { setQuality("1080p") }

        binding.btnFps24.setOnClickListener { setFps(24) }
        binding.btnFps30.setOnClickListener { setFps(30) }
        binding.btnFps60.setOnClickListener { setFps(60) }

        binding.btnFit.setOnClickListener { setFit(ContentFit.FIT) }
        binding.btnFill.setOnClickListener { setFit(ContentFit.FILL) }
        binding.btnResetTransform.setOnClickListener { resetTransform() }

        binding.btnVideoAudio.setOnClickListener {
            val settings = SettingsStore.get(this)
            val on = !settings.videoAudioEnabled
            settings.videoAudioEnabled = on
            engine.setVideoAudioEnabled(on) // mic state never mutes video audio
            refreshAudioButtons()
        }
        binding.btnMicAudio.setOnClickListener { toggleMic() }
        binding.btnMuteMicLive.setOnClickListener { toggleMic() }

        binding.btnSaveCurrent.setOnClickListener { showSaveLiveDialog() }

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

        val scaleDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val current = composition ?: return true
                    composition = current.copy(
                        scale = (current.scale * detector.scaleFactor)
                            .coerceIn(MIN_SCALE, MAX_SCALE),
                        fit = ContentFit.CUSTOM
                    )
                    pushComposition()
                    return true
                }
            }
        )

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
                        val current = composition
                        if (current != null && (abs(dx) > 0f || abs(dy) > 0f)) {
                            composition = current.copy(
                                translationX = (current.translationX + dx).coerceIn(-1.5f, 1.5f),
                                translationY = (current.translationY + dy).coerceIn(-1.5f, 1.5f),
                                fit = ContentFit.CUSTOM
                            )
                            pushComposition()
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

    /** Apply the composition to the SHARED preview+encoder pipeline. */
    private fun pushComposition() {
        val state = composition ?: return
        if (engine.currentComposition() != null) {
            engine.updateComposition(state)
        }
    }

    private fun setFit(fit: ContentFit) {
        val current = composition ?: return
        composition = current.copy(
            fit = fit,
            scale = 1f,
            translationX = 0f,
            translationY = 0f
        )
        pushComposition()
    }

    private fun resetTransform() {
        val current = composition ?: return
        composition = current.copy(
            scale = 1f,
            translationX = 0f,
            translationY = 0f,
            fit = ContentFit.FIT
        )
        pushComposition()
    }

    // ------------------------------------------------------------------
    // Source / format / quality / fps
    // ------------------------------------------------------------------

    private fun setSource(mode: LiveEngine.Mode) {
        if (engine.isBroadcasting) {
            toast(R.string.settings_locked_while_live); return
        }
        engine.setMode(mode)
        refreshSourceButtons()
        composition = null // fresh composition for the new source
        startPreviewIfReady()
    }

    private fun setFormat(newFormat: OutputFormat) {
        if (engine.isBroadcasting) {
            toast(R.string.settings_locked_while_live); return
        }
        if (newFormat == format) return
        format = newFormat
        SettingsStore.get(this).outputFormat = format.name
        // RECALCULATED composition — default transform, never a stale matrix
        // from the previous aspect ratio (the video stays visible).
        composition = rebuildComposition(resetTransform = true)
        persistTransform()
        refreshFormatButtons()
        refreshPreviewBadges()
        startPreviewIfReady()
        val preset = currentPreset()
        toast(getString(R.string.format_switched, format.label, preset.label()))
    }

    private fun setQuality(quality: String) {
        if (engine.isBroadcasting) {
            toast(R.string.settings_locked_while_live); return
        }
        SettingsStore.get(this).quality = quality
        refreshQualitySelection()
        refreshPreviewBadges()
        startPreviewIfReady()
    }

    private fun setFps(fps: Int) {
        if (engine.isBroadcasting) {
            toast(R.string.settings_locked_while_live); return
        }
        SettingsStore.get(this).fps = fps
        refreshFpsSelection()
        refreshPreviewBadges()
        startPreviewIfReady()
    }

    /**
     * The composition for the CURRENT format + selected video.
     * resetTransform = true keeps only the dimensions (format switch / new
     * video); false preserves the user's zoom/pan (settings/fps changes).
     */
    private fun rebuildComposition(resetTransform: Boolean): LiveCompositionState {
        val preset = currentPreset()
        val srcW = selectedVideo?.width ?: preset.width
        val srcH = selectedVideo?.height ?: preset.height
        val old = composition
        return if (resetTransform || old == null) {
            LiveCompositionState(
                outputWidth = preset.width,
                outputHeight = preset.height,
                sourceWidth = srcW,
                sourceHeight = srcH
            )
        } else {
            old.copy(
                outputWidth = preset.width,
                outputHeight = preset.height,
                sourceWidth = srcW,
                sourceHeight = srcH
            )
        }
    }

    private fun currentPreset(): OutputPresets.Preset {
        val quality = SettingsStore.get(this).quality
        return if (quality == "auto") {
            OutputPresets.autoPreset(
                format,
                CapabilityDetector.videoEncoderCaps(),
                networkMonitor?.estimatedUploadKbps()
            )
        } else {
            OutputPresets.presetFor(format, quality)
        }
    }

    private fun refreshSourceButtons() {
        val video = engine.mode == LiveEngine.Mode.VIDEO
        highlight(binding.sourceVideo, video)
        highlight(binding.sourceCamera, !video)
    }

    private fun refreshFormatButtons() {
        highlight(binding.btnFormatLandscape, format == OutputFormat.LANDSCAPE_16_9)
        highlight(binding.btnFormatPortrait, format == OutputFormat.VERTICAL_9_16)
    }

    private fun refreshQualitySelection() {
        val q = SettingsStore.get(this).quality
        highlight(binding.btnQualityAuto, q == "auto")
        highlight(binding.btnQuality480, q == "480p")
        highlight(binding.btnQuality720, q == "720p")
        highlight(binding.btnQuality1080, q == "1080p")
    }

    private fun refreshFpsSelection() {
        val fps = SettingsStore.get(this).fps
        highlight(binding.btnFps24, fps == 24)
        highlight(binding.btnFps30, fps == 30)
        highlight(binding.btnFps60, fps == 60)
    }

    private fun highlight(button: Button, selected: Boolean) {
        button.backgroundTintList = android.content.res.ColorStateList.valueOf(
            getColor(if (selected) R.color.primary_purple_dark else R.color.card_graphite_high)
        )
    }

    // ------------------------------------------------------------------
    // Video selection
    // ------------------------------------------------------------------

    private fun onVideoPicked(uri: Uri) {
        if (engine.isBroadcasting) {
            toast(R.string.settings_locked_while_live); return
        }
        ioExecutor.execute {
            val info = runCatching { MediaAnalyzer.analyze(this, uri) }.getOrNull()
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                if (info == null) {
                    toast(R.string.video_unreadable)
                    return@post
                }
                if (info.videoTrackCount > 1) {
                    toast(
                        getString(
                            R.string.error_start_failed,
                            "video has ${info.videoTrackCount} video tracks — pick a single-track file"
                        )
                    )
                    return@post
                }
                val isNewVideo = selectedVideo?.uri != uri.toString()
                val video = LibraryStore.add(this@HomeActivity, uri, info)
                selectedVideo = video
                val settings = SettingsStore.get(this@HomeActivity)
                settings.currentVideoUri = video.uri
                settings.currentVideoJson = video.toJson().toString()
                if (engine.mode != LiveEngine.Mode.VIDEO) {
                    engine.setMode(LiveEngine.Mode.VIDEO)
                    refreshSourceButtons()
                }
                if (isNewVideo) composition = null // fresh composition
                refreshVideoInfoLine()
                refreshPreviewBadges()
                toast(getString(R.string.video_selected_toast, video.name))
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
        binding.formatBadge.text =
            "${if (format == OutputFormat.VERTICAL_9_16) "9:16" else "16:9"} • ${preset.label()}"
        binding.previewInfo.text = selectedVideo?.infoLabel() ?: ""
    }

    // ------------------------------------------------------------------
    // Preview (== live output). Sizing here, binding in surfaceCreated.
    // ------------------------------------------------------------------

    /**
     * Size the preview surface to the OUTPUT aspect ratio. Sizing ONLY —
     * never binds (a pending resize would destroy the bound surface and
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
        val (w, h) = PreviewMath.fit(
            cardW.toFloat(), maxH, preset.width.toFloat(), preset.height.toFloat()
        )
        if (w <= 0f || h <= 0f) return

        // Sizing ONLY — never binds. Apply only when actually different so
        // this cannot loop with the layout-change listener.
        val newCardHeight = h.toInt() + 2
        if (card.layoutParams.height != newCardHeight) {
            card.layoutParams.height = newCardHeight
            card.requestLayout()
        }
        val surfaceParams = binding.surfaceView.layoutParams
        if (surfaceParams.width != w.toInt() || surfaceParams.height != h.toInt()) {
            surfaceParams.width = w.toInt()
            surfaceParams.height = h.toInt()
            surfaceParams.gravity = Gravity.CENTER
            binding.surfaceView.layoutParams = surfaceParams
        }
    }

    /**
     * Start (or rebind) the OFFLINE preview — called ONLY when the surface
     * exists. Works with NO url, NO key, NO RTMP, NO internet: the decoder
     * + GL compositor + surface alone produce the visible video.
     */
    private fun startPreviewIfReady() {
        if (!surfaceReady || isFinishing || isDestroyed) return
        if (engine.isBroadcasting) {
            engine.rebindLivePreview(binding.surfaceView)
            return
        }
        if (engine.mode == LiveEngine.Mode.VIDEO && selectedVideo == null) {
            binding.previewPlaceholder.visibility = View.VISIBLE
            return
        }
        binding.previewPlaceholder.visibility = View.GONE
        applyPreviewAspect()
        val state = rebuildComposition(resetTransform = false)
        composition = state
        val config = buildConfig()
        val error = engine.startPreview(
            this,
            binding.surfaceView,
            selectedVideo?.uriParsed(),
            config,
            state
        )
        if (error != null) {
            toast(getString(R.string.preview_failed, error))
        }
    }

    // ------------------------------------------------------------------
    // Engine config
    // ------------------------------------------------------------------

    private fun buildConfig(): EngineConfig {
        val settings = SettingsStore.get(this)
        val preset = currentPreset()
        val fps = settings.fps
        val recommended = BitratePolicy.recommendedKbps(preset.width, preset.height, fps)
        val (bitrate, note) = BitratePolicy.clampToNetwork(
            recommended, networkMonitor?.estimatedUploadKbps()
        )
        networkNote = note
        return EngineConfig(
            url = binding.inputUrl.text.toString(),
            key = binding.inputKey.text.toString(),
            videoWidth = preset.width,
            videoHeight = preset.height,
            fps = fps,
            videoBitrateKbps = bitrate,
            keyframeIntervalSec = 2, // YouTube-recommended ~2 s
            audioBitrateKbps = 128,
            sampleRate = settings.audioSampleRate,
            stereo = settings.audioStereo,
            echoCanceler = settings.echoCanceler,
            noiseSuppressor = settings.noiseSuppressor,
            autoReconnect = settings.autoReconnect,
            maxReconnectAttempts = settings.maxReconnectAttempts
        )
    }

    // ------------------------------------------------------------------
    // Audio
    // ------------------------------------------------------------------

    private fun toggleMic() {
        val settings = SettingsStore.get(this)
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
        SettingsStore.get(this).microphoneEnabled = enabled
        engine.setMicrophoneEnabled(enabled) // mic only — video audio untouched
        refreshAudioButtons()
    }

    private fun refreshAudioButtons() {
        val settings = SettingsStore.get(this)
        binding.btnVideoAudio.text = getString(
            if (settings.videoAudioEnabled) R.string.audio_video_on else R.string.audio_video_off
        )
        binding.btnMicAudio.text = getString(
            if (settings.microphoneEnabled) R.string.audio_mic_on else R.string.audio_mic_off
        )
        binding.btnMuteMicLive.text = getString(
            if (settings.microphoneEnabled) R.string.action_unmute_mic else R.string.action_mute_mic
        )
    }

    // ------------------------------------------------------------------
    // SAVED LIVES
    // ------------------------------------------------------------------

    private fun renderSavedLives() {
        val container = binding.savedLivesList
        container.removeAllViews()
        val lives = SavedLiveStore.all(this).sortedByDescending { it.id }
        if (lives.isEmpty()) {
            container.addView(
                TextView(this).apply {
                    text = getString(R.string.saved_lives_empty)
                    setTextColor(getColor(R.color.text_secondary))
                    textSize = 12f
                    setPadding(0, 8, 0, 8)
                }
            )
            return
        }
        lives.forEach { live ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 6, 0, 6)
            }
            val label = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = "${live.name}\n${savedLiveSummary(live)}"
                setTextColor(getColor(R.color.text_primary))
                textSize = 13f
            }
            val load = Button(this).apply {
                text = getString(R.string.action_load)
                minHeight = 44
                textSize = 12f
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    getColor(R.color.card_graphite_high)
                )
                setOnClickListener { loadSavedLive(live) }
            }
            val delete = Button(this).apply {
                text = getString(R.string.action_delete)
                minHeight = 44
                textSize = 12f
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    getColor(R.color.card_graphite_high)
                )
                setOnClickListener {
                    SavedLiveStore.delete(this@HomeActivity, live.id)
                    renderSavedLives()
                    toast(getString(R.string.saved_live_deleted, live.name))
                }
            }
            row.addView(label)
            row.addView(load)
            row.addView(delete)
            container.addView(row)
        }
    }

    private fun savedLiveSummary(live: SavedLive): String {
        val f = OutputFormat.from(live.outputFormat)
        val quality = if (live.quality == "auto") "AUTO" else live.quality
        return "${f.label} • $quality • ${live.fps} FPS • ${live.videoName.ifBlank { "no video" }}"
    }

    private fun showSaveLiveDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.saved_live_name_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.action_save_current)
            .setView(input)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isBlank()) {
                    toast(R.string.saved_live_name_required)
                    return@setPositiveButton
                }
                saveCurrentLive(name)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun saveCurrentLive(name: String) {
        val settings = SettingsStore.get(this)
        val preset = currentPreset()
        val live = SavedLive(
            id = 0L,
            name = name,
            videoUri = selectedVideo?.uri ?: "",
            videoName = selectedVideo?.name ?: "",
            outputFormat = format.name,
            quality = settings.quality,
            fps = settings.fps,
            compositionJson = composition?.toJson()?.toString() ?: "",
            serverUrl = binding.inputUrl.text.toString().trim(),
            videoAudio = settings.videoAudioEnabled,
            mic = settings.microphoneEnabled
        )
        SavedLiveStore.save(this, live, binding.inputKey.text.toString())
        renderSavedLives()
        toast(getString(R.string.saved_live_saved, name, preset.label()))
    }

    private fun loadSavedLive(live: SavedLive) {
        if (engine.isBroadcasting) {
            toast(R.string.settings_locked_while_live); return
        }
        val settings = SettingsStore.get(this)
        format = OutputFormat.from(live.outputFormat)
        settings.outputFormat = format.name
        settings.quality = live.quality
        settings.fps = live.fps
        settings.videoAudioEnabled = live.videoAudio
        settings.microphoneEnabled = live.mic

        SecureStore.setServerUrl(this, live.serverUrl)
        SecureStore.setStreamKey(this, SavedLiveStore.keyFor(this, live))
        binding.inputUrl.setText(live.serverUrl)
        binding.inputKey.setText(SavedLiveStore.keyFor(this, live))

        if (live.videoUri.isNotBlank()) {
            val libraryVideo = LibraryStore.byUri(this, Uri.parse(live.videoUri))
            if (libraryVideo != null) {
                selectedVideo = libraryVideo
                settings.currentVideoUri = libraryVideo.uri
                settings.currentVideoJson = libraryVideo.toJson().toString()
                if (engine.mode != LiveEngine.Mode.VIDEO) {
                    engine.setMode(LiveEngine.Mode.VIDEO)
                }
            } else {
                toast(R.string.saved_live_video_missing)
            }
        }

        composition = if (live.compositionJson.isNotBlank()) {
            restoreComposition(live.compositionJson) ?: rebuildComposition(true)
        } else {
            rebuildComposition(true)
        }

        engine.setVideoAudioEnabled(live.videoAudio)
        engine.setMicrophoneEnabled(live.mic)

        refreshSourceButtons()
        refreshFormatButtons()
        refreshQualitySelection()
        refreshFpsSelection()
        refreshAudioButtons()
        refreshVideoInfoLine()
        refreshPreviewBadges()
        startPreviewIfReady()
        toast(getString(R.string.saved_live_loaded, live.name))
    }

    // ------------------------------------------------------------------
    // START / STOP — never start RTMP on a dead preview
    // ------------------------------------------------------------------

    private fun startLive() {
        if (engine.isBroadcasting) return
        if (!surfaceReady) { toast(R.string.error_preview_not_ready); return }

        val url = binding.inputUrl.text.toString().trim()
        if (url.isEmpty()) { toast(R.string.error_url_empty); return }
        val urlLower = url.lowercase()
        if (!urlLower.startsWith("rtmp://") && !urlLower.startsWith("rtmps://")) {
            toast(R.string.error_url_invalid); return
        }
        val key = binding.inputKey.text.toString().trim()
        if (key.isEmpty()) { toast(R.string.error_key_empty); return }

        if (engine.mode == LiveEngine.Mode.VIDEO && selectedVideo == null) {
            toast(R.string.error_no_video); return
        }

        val video = selectedVideo
        // Pre-flight on the IO thread: the saved URI must still be readable
        // AND the decoder must be producing frames (preview alive). A
        // black/failed preview NEVER starts an RTMP session.
        ioExecutor.execute {
            val readable = video == null || runCatching {
                contentResolver.openFileDescriptor(video.uriParsed(), "r")?.use { true } ?: false
            }.getOrDefault(false)
            val flowing = runCatching { engine.decoderFlowing() }.getOrDefault(false)
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                if (!readable) { toast(R.string.error_preview_failed); return@post }
                if (!flowing) { toast(R.string.error_preview_failed); return@post }
                reallyStartLive()
            }
        }
    }

    private fun reallyStartLive() {
        persistStreamInputs()
        val config = buildConfig()
        if (!config.isValidUrl()) { toast(R.string.error_url_invalid); return }

        networkNote?.let { toast(it) }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Foreground service owns the session — the stream now survives
        // activity destroy / lock / background until explicit STOP.
        LiveService.start(this)
        val state = composition ?: rebuildComposition(true)
        composition = state
        val error = engine.startStream(
            this,
            config,
            selectedVideo?.uriParsed(),
            binding.surfaceView,
            state
        )
        if (error != null) {
            toast(getString(R.string.error_start_failed, error))
            LiveService.stop(this)
        }
    }

    private fun stopLive() {
        // The ONLY path that ends a stream: explicit user press.
        LiveService.stop(this)
        engine.stopStream()
        LiveBubbleService.stop(this)
    }

    private fun toast(resId: Int) =
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()

    private fun toast(text: String) =
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------------
    // Engine listener — real states, real metrics only
    // ------------------------------------------------------------------

    override fun onLiveStateChanged(state: LiveState, message: String?) {
        runOnUiThread {
            updateStateUi(state)
            when (state) {
                LiveState.OFFLINE, LiveState.ERROR, LiveState.STOPPED -> {
                    if (message != null && state == LiveState.ERROR) {
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

    private fun updateStateUi(state: LiveState) {
        val (label, color) = when (state) {
            LiveState.CONNECTING -> R.string.state_connecting to R.color.status_connecting
            LiveState.CONNECTED -> R.string.state_connected to R.color.status_connecting
            LiveState.SENDING -> R.string.state_sending to R.color.status_connecting
            LiveState.STREAMING -> R.string.state_streaming to R.color.status_live
            LiveState.RECONNECTING -> R.string.state_reconnecting to R.color.status_reconnecting
            LiveState.ERROR -> R.string.state_error to R.color.status_error
            LiveState.STOPPED -> R.string.state_stopped to R.color.status_offline
            else -> R.string.state_offline to R.color.status_offline
        }
        binding.statusPill.text = getString(label)
        binding.statusPill.setTextColor(getColor(color))
    }

    private fun enterLiveUiIfStreaming() {
        if (engine.isBroadcasting) enterLiveUi() else exitLiveUi()
    }

    private fun enterLiveUi() {
        binding.btnStartLive.visibility = View.GONE
        binding.liveControls.visibility = View.VISIBLE
        binding.statsCard.visibility = View.VISIBLE
        setControlsEnabled(false)
    }

    private fun exitLiveUi() {
        binding.btnStartLive.visibility = View.VISIBLE
        binding.liveControls.visibility = View.GONE
        binding.statsCard.visibility = View.GONE
        setControlsEnabled(true)
    }

    private fun setControlsEnabled(enabled: Boolean) {
        val views = listOf(
            binding.sourceVideo, binding.sourceCamera,
            binding.btnSelectVideo, binding.btnFormatLandscape, binding.btnFormatPortrait,
            binding.btnQualityAuto, binding.btnQuality480, binding.btnQuality720,
            binding.btnQuality1080, binding.btnFps24, binding.btnFps30, binding.btnFps60,
            binding.inputUrl, binding.inputKey, binding.btnSaveCurrent
        )
        views.forEach { it.isEnabled = enabled }
    }

    override fun onLiveSnapshot(snap: LiveSnapshot) {
        runOnUiThread {
            binding.statTime.text = formatDuration(snap.durationSec)
            binding.statBitrate.text =
                if (snap.bitrateKbps > 0) formatBitrate(snap.bitrateKbps) else "N/A"
            binding.statFps.text = if (snap.fps > 0) snap.fps.toString() else "N/A"
            binding.statDropped.text = snap.droppedFrames.toString()
            binding.statConnection.text = getString(
                if (snap.congestion) R.string.connection_poor else R.string.connection_good
            )
            binding.statConnection.setTextColor(
                getColor(if (snap.congestion) R.color.warning_amber else R.color.success_green)
            )
            binding.statAvSync.text = snap.avSyncMs?.let { formatAvSync(it) } ?: "N/A"
            binding.statLoop.text =
                "Loop #${snap.loopCount} • Reconnects ${snap.reconnects}"
            val c = snap.components
            binding.statComponents.text =
                "Decoder ${c.decoder} • Encoder ${c.encoder} • Muxer ${c.muxer} • " +
                    "RTMP ${c.rtmp} • Ingest ${c.ingest}"

            // Honest YouTube hint: we are SENDING/STREAMING, but YouTube only
            // shows the broadcast publicly when it accepts it (auto-start).
            binding.youtubeHint.visibility = when {
                engine.isYoutubeDestination() &&
                    (snap.state == LiveState.SENDING || snap.state == LiveState.STREAMING) ->
                    View.VISIBLE
                else -> View.GONE
            }

            renderDiagnostics(snap)
        }
    }

    // ------------------------------------------------------------------
    // Developer diagnostics — real probes only, no credentials ever
    // ------------------------------------------------------------------

    private fun renderDiagnostics(snap: LiveSnapshot) {
        if (!SettingsStore.get(this).debugDiagnostics) return
        if (binding.diagnosticsCard.visibility != View.VISIBLE) {
            binding.diagnosticsCard.visibility = View.VISIBLE
        }
        val c = snap.components
        binding.diagVideo.text =
            "VIDEO SOURCE: " + if (selectedVideo != null) "READY" else "ERROR — no video selected"
        binding.diagDecoder.text = "DECODER: ${c.decoder}"
        binding.diagPreview.text = "PREVIEW: ${c.preview}"
        binding.diagEncoder.text = buildString {
            append("ENCODER: ").append(c.encoder)
            if (engine.encoderFallbackActive()) append(" (854×480 fallback)")
        }
        binding.diagAudio.text = "AUDIO: ${c.muxer}"
        binding.diagRtmp.text = "RTMP: ${c.rtmp}"
        binding.diagVideoFrames.text = "ENCODED VIDEO FRAMES: ${snap.sentVideoFrames}"
        binding.diagAudioFrames.text = "ENCODED AUDIO FRAMES: ${snap.sentAudioFrames}"
        binding.diagPackets.text =
            "PACKETS SENT: video ${snap.sentVideoFrames} • audio ${snap.sentAudioFrames}"
        binding.diagBytes.text = "BYTES SENT: ${formatBytes(snap.bytesSent)}"
        binding.diagRtmpError.text = "LAST RTMP ERROR: ${snap.lastError ?: "none"}"
        binding.diagEncoderError.text =
            "LAST ENCODER ERROR: ${engine.lastEncoderError() ?: "none"}"
        binding.diagDecoderError.text =
            "LAST DECODER ERROR: ${engine.lastDecoderError() ?: "none"}"
        binding.diagLoop.text = "LOOP COUNT: ${snap.loopCount}"
        binding.diagReconnects.text = "RECONNECT COUNT: ${snap.reconnects}"
        binding.diagAvOffset.text =
            "A/V OFFSET: ${snap.avSyncMs?.let { formatAvSync(it) } ?: "N/A"}"
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

    private fun formatBitrate(kbps: Long): String =
        if (kbps >= 1000) String.format(Locale.US, "%.1f Mbps", kbps / 1000f)
        else "$kbps kbps"

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
