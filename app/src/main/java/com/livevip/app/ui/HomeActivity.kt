package com.livevip.app.ui

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.View
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.livevip.app.R
import com.livevip.app.camera.CameraConfig
import com.livevip.app.data.SettingsRepository
import com.livevip.app.data.StreamProfile
import com.livevip.app.data.StreamProfilesRepository
import com.livevip.app.databinding.ActivityHomeBinding
import com.livevip.app.media.MediaAnalyzer
import com.livevip.app.media.VideoItem
import com.livevip.app.media.VideoRepository
import com.livevip.app.service.LiveStreamingService
import com.livevip.app.streaming.LiveStreamingManager
import com.livevip.app.streaming.LiveStreamingManager.Mode
import com.livevip.app.streaming.StreamConfig
import com.livevip.app.streaming.StreamPlatform
import com.livevip.app.streaming.StreamState
import com.livevip.app.streaming.StreamStats
import com.livevip.app.util.NetworkMonitor
import java.util.Locale
import java.util.concurrent.Executors

/**
 * LIVE VIP premium dashboard.
 * UI only — the broadcast pipeline lives in [LiveStreamingManager]
 * and runs under [LiveStreamingService] while live.
 */
class HomeActivity : AppCompatActivity(), LiveStreamingManager.Listener {

    private lateinit var binding: ActivityHomeBinding
    private lateinit var settings: SettingsRepository
    private lateinit var network: NetworkMonitor
    private lateinit var videos: VideoRepository
    private lateinit var profiles: StreamProfilesRepository

    private val bgExecutor = Executors.newSingleThreadExecutor()
    private var surfaceReady = false
    private var pendingStartAfterPermission = false
    private var selectedVideo: VideoItem? = null
    private var livePulse: ObjectAnimator? = null

    // ------------------------------------------------------------------
    // Launchers
    // ------------------------------------------------------------------

    private val videoPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importVideo(uri)
        }

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                refreshPreview()
            } else {
                showError(getString(R.string.camera_permission_denied))
            }
        }

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                LiveStreamingManager.setMicrophoneEnabled(true)
                binding.switchMic.isChecked = true
                updateMicUi()
            } else {
                binding.switchMic.isChecked = false
                showError(getString(R.string.mic_permission_denied))
            }
        }

    private val streamPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (pendingStartAfterPermission) {
                pendingStartAfterPermission = false
                val camNeeded = LiveStreamingManager.mode == Mode.CAMERA
                val camGranted = !camNeeded || hasPermission(Manifest.permission.CAMERA)
                if (!camGranted) {
                    showError(getString(R.string.camera_permission_denied))
                } else {
                    doStartStream()
                }
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
        profiles = StreamProfilesRepository.get(this)

        LiveStreamingManager.setMode(
            if (settings.lastMode == 1) Mode.CAMERA else Mode.VIDEO
        )

        setupSurface()
        setupModeToggle()
        setupInputs()
        setupDropdowns()
        setupProfiles()
        setupMixer()
        setupButtons()
        loadSelectedVideo()
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
        updateMicUi()
        refreshProfilesDropdown()
    }

    override fun onStop() {
        persistInputs()
        LiveStreamingManager.removeListener(this)
        network.unregister()
        if (!LiveStreamingManager.isStreaming) {
            LiveStreamingManager.stopPreview()
        }
        super.onStop()
    }

    override fun onDestroy() {
        livePulse?.cancel()
        if (isFinishing && !LiveStreamingManager.isStreaming) {
            LiveStreamingManager.release()
        }
        super.onDestroy()
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
            if (LiveStreamingManager.mode == Mode.VIDEO) {
                openVideoPicker()
            } else {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun refreshPreview() {
        if (!surfaceReady) return
        val mode = LiveStreamingManager.mode
        when (mode) {
            Mode.VIDEO -> {
                val video = selectedVideo
                if (video == null) {
                    showPlaceholder(getString(R.string.no_video_selected), getString(R.string.select_video))
                    return
                }
                val error = LiveStreamingManager.startPreview(
                    this, binding.previewSurface, video.uriParsed()
                )
                if (error == null) {
                    binding.previewPlaceholder.visibility = View.GONE
                    showReadyBadge(video)
                } else {
                    showPlaceholder(error, getString(R.string.change_video))
                }
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
                } else {
                    showPlaceholder(error, getString(R.string.enable_camera))
                }
            }
        }
    }

    private fun showPlaceholder(text: String, action: String) {
        binding.previewPlaceholder.visibility = View.VISIBLE
        binding.previewPlaceholderText.text = text
        binding.btnPlaceholderAction.text = action
        binding.previewStatusBadge.visibility = View.GONE
    }

    private fun showReadyBadge(video: VideoItem) {
        if (LiveStreamingManager.isStreaming) return
        binding.previewStatusBadge.visibility = View.VISIBLE
        binding.previewStatusBadge.text = getString(
            R.string.video_ready_format,
            video.height, video.fps, video.durationLabel()
        )
    }

    // ------------------------------------------------------------------
    // Mode toggle
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
        binding.videoCard.visibility = if (videoMode) View.VISIBLE else View.GONE
        binding.mixerCard.visibility = if (videoMode) View.VISIBLE else View.GONE
        binding.btnSwitchCamera.isEnabled = !videoMode
        binding.btnFlash.isEnabled = !videoMode
        binding.btnSwitchCamera.alpha = if (videoMode) 0.4f else 1f
        binding.btnFlash.alpha = if (videoMode) 0.4f else 1f
    }

    // ------------------------------------------------------------------
    // Video selection / library
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
                loadSelectedVideo()
                refreshPreview()
            }
        }
    }

    private fun loadSelectedVideo() {
        val item = videos.byId(settings.selectedVideoId)
        selectedVideo = item
        if (item == null) {
            binding.videoName.text = getString(R.string.no_video_selected)
            binding.videoMeta.text = getString(R.string.tap_select_video_hint)
            binding.videoThumb.setImageDrawable(null)
            return
        }
        binding.videoName.text = item.name
        binding.videoMeta.text = item.metaLabel()
        bgExecutor.execute {
            val thumb = MediaAnalyzer.thumbnail(this, item.uriParsed())
            runOnUiThread {
                if (!isFinishing && thumb != null) binding.videoThumb.setImageBitmap(thumb)
            }
        }
    }

    // ------------------------------------------------------------------
    // Inputs / dropdowns
    // ------------------------------------------------------------------

    private fun setupInputs() {
        binding.inputUrl.setText(settings.streamUrl)
        binding.inputKey.setText(settings.streamKey)
    }

    private fun persistInputs() {
        settings.streamUrl = binding.inputUrl.text?.toString().orEmpty()
        settings.streamKey = binding.inputKey.text?.toString().orEmpty()
    }

    private fun setupDropdowns() {
        val platforms = StreamPlatform.values().map { it.label }
        binding.platformDropdown.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, platforms)
        )
        val savedPlatform = settings.platformIndex.coerceIn(0, platforms.size - 1)
        binding.platformDropdown.setText(platforms[savedPlatform], false)
        binding.platformDropdown.setOnItemClickListener { _, _, position, _ ->
            settings.platformIndex = position
            val platform = StreamPlatform.values()[position]
            if (platform.baseUrl.isNotEmpty()) binding.inputUrl.setText(platform.baseUrl)
        }

        val resLabels = CameraConfig.RESOLUTIONS.map { it.label }
        binding.resolutionDropdown.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, resLabels)
        )
        val currentRes = CameraConfig.RESOLUTIONS.indexOfFirst { it.height == settings.videoHeight }
            .takeIf { it >= 0 } ?: 2
        binding.resolutionDropdown.setText(resLabels[currentRes], false)
        binding.resolutionDropdown.setOnItemClickListener { _, _, position, _ ->
            val preset = CameraConfig.RESOLUTIONS[position]
            settings.videoWidth = preset.width
            settings.videoHeight = preset.height
            LiveStreamingManager.invalidatePreparation()
            refreshPreview()
        }

        val fpsLabels = CameraConfig.FPS_OPTIONS.map { "$it fps" }
        binding.fpsDropdown.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, fpsLabels)
        )
        val currentFps = CameraConfig.FPS_OPTIONS.indexOf(settings.videoFps)
            .takeIf { it >= 0 } ?: 1
        binding.fpsDropdown.setText(fpsLabels[currentFps], false)
        binding.fpsDropdown.setOnItemClickListener { _, _, position, _ ->
            settings.videoFps = CameraConfig.FPS_OPTIONS[position]
            LiveStreamingManager.invalidatePreparation()
        }

        val bitrateLabels = CameraConfig.BITRATE_OPTIONS.map { "$it kbps" }
        binding.bitrateDropdown.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, bitrateLabels)
        )
        val currentBitrate = CameraConfig.BITRATE_OPTIONS.indexOf(settings.videoBitrateKbps)
            .takeIf { it >= 0 } ?: 3
        binding.bitrateDropdown.setText(bitrateLabels[currentBitrate], false)
        binding.bitrateDropdown.setOnItemClickListener { _, _, position, _ ->
            settings.videoBitrateKbps = CameraConfig.BITRATE_OPTIONS[position]
            LiveStreamingManager.invalidatePreparation()
        }
    }

    // ------------------------------------------------------------------
    // Stream profiles
    // ------------------------------------------------------------------

    private fun setupProfiles() {
        refreshProfilesDropdown()

        binding.profileDropdown.setOnItemClickListener { _, _, position, _ ->
            val all = profiles.all()
            if (position in all.indices) applyProfile(all[position])
        }

        binding.btnSaveProfile.setOnClickListener { promptSaveProfile() }

        binding.btnDeleteProfile.setOnClickListener {
            val name = binding.profileDropdown.text?.toString().orEmpty()
            val profile = profiles.all().firstOrNull { it.name == name }
            if (profile == null) {
                showError(getString(R.string.no_profile_selected))
            } else {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.delete_profile)
                    .setMessage(getString(R.string.delete_profile_confirm, profile.name))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.delete) { _, _ ->
                        profiles.delete(profile.id)
                        binding.profileDropdown.setText("", false)
                        refreshProfilesDropdown()
                    }
                    .show()
            }
        }
    }

    private fun refreshProfilesDropdown() {
        val names = profiles.all().map { it.name }
        binding.profileDropdown.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, names)
        )
    }

    private fun applyProfile(profile: StreamProfile) {
        binding.inputUrl.setText(profile.url)
        binding.inputKey.setText(profile.key)
        settings.platformIndex = profile.platformIndex
        settings.videoWidth = profile.width
        settings.videoHeight = profile.height
        settings.videoFps = profile.fps
        settings.videoBitrateKbps = profile.bitrateKbps
        persistInputs()
        setupDropdowns()
        LiveStreamingManager.invalidatePreparation()
        Snackbar.make(
            binding.root,
            getString(R.string.profile_applied, profile.name),
            Snackbar.LENGTH_SHORT
        ).show()
    }

    private fun promptSaveProfile() {
        persistInputs()
        val input = TextInputEditText(this).apply {
            hint = getString(R.string.profile_name_hint)
            setPadding(48, 48, 48, 24)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.save_profile)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    showError(getString(R.string.profile_name_required))
                } else {
                    profiles.save(
                        StreamProfile(
                            id = System.currentTimeMillis(),
                            name = name,
                            url = settings.streamUrl,
                            key = settings.streamKey,
                            platformIndex = settings.platformIndex,
                            width = settings.videoWidth,
                            height = settings.videoHeight,
                            fps = settings.videoFps,
                            bitrateKbps = settings.videoBitrateKbps
                        )
                    )
                    refreshProfilesDropdown()
                    binding.profileDropdown.setText(name, false)
                    Snackbar.make(binding.root, R.string.profile_saved, Snackbar.LENGTH_SHORT)
                        .show()
                }
            }
            .show()
    }

    // ------------------------------------------------------------------
    // Audio mixer
    // ------------------------------------------------------------------

    private fun setupMixer() {
        binding.sliderVideoVolume.value = LiveStreamingManager.videoVolume * 100f
        binding.sliderMicVolume.value = LiveStreamingManager.micVolume * 100f
        binding.switchVideoAudio.isChecked = LiveStreamingManager.videoAudioEnabled
        binding.switchMic.isChecked = LiveStreamingManager.micEnabled

        binding.sliderVideoVolume.addOnChangeListener { _, value, _ ->
            LiveStreamingManager.setVolumes(value / 100f, binding.sliderMicVolume.value / 100f)
        }
        binding.sliderMicVolume.addOnChangeListener { _, value, _ ->
            LiveStreamingManager.setVolumes(binding.sliderVideoVolume.value / 100f, value / 100f)
        }
        binding.switchVideoAudio.setOnCheckedChangeListener { _, checked ->
            LiveStreamingManager.setVideoAudioOn(checked)
        }
        binding.switchMic.setOnCheckedChangeListener { _, checked ->
            if (checked && !hasPermission(Manifest.permission.RECORD_AUDIO)) {
                binding.switchMic.isChecked = false
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                LiveStreamingManager.setMicrophoneEnabled(checked)
                updateMicUi()
            }
        }
    }

    private fun updateMicUi() {
        val on = LiveStreamingManager.micEnabled
        binding.btnMic.setIconResource(if (on) R.drawable.ic_mic else R.drawable.ic_mic_off)
        if (binding.switchMic.isChecked != on) binding.switchMic.isChecked = on
        binding.statMic.text = if (on) getString(R.string.on) else getString(R.string.off)
    }

    // ------------------------------------------------------------------
    // Buttons
    // ------------------------------------------------------------------

    private fun setupButtons() {
        binding.btnStartStop.setOnClickListener {
            val active = LiveStreamingManager.isStreaming ||
                LiveStreamingManager.state == StreamState.CONNECTING ||
                LiveStreamingManager.state == StreamState.RECONNECTING
            if (active) confirmStop() else requestStartStream()
        }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnSelectVideo.setOnClickListener { openVideoPicker() }
        binding.btnLibrary.setOnClickListener { openLibrary() }
        binding.btnLibraryBottom.setOnClickListener { openLibrary() }

        binding.btnSwitchCamera.setOnClickListener {
            if (!LiveStreamingManager.switchCamera()) {
                showError(getString(R.string.camera_not_active))
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

        binding.btnFlash.setOnClickListener {
            when (LiveStreamingManager.toggleLantern()) {
                null -> showError(getString(R.string.flash_unsupported))
                true -> binding.btnFlash.setIconResource(R.drawable.ic_flash_on)
                false -> binding.btnFlash.setIconResource(R.drawable.ic_flash_off)
            }
        }
    }

    private fun openLibrary() {
        startActivity(Intent(this, VideoLibraryActivity::class.java))
    }

    // ------------------------------------------------------------------
    // Start / stop with preflight
    // ------------------------------------------------------------------

    private fun requestStartStream() {
        persistInputs()
        val config = StreamConfig.from(settings)

        // ---- Preflight checks ----
        if (LiveStreamingManager.mode == Mode.VIDEO && selectedVideo == null) {
            showError(getString(R.string.preflight_no_video)); return
        }
        if (config.url.isBlank()) {
            showError(getString(R.string.error_url_empty)); return
        }
        if (!config.isValidUrl()) {
            showError(getString(R.string.error_url_invalid)); return
        }
        if (!network.isOnline()) {
            showError(getString(R.string.error_no_internet)); return
        }

        val missing = mutableListOf<String>()
        if (LiveStreamingManager.mode == Mode.CAMERA &&
            !hasPermission(Manifest.permission.CAMERA)
        ) {
            missing += Manifest.permission.CAMERA
        }
        if (LiveStreamingManager.micEnabled &&
            !hasPermission(Manifest.permission.RECORD_AUDIO)
        ) {
            missing += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            !hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            missing += Manifest.permission.POST_NOTIFICATIONS
        }

        if (missing.isNotEmpty()) {
            pendingStartAfterPermission = true
            streamPermissionsLauncher.launch(missing.toTypedArray())
        } else {
            doStartStream()
        }
    }

    private fun doStartStream() {
        val config = StreamConfig.from(settings)
        val uri = selectedVideo?.uriParsed()
        binding.btnStartStop.isEnabled = false
        val error = LiveStreamingManager.startStream(
            this, config, uri,
            if (surfaceReady) binding.previewSurface else null
        )
        binding.btnStartStop.isEnabled = true
        if (error != null) {
            showErrorDialog(getString(R.string.dialog_start_failed_title), error)
        } else {
            LiveStreamingService.start(this)
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
        LiveStreamingManager.stopStream()
        LiveStreamingService.stop(this)
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
        binding.statMode.text =
            if (LiveStreamingManager.mode == Mode.VIDEO) getString(R.string.mode_video_short)
            else getString(R.string.mode_camera_short)

        val active = state == StreamState.LIVE || state == StreamState.CONNECTING ||
            state == StreamState.RECONNECTING

        binding.btnStartStop.text = getString(
            if (active) R.string.btn_stop_live else R.string.btn_start_live
        )
        binding.btnStartStop.setBackgroundResource(
            if (active) R.drawable.bg_stop_button else R.drawable.bg_gradient_button
        )

        binding.liveBadge.visibility = if (state == StreamState.LIVE) View.VISIBLE else View.GONE
        binding.statsCard.visibility =
            if (active && settings.showStats) View.VISIBLE else View.GONE
        binding.timerText.visibility = if (active) View.VISIBLE else View.GONE
        if (state == StreamState.LIVE) {
            binding.previewStatusBadge.visibility = View.GONE
            startLivePulse()
        } else {
            stopLivePulse()
        }

        val lockInputs = active
        binding.inputUrlLayout.isEnabled = !lockInputs
        binding.inputKeyLayout.isEnabled = !lockInputs
        binding.platformLayout.isEnabled = !lockInputs
        binding.resolutionLayout.isEnabled = !lockInputs
        binding.fpsLayout.isEnabled = !lockInputs
        binding.bitrateLayout.isEnabled = !lockInputs
        binding.profileLayout.isEnabled = !lockInputs
        binding.btnSelectVideo.isEnabled = !lockInputs

        if (state == StreamState.ERROR && message != null) {
            showErrorDialog(getString(R.string.dialog_stream_error_title), message)
        } else if (message != null) {
            Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
        }

        if (state == StreamState.OFFLINE || state == StreamState.ERROR) {
            updateMicUi()
            refreshPreview()
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
