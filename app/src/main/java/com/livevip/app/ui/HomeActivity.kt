package com.livevip.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import com.livevip.app.R
import com.livevip.app.camera.CameraConfig
import com.livevip.app.data.SettingsRepository
import com.livevip.app.databinding.ActivityHomeBinding
import com.livevip.app.service.LiveStreamingService
import com.livevip.app.streaming.LiveStreamingManager
import com.livevip.app.streaming.StreamConfig
import com.livevip.app.streaming.StreamPlatform
import com.livevip.app.streaming.StreamState
import com.livevip.app.streaming.StreamStats
import com.livevip.app.util.NetworkMonitor
import java.util.Locale

/**
 * LIVE VIP dashboard. This activity only controls the UI —
 * all streaming logic lives in [LiveStreamingManager] and runs inside
 * [LiveStreamingService] while a stream is active.
 */
class HomeActivity : AppCompatActivity(), LiveStreamingManager.Listener {

    private lateinit var binding: ActivityHomeBinding
    private lateinit var settings: SettingsRepository
    private lateinit var network: NetworkMonitor

    private var surfaceReady = false
    private var pendingStartAfterPermission = false

    // ------------------------------------------------------------------
    // Permission launchers
    // ------------------------------------------------------------------

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                showPreviewIfPossible()
            } else {
                binding.previewPlaceholderText.text =
                    getString(R.string.camera_permission_denied)
                showError(getString(R.string.camera_permission_denied))
            }
        }

    private val streamPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val micGranted = result[Manifest.permission.RECORD_AUDIO] ?: hasMicPermission()
            val camGranted = result[Manifest.permission.CAMERA] ?: hasCameraPermission()
            if (pendingStartAfterPermission) {
                pendingStartAfterPermission = false
                when {
                    !camGranted -> showError(getString(R.string.camera_permission_denied))
                    !micGranted -> showError(getString(R.string.mic_permission_denied))
                    else -> {
                        showPreviewIfPossible()
                        doStartStream()
                    }
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

        setupPreviewSurface()
        setupInputs()
        setupDropdowns()
        setupButtons()
        renderState(LiveStreamingManager.state, null)
    }

    override fun onStart() {
        super.onStart()
        LiveStreamingManager.addListener(this)
        network.register()
    }

    override fun onResume() {
        super.onResume()
        showPreviewIfPossible()
        updateMicIcon()
    }

    override fun onStop() {
        persistInputs()
        LiveStreamingManager.removeListener(this)
        network.unregister()
        // Keep camera running only when live (foreground service owns it).
        if (!LiveStreamingManager.isStreaming) {
            LiveStreamingManager.stopPreview()
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (isFinishing && !LiveStreamingManager.isStreaming) {
            LiveStreamingManager.release()
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Preview
    // ------------------------------------------------------------------

    private fun setupPreviewSurface() {
        binding.openGlView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceReady = true
                showPreviewIfPossible()
            }

            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) = Unit

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
                LiveStreamingManager.detachPreview()
            }
        })

        binding.btnEnableCamera.setOnClickListener {
            if (hasCameraPermission()) {
                showPreviewIfPossible()
            } else {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun showPreviewIfPossible() {
        if (!surfaceReady || !hasCameraPermission()) return
        val ok = if (LiveStreamingManager.isStreaming) {
            LiveStreamingManager.reattachPreview(binding.openGlView)
            true
        } else {
            LiveStreamingManager.startPreview(this, binding.openGlView)
        }
        if (ok) {
            binding.previewPlaceholder.visibility = View.GONE
        } else {
            binding.previewPlaceholder.visibility = View.VISIBLE
            binding.previewPlaceholderText.text = getString(R.string.camera_unavailable)
        }
    }

    // ------------------------------------------------------------------
    // Inputs & dropdowns
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
        // Platform
        val platforms = StreamPlatform.values().map { it.label }
        binding.platformDropdown.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, platforms)
        )
        val savedPlatform = settings.platformIndex.coerceIn(0, platforms.size - 1)
        binding.platformDropdown.setText(platforms[savedPlatform], false)
        binding.platformDropdown.setOnItemClickListener { _, _, position, _ ->
            settings.platformIndex = position
            val platform = StreamPlatform.values()[position]
            if (platform.baseUrl.isNotEmpty()) {
                binding.inputUrl.setText(platform.baseUrl)
            } else if (binding.inputUrl.text.isNullOrBlank()) {
                binding.inputUrl.hint = if (platform == StreamPlatform.CUSTOM_RTMPS) {
                    "rtmps://server/app"
                } else {
                    "rtmp://server/app"
                }
            }
        }

        // Resolution
        val resLabels = CameraConfig.RESOLUTIONS.map { it.label }
        binding.resolutionDropdown.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, resLabels)
        )
        val currentRes = CameraConfig.RESOLUTIONS.indexOfFirst {
            it.height == settings.videoHeight
        }.takeIf { it >= 0 } ?: 2
        binding.resolutionDropdown.setText(resLabels[currentRes], false)
        binding.resolutionDropdown.setOnItemClickListener { _, _, position, _ ->
            val preset = CameraConfig.RESOLUTIONS[position]
            settings.videoWidth = preset.width
            settings.videoHeight = preset.height
        }

        // FPS
        val fpsLabels = CameraConfig.FPS_OPTIONS.map { "$it fps" }
        binding.fpsDropdown.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, fpsLabels)
        )
        val currentFps = CameraConfig.FPS_OPTIONS.indexOf(settings.videoFps)
            .takeIf { it >= 0 } ?: 1
        binding.fpsDropdown.setText(fpsLabels[currentFps], false)
        binding.fpsDropdown.setOnItemClickListener { _, _, position, _ ->
            settings.videoFps = CameraConfig.FPS_OPTIONS[position]
        }

        // Bitrate
        val bitrateLabels = CameraConfig.BITRATE_OPTIONS.map { "$it kbps" }
        binding.bitrateDropdown.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, bitrateLabels)
        )
        val currentBitrate = CameraConfig.BITRATE_OPTIONS.indexOf(settings.videoBitrateKbps)
            .takeIf { it >= 0 } ?: 2
        binding.bitrateDropdown.setText(bitrateLabels[currentBitrate], false)
        binding.bitrateDropdown.setOnItemClickListener { _, _, position, _ ->
            settings.videoBitrateKbps = CameraConfig.BITRATE_OPTIONS[position]
        }
    }

    // ------------------------------------------------------------------
    // Buttons
    // ------------------------------------------------------------------

    private fun setupButtons() {
        binding.btnStartStop.setOnClickListener {
            if (LiveStreamingManager.isStreaming ||
                LiveStreamingManager.state == StreamState.CONNECTING ||
                LiveStreamingManager.state == StreamState.RECONNECTING
            ) {
                stopStream()
            } else {
                requestStartStream()
            }
        }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnSwitchCamera.setOnClickListener {
            if (!hasCameraPermission() || !LiveStreamingManager.isOnPreview) {
                showError(getString(R.string.camera_not_active))
            } else if (!LiveStreamingManager.switchCamera()) {
                showError(getString(R.string.camera_switch_failed))
            }
        }

        binding.btnMic.setOnClickListener {
            if (!LiveStreamingManager.isStreaming) {
                showError(getString(R.string.mic_toggle_hint))
            } else {
                LiveStreamingManager.toggleMute()
                updateMicIcon()
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

    private fun updateMicIcon() {
        binding.btnMic.setIconResource(
            if (LiveStreamingManager.isMuted) R.drawable.ic_mic_off else R.drawable.ic_mic
        )
    }

    // ------------------------------------------------------------------
    // Start / stop stream
    // ------------------------------------------------------------------

    private fun requestStartStream() {
        persistInputs()

        val config = StreamConfig.from(settings)
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
        if (!hasCameraPermission()) missing += Manifest.permission.CAMERA
        if (!hasMicPermission()) missing += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
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
        val error = LiveStreamingManager.startStream(this, config)
        if (error != null) {
            showError(error)
        } else {
            LiveStreamingService.start(this)
        }
    }

    private fun stopStream() {
        LiveStreamingManager.stopStream()
        LiveStreamingService.stop(this)
    }

    // ------------------------------------------------------------------
    // Manager callbacks → UI
    // ------------------------------------------------------------------

    override fun onStateChanged(state: StreamState, message: String?) {
        if (isFinishing || isDestroyed) return
        renderState(state, message)
    }

    override fun onStatsChanged(stats: StreamStats) {
        if (isFinishing || isDestroyed) return
        binding.timerText.text = formatDuration(stats.durationSec)
        binding.statBitrate.text =
            getString(R.string.stat_bitrate_format, stats.bitrateKbps)
        binding.statFps.text = getString(R.string.stat_fps_format, stats.fps)
        binding.statDropped.text = stats.droppedFrames.toString()
        binding.statConnection.text =
            if (stats.congestion) getString(R.string.connection_poor)
            else getString(R.string.connection_good)
        binding.statConnection.setTextColor(
            ContextCompat.getColor(
                this,
                if (stats.congestion) R.color.status_reconnecting else R.color.status_live
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
        binding.statusDot.backgroundTintList =
            ContextCompat.getColorStateList(this, dotColor)
        binding.statusText.text = label

        val streamingOrTrying =
            state == StreamState.LIVE || state == StreamState.CONNECTING ||
                state == StreamState.RECONNECTING

        binding.btnStartStop.text = getString(
            if (streamingOrTrying) R.string.btn_stop_live else R.string.btn_start_live
        )
        binding.btnStartStop.setBackgroundColor(
            ContextCompat.getColor(
                this,
                if (streamingOrTrying) R.color.stop_button else R.color.live_red
            )
        )
        binding.btnStartStop.isEnabled = true

        binding.liveBadge.visibility =
            if (state == StreamState.LIVE) View.VISIBLE else View.GONE
        binding.statsCard.visibility =
            if (streamingOrTrying && settings.showStats) View.VISIBLE else View.GONE
        binding.timerText.visibility =
            if (streamingOrTrying) View.VISIBLE else View.GONE

        // Lock stream inputs while live.
        binding.inputUrlLayout.isEnabled = !streamingOrTrying
        binding.inputKeyLayout.isEnabled = !streamingOrTrying
        binding.platformLayout.isEnabled = !streamingOrTrying
        binding.resolutionLayout.isEnabled = !streamingOrTrying
        binding.fpsLayout.isEnabled = !streamingOrTrying
        binding.bitrateLayout.isEnabled = !streamingOrTrying

        if (state == StreamState.ERROR && message != null) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_stream_error_title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        } else if (message != null) {
            Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
        }

        if (state == StreamState.OFFLINE || state == StreamState.ERROR) {
            updateMicIcon()
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun showError(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    private fun formatDuration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }
}
