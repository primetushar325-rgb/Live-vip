package com.livevip.app.ui

import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.snackbar.Snackbar
import com.livevip.app.R
import com.livevip.app.camera.CameraConfig
import com.livevip.app.data.SecretsVault
import com.livevip.app.data.SettingsRepository
import com.livevip.app.databinding.ActivitySettingsBinding
import com.livevip.app.relay.RelaySessionClient
import com.livevip.app.streaming.LiveStreamingManager
import com.livevip.app.util.BatterySafety
import java.util.concurrent.Executors

/** Dedicated settings screen: Video / Audio / Stream / Appearance / Advanced. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = SettingsRepository.get(this)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        if (LiveStreamingManager.isStreaming) {
            Snackbar.make(
                binding.root,
                R.string.settings_locked_while_live,
                Snackbar.LENGTH_LONG
            ).show()
        }

        setupVideoSection()
        setupAudioSection()
        setupStreamSection()
        setupAppearanceSection()
        setupRelaySection()
        setupBackgroundSection()
        setupQuickLiveSection()
        setupAdvancedSection()
    }

    override fun onStop() {
        persistSecretInputs()
        super.onStop()
    }

    // ---------------- Smart Relay ----------------

    private fun setupRelaySection() {
        val vault = SecretsVault.get(this)
        binding.inputRelayUrl.setText(vault.relayApiUrl)
        binding.inputRelayToken.setText(vault.relayToken)

        binding.btnTestRelay.setOnClickListener {
            persistSecretInputs()
            val url = vault.relayApiUrl
            val token = vault.relayToken
            if (!url.startsWith("https://")) {
                Snackbar.make(
                    binding.root,
                    "Relay URL must start with https://",
                    Snackbar.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }
            binding.btnTestRelay.isEnabled = false
            Executors.newSingleThreadExecutor().execute {
                val result = RelaySessionClient.ping(url, token)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    binding.btnTestRelay.isEnabled = true
                    when (result) {
                        is RelaySessionClient.Result.Ok ->
                            Snackbar.make(binding.root, R.string.relay_ok, Snackbar.LENGTH_SHORT).show()
                        is RelaySessionClient.Result.Error ->
                            Snackbar.make(
                                binding.root,
                                getString(R.string.relay_unavailable, result.message),
                                Snackbar.LENGTH_LONG
                            ).show()
                    }
                }
            }
        }
    }

    private fun persistSecretInputs() {
        val vault = SecretsVault.get(this)
        vault.relayApiUrl = binding.inputRelayUrl.text?.toString()?.trim().orEmpty()
        vault.relayToken = binding.inputRelayToken.text?.toString()?.trim().orEmpty()
        settings.streamUrl = binding.inputQuickUrl.text?.toString()?.trim().orEmpty()
        settings.streamKey = binding.inputQuickKey.text?.toString()?.trim().orEmpty()
    }

    // ---------------- Background safety ----------------

    private fun setupBackgroundSection() {
        binding.switchFloatingBubble.isChecked = settings.floatingBubbleEnabled
        binding.switchFloatingBubble.setOnCheckedChangeListener { _, checked ->
            settings.floatingBubbleEnabled = checked
            if (checked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                !android.provider.Settings.canDrawOverlays(this)
            ) {
                Snackbar.make(
                    binding.root,
                    R.string.overlay_permission_needed,
                    Snackbar.LENGTH_LONG
                ).show()
                try {
                    startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:$packageName")
                        )
                    )
                } catch (_: Throwable) {
                }
            }
        }
        val assessment = BatterySafety.assess(this)
        binding.batteryStatus.text = if (assessment.ignoringOptimizations) {
            "Battery optimization: UNRESTRICTED ✓"
        } else {
            getString(R.string.battery_warning_text)
        }
        binding.btnBatteryFix.setOnClickListener {
            try {
                startActivity(BatterySafety.exemptionIntent(this))
            } catch (_: Throwable) {
                try {
                    startActivity(BatterySafety.optimizationListIntent())
                } catch (_: Throwable) {
                }
            }
        }
    }

    // ---------------- Quick live (legacy single destination) ----------------

    private fun setupQuickLiveSection() {
        binding.inputQuickUrl.setText(settings.streamUrl)
        binding.inputQuickKey.setText(settings.streamKey)
    }

    // ---------------- Video ----------------

    private fun setupVideoSection() {
        val resLabels = CameraConfig.RESOLUTIONS.map { it.label }
        bindDropdown(binding.settingResolution, resLabels,
            CameraConfig.RESOLUTIONS.indexOfFirst { it.height == settings.videoHeight }
                .takeIf { it >= 0 } ?: 2) { position ->
            val preset = CameraConfig.RESOLUTIONS[position]
            settings.videoWidth = preset.width
            settings.videoHeight = preset.height
        }

        val fpsLabels = CameraConfig.FPS_OPTIONS.map { "$it fps" }
        bindDropdown(binding.settingFps, fpsLabels,
            CameraConfig.FPS_OPTIONS.indexOf(settings.videoFps).takeIf { it >= 0 } ?: 1) {
            settings.videoFps = CameraConfig.FPS_OPTIONS[it]
        }

        val bitrateLabels = CameraConfig.BITRATE_OPTIONS.map { "$it kbps" }
        bindDropdown(binding.settingBitrate, bitrateLabels,
            CameraConfig.BITRATE_OPTIONS.indexOf(settings.videoBitrateKbps)
                .takeIf { it >= 0 } ?: 2) {
            settings.videoBitrateKbps = CameraConfig.BITRATE_OPTIONS[it]
        }

        val keyframeOptions = listOf(1, 2, 3, 4, 5)
        bindDropdown(binding.settingKeyframe, keyframeOptions.map { "$it s" },
            keyframeOptions.indexOf(settings.keyframeIntervalSec).takeIf { it >= 0 } ?: 1) {
            settings.keyframeIntervalSec = keyframeOptions[it]
        }
    }

    // ---------------- Audio ----------------

    private fun setupAudioSection() {
        val audioBitrates = listOf(64, 96, 128, 160, 192)
        bindDropdown(binding.settingAudioBitrate, audioBitrates.map { "$it kbps" },
            audioBitrates.indexOf(settings.audioBitrateKbps).takeIf { it >= 0 } ?: 2) {
            settings.audioBitrateKbps = audioBitrates[it]
        }

        val sampleRates = listOf(16000, 32000, 44100, 48000)
        bindDropdown(binding.settingSampleRate, sampleRates.map { "$it Hz" },
            sampleRates.indexOf(settings.audioSampleRate).takeIf { it >= 0 } ?: 2) {
            settings.audioSampleRate = sampleRates[it]
        }

        binding.switchStereo.isChecked = settings.audioStereo
        binding.switchStereo.setOnCheckedChangeListener { _, checked ->
            settings.audioStereo = checked
        }
        binding.switchEchoCanceler.isChecked = settings.echoCanceler
        binding.switchEchoCanceler.setOnCheckedChangeListener { _, checked ->
            settings.echoCanceler = checked
        }
        binding.switchNoiseSuppressor.isChecked = settings.noiseSuppressor
        binding.switchNoiseSuppressor.setOnCheckedChangeListener { _, checked ->
            settings.noiseSuppressor = checked
        }
    }

    // ---------------- Stream ----------------

    private fun setupStreamSection() {
        binding.switchAutoReconnect.isChecked = settings.autoReconnect
        binding.switchAutoReconnect.setOnCheckedChangeListener { _, checked ->
            settings.autoReconnect = checked
        }

        val attempts = listOf(1, 2, 3, 5, 10)
        bindDropdown(binding.settingReconnectAttempts, attempts.map { "$it" },
            attempts.indexOf(settings.maxReconnectAttempts).takeIf { it >= 0 } ?: 2) {
            settings.maxReconnectAttempts = attempts[it]
        }
    }

    // ---------------- Appearance ----------------

    private fun setupAppearanceSection() {
        val themes = listOf(
            getString(R.string.theme_system),
            getString(R.string.theme_dark),
            getString(R.string.theme_light)
        )
        bindDropdown(binding.settingTheme, themes, settings.themeMode) { position ->
            settings.themeMode = position
            when (position) {
                SettingsRepository.THEME_LIGHT ->
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                SettingsRepository.THEME_DARK ->
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                else ->
                    AppCompatDelegate.setDefaultNightMode(
                        AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    )
            }
        }
    }

    // ---------------- Advanced ----------------

    private fun setupAdvancedSection() {
        binding.switchDebugLogging.isChecked = settings.debugLogging
        binding.switchDebugLogging.setOnCheckedChangeListener { _, checked ->
            settings.debugLogging = checked
        }
        binding.switchShowStats.isChecked = settings.showStats
        binding.switchShowStats.setOnCheckedChangeListener { _, checked ->
            settings.showStats = checked
        }
    }

    // ---------------- Helper ----------------

    private fun bindDropdown(
        view: com.google.android.material.textfield.MaterialAutoCompleteTextView,
        labels: List<String>,
        selectedIndex: Int,
        onSelected: (Int) -> Unit
    ) {
        view.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, labels))
        val index = selectedIndex.coerceIn(0, labels.size - 1)
        view.setText(labels[index], false)
        view.setOnItemClickListener { _, _, position, _ -> onSelected(position) }
    }
}
