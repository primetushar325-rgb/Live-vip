package com.livevip.app.ui

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.SurfaceHolder
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.livevip.app.R
import com.livevip.app.data.ProjectRepository
import com.livevip.app.data.SettingsRepository
import com.livevip.app.databinding.ActivityHomeBinding
import com.livevip.app.databinding.DialogAudioMixerBinding
import com.livevip.app.databinding.DialogNetworkCheckBinding
import com.livevip.app.databinding.ItemDestStatusBinding
import com.livevip.app.databinding.ItemRecentProjectBinding
import com.livevip.app.data.Project
import com.livevip.app.media.MediaAnalyzer
import com.livevip.app.data.ThumbnailCache
import com.livevip.app.media.VideoRepository
import com.livevip.app.overlay.OverlayConfig
import com.livevip.app.relay.RelaySessionClient
import com.livevip.app.service.LiveBubbleService
import com.livevip.app.service.LiveStreamingService
import com.livevip.app.streaming.BroadcastMode
import com.livevip.app.streaming.BroadcastPlan
import com.livevip.app.streaming.LiveStreamingManager
import com.livevip.app.streaming.LiveStreamingManager.Mode
import com.livevip.app.streaming.NetworkMath
import com.livevip.app.streaming.StreamConfig
import com.livevip.app.streaming.StreamState
import com.livevip.app.streaming.StreamStats
import com.livevip.app.util.BatterySafety
import com.livevip.app.util.NetworkMonitor
import java.util.Locale
import java.util.concurrent.Executors

/**
 * LIVE VIP — video-first home + live dashboard.
 *
 * UI ONLY — the broadcast pipeline lives in [LiveStreamingManager] and runs
 * under [LiveStreamingService] while live. Activity destruction/recreation
 * NEVER stops a running stream; this screen re-attaches as a listener.
 *
 * VIDEO LIVE is the primary source; CAMERA is secondary.
 */
class HomeActivity : AppCompatActivity(), LiveStreamingManager.Listener {

    private lateinit var binding: ActivityHomeBinding
    private lateinit var settings: SettingsRepository
    private lateinit var network: NetworkMonitor
    private lateinit var videos: VideoRepository
    private lateinit var repo: ProjectRepository
    private lateinit var thumbs: ThumbnailCache

    private val bgExecutor = Executors.newSingleThreadExecutor()
    private val uiHandler = Handler(Looper.getMainLooper())
    private var surfaceReady = false
    private var pendingStartAfterPermission = false
    private var livePulse: ObjectAnimator? = null
    private var currentProject: Project? = null
    private var activeLevelTicker: Runnable? = null

    // ------------------------------------------------------------------
    // Launchers
    // ------------------------------------------------------------------

    private val videoPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importVideo(uri)
        }

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) refreshPreview() else showError(getString(R.string.camera_permission_denied))
        }

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                LiveStreamingManager.setMicrophoneEnabled(true)
                updateMicUi()
            } else showError(getString(R.string.mic_permission_denied))
        }

    private val streamPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (pendingStartAfterPermission) {
                pendingStartAfterPermission = false
                val camNeeded = LiveStreamingManager.mode == Mode.CAMERA
                val camGranted = !camNeeded || hasPermission(Manifest.permission.CAMERA)
                if (!camGranted) showError(getString(R.string.camera_permission_denied))
                else continueStart()
            }
        }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = SettingsRepository.get(this)
        network = NetworkMonitor(this)
        videos = VideoRepository.get(this)
        repo = ProjectRepository.get(this)
        thumbs = ThumbnailCache.get(this)

        com.livevip.app.streaming.CapabilityDetector.warmAsync()

        setupSurface()
        setupModeToggle()
        setupButtons()
        loadCurrentProject()
        renderState(LiveStreamingManager.state, null)
    }

    override fun onStart() {
        super.onStart()
        LiveStreamingManager.addListener(this)
        network.register()
    }

    override fun onResume() {
        super.onResume()
        refreshPreview()
        updateBatteryBanner()
        loadCurrentProject()
    }

    override fun onStop() {
        LiveStreamingManager.removeListener(this)
        network.unregister()
        if (!LiveStreamingManager.isStreaming) {
            LiveStreamingManager.stopPreview()
        }
        super.onStop()
    }

    override fun onDestroy() {
        stopLevelTicker()
        livePulse?.cancel()
        if (isFinishing && !LiveStreamingManager.isStreaming) {
            LiveStreamingManager.release()
        }
        super.onDestroy()
    }

    /**
     * User left the app while live → optional floating bubble.
     * The stream itself NEVER stops on navigation.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (LiveStreamingManager.isStreaming &&
            settings.floatingBubbleEnabled &&
            Settings.canDrawOverlays(this)
        ) {
            LiveBubbleService.start(this)
        }
    }

    // ------------------------------------------------------------------
    // Preview
    // ------------------------------------------------------------------

    private fun setupSurface() {
        binding.previewSurface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceReady = true
                refreshPreview()
            }

            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) = Unit

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
                LiveStreamingManager.stopPreview()
            }
        })

        binding.btnPlaceholderAction.setOnClickListener {
            if (LiveStreamingManager.mode == Mode.VIDEO) openVideoPicker()
            else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun refreshPreview() {
        if (!surfaceReady) return
        if (LiveStreamingManager.isStreaming) return // live preview is owned by the engine
        val mode = LiveStreamingManager.mode
        when (mode) {
            Mode.VIDEO -> {
                val video = quickLiveVideo()
                if (video == null) {
                    showPlaceholder(
                        getString(R.string.no_video_selected),
                        getString(R.string.select_video)
                    )
                    return
                }
                val error = LiveStreamingManager.startPreview(
                    this, binding.previewSurface, video.uriParsed()
                )
                if (error == null) {
                    binding.previewPlaceholder.visibility = View.GONE
                    showReadyBadge("${video.resolutionLabel()} • ${video.fps} FPS • ${video.durationLabel()}")
                } else showPlaceholder(error, getString(R.string.change_video))
            }

            Mode.CAMERA -> {
                if (!hasPermission(Manifest.permission.CAMERA)) {
                    showPlaceholder(
                        getString(R.string.tap_to_enable_camera),
                        getString(R.string.enable_camera)
                    )
                    return
                }
                val error = LiveStreamingManager.startPreview(this, binding.previewSurface, null)
                if (error == null) {
                    binding.previewPlaceholder.visibility = View.GONE
                    binding.previewStatusBadge.visibility = View.GONE
                } else showPlaceholder(error, getString(R.string.enable_camera))
            }
        }
    }

    private fun showPlaceholder(text: String, action: String) {
        binding.previewPlaceholder.visibility = View.VISIBLE
        binding.previewPlaceholderText.text = text
        binding.btnPlaceholderAction.text = action
        binding.previewStatusBadge.visibility = View.GONE
    }

    private fun showReadyBadge(text: String) {
        if (LiveStreamingManager.isStreaming) return
        binding.previewStatusBadge.visibility = View.VISIBLE
        binding.previewStatusBadge.text = text
    }

    // ------------------------------------------------------------------
    // Mode toggle (VIDEO LIVE primary, CAMERA secondary)
    // ------------------------------------------------------------------

    private fun setupModeToggle() {
        binding.modeToggle.check(
            if (LiveStreamingManager.mode == Mode.VIDEO) R.id.btnModeVideo else R.id.btnModeCamera
        )
        applyModeUi()

        binding.modeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            if (LiveStreamingManager.isStreaming) {
                showError(getString(R.string.mode_locked_while_live))
                binding.modeToggle.check(
                    if (LiveStreamingManager.mode == Mode.VIDEO) R.id.btnModeVideo
                    else R.id.btnModeCamera
                )
                return@addOnButtonCheckedListener
            }
            val newMode = if (checkedId == R.id.btnModeVideo) Mode.VIDEO else Mode.CAMERA
            LiveStreamingManager.setMode(newMode)
            settings.lastMode = if (newMode == Mode.CAMERA) 1 else 0
            applyModeUi()
            refreshPreview()
        }
    }

    private fun applyModeUi() {
        val videoMode = LiveStreamingManager.mode == Mode.VIDEO
        binding.cameraControls.visibility = if (videoMode) View.GONE else View.VISIBLE
        binding.placeholderIcon.setImageResource(
            if (videoMode) R.drawable.ic_folder_video else R.drawable.ic_videocam
        )
    }

    // ------------------------------------------------------------------
    // Projects
    // ------------------------------------------------------------------

    private fun loadCurrentProject() {
        val id = settings.currentProjectId
        if (id == 0L) {
            currentProject = null
            renderProjectCard()
        } else {
            repo.async({ it.projectBundle(id) }) { bundle ->
                if (isFinishing || isDestroyed) return@async
                currentProject = bundle?.project
                renderProjectCard()
            }
        }
        loadRecentProjects()
    }

    /** Small "RECENT PROJECTS" strip under the quick actions. */
    private fun loadRecentProjects() {
        repo.async({ it.recentProjects(3) }) { list ->
            if (isFinishing || isDestroyed) return@async
            binding.recentProjects.layoutManager =
                LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)
            binding.recentProjects.adapter = object :
                androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {

                override fun onCreateViewHolder(
                    parent: android.view.ViewGroup, viewType: Int
                ): androidx.recyclerview.widget.RecyclerView.ViewHolder =
                    object : androidx.recyclerview.widget.RecyclerView.ViewHolder(
                        ItemRecentProjectBinding.inflate(layoutInflater, parent, false).root
                    ) {}

                override fun getItemCount(): Int = list.size

                override fun onBindViewHolder(
                    holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int
                ) {
                    val project = list[position]
                    val b = ItemRecentProjectBinding.bind(holder.itemView)
                    b.recentName.text = project.name
                    b.recentMeta.text = "• ${project.height}p ${project.fps}fps • " +
                        "${project.broadcastMode.label}"
                    holder.itemView.setOnClickListener {
                        settings.currentProjectId = project.id
                        loadCurrentProject()
                    }
                }
            }
        }
    }

    private fun renderProjectCard() {
        val project = currentProject
        if (project == null) {
            binding.projectName.text = getString(R.string.no_project_selected)
            binding.projectVideoSummary.text = getString(R.string.no_project_hint)
            binding.projectDestSummary.text = ""
            binding.projectThumb.setImageDrawable(null)
            updateReadyHint()
            return
        }
        binding.projectName.text = project.name
        repo.async({ it.projectBundle(project.id) }) { bundle ->
            if (isFinishing || isDestroyed || bundle == null) return@async
            val playlistVideos = bundle.playlist
                .mapNotNull { videos.byId(it.videoId) }
            val videoSummary = when {
                playlistVideos.isEmpty() -> getString(R.string.playlist_empty)
                playlistVideos.size == 1 ->
                    "${playlistVideos[0].name} • ${project.loopMode.label}"
                else -> "${playlistVideos.size} videos • ${project.loopMode.label}"
            }
            binding.projectVideoSummary.text = videoSummary
            val destCount = bundle.destinations.count { it.enabled }
            binding.projectDestSummary.text = "$destCount destinations • " +
                "${project.height}p ${project.fps}fps ${project.videoBitrateKbps / 1000} Mbps • " +
                project.broadcastMode.label

            val firstVideo = playlistVideos.firstOrNull()
            if (firstVideo == null) {
                binding.projectThumb.setImageDrawable(null)
            } else {
                thumbs.load(firstVideo.id, firstVideo.uri) { bmp ->
                    if (!isFinishing && bmp != null) binding.projectThumb.setImageBitmap(bmp)
                }
            }
            updateReadyHint()
        }
    }

    private fun updateReadyHint() {
        val project = currentProject ?: run {
            binding.readyHint.visibility = View.GONE
            return
        }
        binding.readyHint.visibility = View.VISIBLE
        binding.readyHint.text = getString(R.string.ready) + " • ${project.height}p ${project.fps}fps" +
            " • ${project.broadcastMode.label}"
    }

    // ------------------------------------------------------------------
    // Video import (quick selection)
    // ------------------------------------------------------------------

    private fun openVideoPicker() {
        try {
            videoPickerLauncher.launch(arrayOf("video/*"))
        } catch (t: Throwable) {
            showError(getString(R.string.picker_unavailable))
        }
    }

    private fun importVideo(uri: Uri) {
        Snackbar.make(binding.root, R.string.analyzing_video, Snackbar.LENGTH_SHORT).show()
        bgExecutor.execute {
            val info = MediaAnalyzer.analyze(this, uri)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (info == null) {
                    MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.unsupported_video_title)
                        .setMessage(R.string.unsupported_video_message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                    return@runOnUiThread
                }
                val item = videos.add(uri, info)
                settings.selectedVideoId = item.id
                LiveStreamingManager.invalidateVideoSource()
                refreshPreview()
            }
        }
    }

    private fun quickLiveVideo() = videos.byId(settings.selectedVideoId)

    // ------------------------------------------------------------------
    // Buttons
    // ------------------------------------------------------------------

    private fun setupButtons() {
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnLibrary.setOnClickListener { openLibrary() }
        binding.btnProjects.setOnClickListener { openProjects() }
        binding.btnNewProject.setOnClickListener { newProject() }
        binding.btnEditProject.setOnClickListener {
            val project = currentProject
            if (project == null) newProject() else editProject(project)
        }
        binding.btnMixer.setOnClickListener { showAudioMixer() }
        binding.btnOverlays.setOnClickListener { showOverlayControls() }

        binding.btnSkipNext.setOnClickListener { LiveStreamingManager.skipToNext() }
        binding.btnSkipPrevious.setOnClickListener { LiveStreamingManager.skipToPrevious() }

        binding.btnDashboardAudio.setOnClickListener { showAudioMixer() }
        binding.btnDashboardOverlay.setOnClickListener { showOverlayControls() }
        binding.btnDashboardScenes.setOnClickListener { showSceneControls() }
        binding.btnDashboardChat.setOnClickListener {
            showUnavailableDialog(R.string.chat_unavailable_title, R.string.chat_unavailable)
        }
        binding.btnDashboardAnalytics.setOnClickListener {
            showUnavailableDialog(R.string.analytics_unavailable_title, R.string.analytics_unavailable)
        }
        binding.btnDashboardStop.setOnClickListener { confirmStop() }

        binding.btnSwitchCamera.setOnClickListener {
            if (!LiveStreamingManager.switchCamera()) {
                showError(getString(R.string.camera_not_active))
            }
        }
        binding.btnFlash.setOnClickListener {
            when (LiveStreamingManager.toggleLantern()) {
                null -> showError(getString(R.string.flash_unsupported))
                true -> binding.btnFlash.setImageResource(R.drawable.ic_flash_on)
                false -> binding.btnFlash.setImageResource(R.drawable.ic_flash_off)
            }
        }
        binding.btnMic.setOnClickListener {
            val newState = !LiveStreamingManager.micEnabled
            if (newState && !hasPermission(Manifest.permission.RECORD_AUDIO)) {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                LiveStreamingManager.setMicrophoneEnabled(newState)
                updateMicUi()
            }
        }

        binding.btnFixBattery.setOnClickListener { showBatteryFix() }

        binding.btnStartStop.setOnClickListener {
            val active = LiveStreamingManager.isStreaming ||
                LiveStreamingManager.state == StreamState.CONNECTING ||
                LiveStreamingManager.state == StreamState.RECONNECTING
            if (active) confirmStop() else requestStartStream()
        }
    }

    private fun openLibrary() {
        startActivity(Intent(this, VideoLibraryActivity::class.java))
    }

    private fun openProjects() {
        startActivity(Intent(this, ProjectsActivity::class.java))
    }

    private fun newProject() {
        startActivity(
            Intent(this, ProjectEditorActivity::class.java)
                .putExtra(ProjectEditorActivity.EXTRA_PROJECT_ID, 0L)
        )
    }

    private fun editProject(project: Project) {
        startActivity(
            Intent(this, ProjectEditorActivity::class.java)
                .putExtra(ProjectEditorActivity.EXTRA_PROJECT_ID, project.id)
        )
    }

    private fun updateMicUi() {
        val on = LiveStreamingManager.micEnabled
        binding.btnMic.setImageResource(if (on) R.drawable.ic_mic else R.drawable.ic_mic_off)
    }

    // ------------------------------------------------------------------
    // Battery / background safety
    // ------------------------------------------------------------------

    private fun updateBatteryBanner() {
        val warn = BatterySafety.shouldWarn(this) &&
            !LiveStreamingManager.isStreaming
        binding.batteryBanner.visibility = if (warn) View.VISIBLE else View.GONE
    }

    private fun showBatteryFix() {
        val assessment = BatterySafety.assess(this)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.battery_fix_title)
            .setMessage(assessment.guidance)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.fix_now) { _, _ ->
                try {
                    startActivity(BatterySafety.exemptionIntent(this))
                } catch (_: Throwable) {
                    try {
                        startActivity(BatterySafety.optimizationListIntent())
                    } catch (_: Throwable) {
                        showError(getString(R.string.battery_warning_text))
                    }
                }
            }
            .show()
    }

    // ------------------------------------------------------------------
    // Start / stop with preflight
    // ------------------------------------------------------------------

    private fun requestStartStream() {
        if (!network.isOnline()) {
            showError(getString(R.string.error_no_internet)); return
        }

        val project = currentProject
        if (project == null) {
            // Legacy quick-live path (v1 behavior) when a destination exists.
            if (settings.streamUrl.isBlank()) {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.create_project)
                    .setMessage(R.string.no_project_hint)
                    .setPositiveButton(android.R.string.ok) { _, _ -> newProject() }
                    .show()
                return
            }
            if (LiveStreamingManager.mode == Mode.VIDEO && quickLiveVideo() == null) {
                showError(getString(R.string.preflight_no_video)); return
            }
        }

        val missing = mutableListOf<String>()
        if (LiveStreamingManager.mode == Mode.CAMERA && !hasPermission(Manifest.permission.CAMERA)) {
            missing += Manifest.permission.CAMERA
        }
        if (LiveStreamingManager.micEnabled && !hasPermission(Manifest.permission.RECORD_AUDIO)) {
            missing += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= 33 && !hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
            missing += Manifest.permission.POST_NOTIFICATIONS
        }

        if (missing.isNotEmpty()) {
            pendingStartAfterPermission = true
            streamPermissionsLauncher.launch(missing.toTypedArray())
        } else continueStart()
    }

    private fun continueStart() {
        val project = currentProject
        if (project == null) {
            doLegacyStart()
            return
        }
        // Build the plan on a background thread (DB + analysis + relay session).
        binding.btnStartStop.isEnabled = false
        bgExecutor.execute {
            val planResult = buildPlan(project)
            runOnUiThread {
                binding.btnStartStop.isEnabled = true
                if (isFinishing || isDestroyed) return@runOnUiThread
                val (plan, error) = planResult
                if (plan == null) {
                    showError(error ?: "Project is incomplete")
                    return@runOnUiThread
                }
                showNetworkCheckAndStart(plan)
            }
        }
    }

    /** v1 quick-live: single destination, single looping video. PRESERVED. */
    private fun doLegacyStart() {
        val config = StreamConfig.from(settings)
        if (!config.isValidUrl()) {
            showError(getString(R.string.error_url_invalid)); return
        }
        val uri = quickLiveVideo()?.uriParsed()
        val error = LiveStreamingManager.startStream(
            this, config, uri,
            if (surfaceReady) binding.previewSurface else null
        )
        if (error != null) {
            showErrorDialog(getString(R.string.dialog_start_failed_title), error)
        } else {
            LiveStreamingService.start(this)
        }
    }

    private fun buildPlan(project: Project): Pair<BroadcastPlan?, String?> {
        val bundle = repo.projectBundle(project.id) ?: return null to "Project not found"
        val (base, error) = repo.buildPlan(bundle)
        if (base == null) return null to error

        // Resolve playlist videos.
        val playlistVideos = bundle.playlist.mapNotNull { videos.byId(it.videoId) }
        if (playlistVideos.isEmpty() && base.mode == Mode.VIDEO) {
            return null to getString(R.string.playlist_empty)
        }
        val first = playlistVideos.firstOrNull()
        val sampleRate = first?.takeIf { it.hasAudio }?.sampleRate ?: project.sampleRate
        val stereo = first?.takeIf { it.hasAudio }?.let { it.channels >= 2 } ?: project.stereo
        val playlistMedia = playlistVideos.map {
            com.livevip.app.streaming.PlaylistMedia(
                id = it.id,
                uri = it.uriParsed(),
                displayName = it.name,
                width = it.width,
                height = it.height,
                fps = it.fps,
                durationMs = it.durationMs,
                hasAudio = it.hasAudio,
                sampleRate = if (it.hasAudio) it.sampleRate else sampleRate,
                isStereo = if (it.hasAudio) it.channels >= 2 else stereo
            )
        }
        val plan = base.copy(
            playlist = playlistMedia,
            audio = base.audio.copy(sampleRate = sampleRate, stereo = stereo),
            overlays = project.overlays
        )
        val validation = plan.validate()
        if (validation != null) return null to validation
        return plan to null
    }

    /** Smart network check before going live (direct vs relay recommendation). */
    private fun showNetworkCheckAndStart(plan: BroadcastPlan) {
        val view = DialogNetworkCheckBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(view.root)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        val destCount = plan.activeDestinations.size
        val available = network.estimatedUploadKbps()
        val assessment = NetworkMath.assess(
            plan.quality.videoBitrateKbps,
            plan.audio.audioBitrateKbps,
            destCount,
            plan.broadcastMode,
            available
        )
        view.netRequired.text = getString(
            R.string.required_upload
        ) + ": %.1f Mbps".format(
            Locale.US,
            assessment.requiredPhoneUploadKbps / 1000.0
        ) + " (video + audio + overhead + 25% margin)"
        view.netAvailable.text = getString(R.string.available_upload) + ": " +
            (available?.let { "%.1f Mbps (OS estimate)".format(Locale.US, it / 1000.0) }
                ?: getString(R.string.upload_unknown))
        view.netMargin.text = getString(R.string.safety_margin) + ": " +
            (assessment.marginKbps?.let { "%.1f Mbps".format(Locale.US, it / 1000.0) }
                ?: "—") + " — ${assessment.status}"
        view.netRecommendation.text = when {
            destCount > 1 && assessment.recommendedMode == BroadcastMode.SMART_RELAY ->
                getString(R.string.direct_not_recommended)
            else -> getString(R.string.direct_good)
        }

        // Latency probe happens off the main thread — the dialog shows it
        // when ready; starting is never blocked on it.
        bgExecutor.execute {
            val rtt = network.probeHostLatency(plan.activeDestinations.first().url)
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    view.netLatency.text = rtt?.let { getString(R.string.latency_ms, it) }
                        ?: getString(R.string.latency_unreachable)
                }
            }
        }

        view.btnStartAnyway.setOnClickListener {
            dialog.dismiss()
            startBroadcast(plan)
        }
        dialog.show()
    }

    private fun startBroadcast(plan: BroadcastPlan) {
        binding.btnStartStop.isEnabled = false
        bgExecutor.execute {
            val relaySession: RelaySessionClient.Session? =
                if (plan.broadcastMode == BroadcastMode.SMART_RELAY && plan.relay != null) {
                    when (val r = RelaySessionClient.createSession(
                        plan.relay.apiUrl, plan.relay.token,
                        plan.projectName, plan.activeDestinations
                    )) {
                        is RelaySessionClient.Result.Error -> {
                            runOnUiThread {
                                binding.btnStartStop.isEnabled = true
                                showErrorDialog(
                                    getString(R.string.dialog_start_failed_title),
                                    "Relay: ${r.message}"
                                )
                            }
                            return@execute
                        }
                        is RelaySessionClient.Result.Ok -> r.value
                    }
                } else null

            val error = LiveStreamingManager.startBroadcast(
                this, plan,
                if (surfaceReady) binding.previewSurface else null,
                relaySession
            )
            runOnUiThread {
                binding.btnStartStop.isEnabled = true
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (error != null) {
                    showErrorDialog(getString(R.string.dialog_start_failed_title), error)
                } else {
                    LiveStreamingService.start(this)
                }
            }
        }
    }

    private fun confirmStop() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.stop_live_title)
            .setMessage(R.string.stop_live_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.btn_stop_live_short) { _, _ -> stopStream() }
            .show()
    }

    private fun stopStream() {
        LiveStreamingManager.stopBroadcast()
        LiveStreamingService.stop(this)
        LiveBubbleService.stop(this)
    }

    // ------------------------------------------------------------------
    // Audio mixer (mic OFF never stops video audio)
    // ------------------------------------------------------------------

    private fun showAudioMixer() {
        val view = DialogAudioMixerBinding.inflate(layoutInflater)
        view.sliderVideoVolume.value = LiveStreamingManager.videoVolume * 100f
        view.sliderMicVolume.value = LiveStreamingManager.micVolume * 100f
        view.switchVideoAudio.isChecked = LiveStreamingManager.videoAudioEnabled
        view.switchMic.isChecked = LiveStreamingManager.micEnabled

        view.sliderVideoVolume.addOnChangeListener { _, v, _ ->
            LiveStreamingManager.setVolumes(v / 100f, view.sliderMicVolume.value / 100f)
        }
        view.sliderMicVolume.addOnChangeListener { _, v, _ ->
            LiveStreamingManager.setVolumes(view.sliderVideoVolume.value / 100f, v / 100f)
        }
        view.switchVideoAudio.setOnCheckedChangeListener { _, checked ->
            LiveStreamingManager.setVideoAudioOn(checked)
        }
        view.switchMic.setOnCheckedChangeListener { _, checked ->
            if (checked && !hasPermission(Manifest.permission.RECORD_AUDIO)) {
                view.switchMic.isChecked = false
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else LiveStreamingManager.setMicrophoneEnabled(checked)
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(view.root)
            .setOnDismissListener { stopLevelTicker() }
            .create()

        view.btnMixerDone.setOnClickListener { dialog.dismiss() }
        dialog.show()
        stopLevelTicker()
        val ticker = levelTicker(view)
        activeLevelTicker = ticker
        uiHandler.post(ticker)
    }

    private fun stopLevelTicker() {
        activeLevelTicker?.let { uiHandler.removeCallbacks(it) }
        activeLevelTicker = null
    }

    /** Level meters driven by REAL mixed audio RMS (not simulated). */
    private fun levelTicker(view: DialogAudioMixerBinding) = object : Runnable {
        override fun run() {
            val levels = LiveStreamingManager.audioLevels()
            if (levels != null) {
                val (video, mic) = levels
                view.videoLevel.layoutParams = view.videoLevel.layoutParams.apply {
                    width = (4 + video * 44 * resources.displayMetrics.density).toInt()
                }
                view.micLevel.layoutParams = view.micLevel.layoutParams.apply {
                    width = (4 + mic * 44 * resources.displayMetrics.density).toInt()
                }
            }
            uiHandler.postDelayed(this, 150)
        }
    }

    // ------------------------------------------------------------------
    // Overlay / scene control (composited into the ENCODED stream)
    // ------------------------------------------------------------------

    private fun showOverlayControls() {
        val project = currentProject
        if (project == null || project.overlays.isEmpty()) {
            Snackbar.make(
                binding.root,
                R.string.no_overlays,
                Snackbar.LENGTH_LONG
            ).show()
            return
        }
        val names = project.overlays.map { "${it.type.label} — ${it.text.ifEmpty { "overlay" }}" }
        val active = LiveStreamingManager.activeOverlayIds().toMutableSet()
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.overlays)
            .setMultiChoiceItems(
                names.toTypedArray(),
                project.overlays.map { it.id in active }.toBooleanArray()
            ) { _, which, checked ->
                val overlay = project.overlays[which]
                if (checked) active += overlay.id else active -= overlay.id
                val selected = project.overlays.filter { it.id in active }
                LiveStreamingManager.applyOverlays(selected)
                Snackbar.make(
                    binding.root,
                    R.string.overlay_applied_live,
                    Snackbar.LENGTH_SHORT
                ).show()
            }
            .setPositiveButton(android.R.string.ok, null)
            .create()
        dialog.show()
    }

    private fun showSceneControls() {
        val project = currentProject
        if (project == null || project.scenes.isEmpty()) {
            Snackbar.make(binding.root, R.string.no_scenes, Snackbar.LENGTH_LONG).show()
            return
        }
        val names = project.scenes.map { it.name }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.scenes)
            .setItems(names) { _, which ->
                LiveStreamingManager.applyScene(project.scenes[which], project.overlays)
                Snackbar.make(binding.root, R.string.overlay_applied_live, Snackbar.LENGTH_SHORT)
                    .show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showUnavailableDialog(titleRes: Int, messageRes: Int) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_live_controls, null)
        view.findViewById<android.widget.TextView>(R.id.controlsTitle)
            .setText(titleRes)
        view.findViewById<android.widget.TextView>(R.id.controlsMessage)
            .setText(messageRes)
        MaterialAlertDialogBuilder(this)
            .setView(view)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // ------------------------------------------------------------------
    // Manager callbacks
    // ------------------------------------------------------------------

    override fun onStateChanged(state: StreamState, message: String?) {
        if (isFinishing || isDestroyed) return
        renderState(state, message)
    }

    override fun onStatsChanged(stats: StreamStats) {
        if (isFinishing || isDestroyed) return
        binding.timerText.text = formatDuration(stats.durationSec)
        binding.statBitrate.text = getString(R.string.stat_bitrate_format, stats.bitrateKbps)
        binding.statFps.text = stats.fps.toString()
        binding.statDropped.text = stats.droppedFrames.toString()
        binding.statLoops.text = getString(R.string.loop_number_format, stats.loopCount)
        binding.statReconnects.text = stats.reconnects.toString()
        binding.statConnection.text =
            if (stats.congestion) getString(R.string.connection_poor)
            else getString(R.string.connection_good)
        binding.statConnection.setTextColor(
            ContextCompat.getColor(
                this,
                if (stats.congestion) R.color.warning_amber else R.color.success_green
            )
        )
        binding.statDestinations.text =
            getString(R.string.destinations_live_format, stats.destinationsLive, stats.destinationsTotal)

        val info = LiveStreamingManager.playlistInfo()
        if (info != null && LiveStreamingManager.mode == Mode.VIDEO) {
            binding.nowPlaying.text = getString(
                R.string.now_playing, info.currentName,
                info.currentIndex + 1, info.itemCount
            )
        }
    }

    override fun onDestinationsChanged(statuses: List<LiveStreamingManager.DestinationRuntimeStatus>) {
        if (isFinishing || isDestroyed) return
        renderDestinations(statuses)
    }

    override fun onHealthChanged(health: LiveStreamingManager.StreamHealth) {
        if (isFinishing || isDestroyed) return
        val timeline = health.timeline
        binding.statTimeline.text = when (timeline.status) {
            com.livevip.app.streaming.TimelineGuard.Status.OK -> "✓"
            com.livevip.app.streaming.TimelineGuard.Status.WARNING -> "!"
            com.livevip.app.streaming.TimelineGuard.Status.CRITICAL -> "✗"
        }
        binding.statTimeline.setTextColor(
            ContextCompat.getColor(
                this,
                when (timeline.status) {
                    com.livevip.app.streaming.TimelineGuard.Status.OK -> R.color.success_green
                    com.livevip.app.streaming.TimelineGuard.Status.WARNING -> R.color.warning_amber
                    com.livevip.app.streaming.TimelineGuard.Status.CRITICAL -> R.color.error_soft_red
                }
            )
        )
    }

    private fun renderDestinations(statuses: List<LiveStreamingManager.DestinationRuntimeStatus>) {
        val container = binding.destinationList
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)
        statuses.forEach { status ->
            val row = ItemDestStatusBinding.inflate(inflater, container, false)
            row.destName.text = status.name
            row.destState.text = when (status.state) {
                LiveStreamingManager.DestinationState.LIVE -> getString(R.string.destination_live)
                LiveStreamingManager.DestinationState.CONNECTING -> getString(R.string.destination_connecting)
                LiveStreamingManager.DestinationState.RECONNECTING -> getString(R.string.destination_reconnecting)
                LiveStreamingManager.DestinationState.FAILED -> getString(R.string.destination_failed)
                LiveStreamingManager.DestinationState.STOPPED -> getString(R.string.destination_stopped)
                LiveStreamingManager.DestinationState.IDLE -> getString(R.string.destination_idle)
            }
            val color = when (status.state) {
                LiveStreamingManager.DestinationState.LIVE -> R.color.status_live
                LiveStreamingManager.DestinationState.CONNECTING, LiveStreamingManager.DestinationState.RECONNECTING -> R.color.status_connecting
                LiveStreamingManager.DestinationState.FAILED -> R.color.status_error
                else -> R.color.status_offline
            }
            row.destDot.backgroundTintList = ContextCompat.getColorStateList(this, color)
            row.destState.setTextColor(ContextCompat.getColor(this, color))
            val detail = buildString {
                append(status.platform)
                if (status.bitrateKbps > 0) append(" • ${status.bitrateKbps} kbps")
                if (status.reconnectCount > 0) append(" • ${status.reconnectCount} retries")
                status.lastError?.let { append(" • $it") }
            }
            row.destDetail.text = detail
            container.addView(row.root)
        }
    }

    private fun renderState(state: StreamState, message: String?) {
        val (dotColor, label) = when (state) {
            StreamState.OFFLINE -> R.color.status_offline to getString(R.string.state_offline)
            StreamState.CONNECTING -> R.color.status_connecting to getString(R.string.state_connecting)
            StreamState.LIVE -> R.color.status_live to getString(R.string.state_live)
            StreamState.RECONNECTING -> R.color.status_reconnecting to getString(R.string.state_reconnecting)
            StreamState.ERROR -> R.color.status_error to getString(R.string.state_error)
        }
        binding.statusDot.backgroundTintList = ContextCompat.getColorStateList(this, dotColor)
        binding.statusText.text = label

        val active = state == StreamState.LIVE || state == StreamState.CONNECTING ||
            state == StreamState.RECONNECTING

        binding.btnStartStop.text = getString(
            if (active) R.string.stop_live_action else R.string.start_live
        )
        binding.btnStartStop.setBackgroundResource(
            if (active) R.drawable.bg_stop_button else R.drawable.bg_gradient_button
        )

        binding.liveBadge.visibility = if (state == StreamState.LIVE) View.VISIBLE else View.GONE
        binding.timerText.visibility = if (active) View.VISIBLE else View.GONE
        binding.liveDashboard.visibility = if (active) View.VISIBLE else View.GONE

        if (state == StreamState.LIVE) startLivePulse() else stopLivePulse()

        binding.modeToggle.isEnabled = !active
        binding.btnEditProject.isEnabled = !active
        binding.btnNewProject.isEnabled = !active

        if (state == StreamState.ERROR && message != null) {
            showErrorDialog(getString(R.string.dialog_stream_error_title), message)
        } else if (message != null && state == StreamState.CONNECTING) {
            Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
        }

        if (state == StreamState.OFFLINE || state == StreamState.ERROR) {
            updateMicUi()
            updateBatteryBanner()
            refreshPreview()
            loadCurrentProject()
        }
    }

    private fun startLivePulse() {
        if (livePulse?.isRunning == true) return
        livePulse = ObjectAnimator.ofFloat(binding.liveBadge, View.ALPHA, 1f, 0.45f).apply {
            duration = 700
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
    }

    private fun stopLivePulse() {
        livePulse?.cancel()
        livePulse = null
        binding.liveBadge.alpha = 1f
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun showError(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    private fun showErrorDialog(title: String, message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun formatDuration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }

}
