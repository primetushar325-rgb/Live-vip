package com.livevip.app.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.livevip.app.LiveVipApplication
import com.livevip.app.R
import com.livevip.app.model.OutputFormat
import com.livevip.app.model.StreamQuality
import com.livevip.app.model.StreamSettings
import com.livevip.app.stream.LiveStreamingForegroundService

class MainActivity : AppCompatActivity() {
    private lateinit var preview: LivePreviewView
    private lateinit var selectedVideoText: android.widget.TextView
    private lateinit var statusText: android.widget.TextView
    private lateinit var startButton: MaterialButton
    private lateinit var diagnosticsText: android.widget.TextView
    private lateinit var serverInput: TextInputEditText
    private lateinit var keyInput: TextInputEditText
    private lateinit var formatSpinner: Spinner
    private lateinit var qualitySpinner: Spinner
    private lateinit var fpsSpinner: Spinner
    private lateinit var videoAudioSwitch: android.widget.Switch
    private lateinit var microphoneSwitch: android.widget.Switch
    private var selectedVideo: Uri? = null
    private var diagnosticsVisible = false

    private val pickVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Some document providers do not offer persistable permissions. The current session can still use it.
        }
        selectedVideo = uri
        selectedVideoText.text = uri.lastPathSegment ?: uri.toString()
        findViewById<View>(R.id.previewHint).visibility = View.GONE
        preview.setVideo(uri)
        (application as LiveVipApplication).settings.save(serverInput.text?.toString().orEmpty(), keyInput.text?.toString().orEmpty(), uri)
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val needsMic = microphoneSwitch.isChecked
        if (needsMic && grants[Manifest.permission.RECORD_AUDIO] != true && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast("Microphone permission is required when microphone is enabled")
            return@registerForActivityResult
        }
        launchStream()
    }

    private val engineReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != LiveStreamingForegroundService.ACTION_ENGINE_UPDATE) return
            val rawState = intent.getStringExtra(LiveStreamingForegroundService.EXTRA_STATE) ?: "IDLE"
            val state = if (rawState == "IDLE" || rawState == "STOPPED") "OFFLINE" else rawState
            statusText.text = "● $state"
            statusText.setTextColor(when (state) {
                "STREAMING" -> getColor(R.color.green)
                "ERROR" -> getColor(R.color.accent)
                "CONNECTING", "RECONNECTING" -> getColor(R.color.orange)
                else -> getColor(R.color.muted)
            })
            val active = state !in setOf("IDLE", "STOPPED", "ERROR", "OFFLINE")
            startButton.text = if (active) "STOP LIVE" else "START LIVE"
            startButton.isEnabled = state !in setOf("PREPARING", "CONNECTING", "SENDING", "STOPPING")
            val snapshot = intent.getStringExtra(LiveStreamingForegroundService.EXTRA_DIAGNOSTICS)
            if (snapshot != null) diagnosticsText.text = snapshot
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        setupSpinners()
        restoreSettings()
        preview.onPreviewError = { message -> runOnUiThread { toast(message) } }
        preview.onFirstFrame = { _, _ -> runOnUiThread { findViewById<View>(R.id.previewHint).visibility = View.GONE } }

        findViewById<MaterialButton>(R.id.selectVideoButton).setOnClickListener {
            pickVideo.launch(arrayOf("video/*"))
        }
        findViewById<MaterialButton>(R.id.fitButton).setOnClickListener { preview.fit() }
        findViewById<MaterialButton>(R.id.fillButton).setOnClickListener { preview.fill() }
        findViewById<MaterialButton>(R.id.resetButton).setOnClickListener { preview.resetTransform() }
        startButton.setOnClickListener { toggleStream() }
        findViewById<MaterialButton>(R.id.diagnosticsButton).setOnClickListener {
            diagnosticsVisible = !diagnosticsVisible
            diagnosticsText.visibility = if (diagnosticsVisible) View.VISIBLE else View.GONE
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(LiveStreamingForegroundService.ACTION_ENGINE_UPDATE)
        ContextCompat.registerReceiver(this, engineReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStop() {
        runCatching { unregisterReceiver(engineReceiver) }
        super.onStop()
    }

    override fun onDestroy() {
        preview.stop()
        super.onDestroy()
    }

    private fun bindViews() {
        preview = findViewById(R.id.previewView)
        selectedVideoText = findViewById(R.id.selectedVideoText)
        statusText = findViewById(R.id.statusText)
        startButton = findViewById(R.id.startLiveButton)
        diagnosticsText = findViewById(R.id.diagnosticsText)
        serverInput = findViewById(R.id.serverUrlInput)
        keyInput = findViewById(R.id.streamKeyInput)
        formatSpinner = findViewById(R.id.formatSpinner)
        qualitySpinner = findViewById(R.id.qualitySpinner)
        fpsSpinner = findViewById(R.id.fpsSpinner)
        videoAudioSwitch = findViewById(R.id.videoAudioSwitch)
        microphoneSwitch = findViewById(R.id.microphoneSwitch)
    }

    private fun setupSpinners() {
        fun <T> Spinner.adapter(values: List<T>) {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, values)
        }
        formatSpinner.adapter(OutputFormat.entries.map { it.label })
        qualitySpinner.adapter(StreamQuality.entries.map { it.label })
        fpsSpinner.adapter(listOf("24 FPS", "30 FPS", "60 FPS"))
        formatSpinner.setSelection(0)
        qualitySpinner.setSelection(1)
        fpsSpinner.setSelection(1)
    }

    private fun restoreSettings() {
        val store = (application as LiveVipApplication).settings
        serverInput.setText(store.serverUrl())
        keyInput.setText(store.streamKey())
        selectedVideo = store.videoUri()
        selectedVideo?.let {
            selectedVideoText.text = it.lastPathSegment ?: it.toString()
            findViewById<View>(R.id.previewHint).visibility = View.GONE
            preview.setVideo(it)
        }
    }

    private fun toggleStream() {
        val state = statusText.text.toString()
        if (state.contains("STREAMING") || state.contains("CONNECTING") || state.contains("SENDING") || state.contains("RECONNECTING")) {
            stopStream()
            return
        }
        val required = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) required += Manifest.permission.POST_NOTIFICATIONS
        if (microphoneSwitch.isChecked) required += Manifest.permission.RECORD_AUDIO
        val missing = required.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray()) else launchStream()
    }

    private fun launchStream() {
        val uri = selectedVideo
        if (uri == null) {
            toast("Select a video first; preview must show a real frame")
            return
        }
        val server = serverInput.text?.toString()?.trim().orEmpty()
        val key = keyInput.text?.toString()?.trim().orEmpty()
        if (!server.startsWith("rtmp://") && !server.startsWith("rtmps://")) {
            toast("Server URL must start with rtmp:// or rtmps://")
            return
        }
        if (key.isBlank()) {
            toast("Stream Key is required")
            return
        }
        val outputFormat = OutputFormat.entries[formatSpinner.selectedItemPosition]
        val quality = StreamQuality.entries[qualitySpinner.selectedItemPosition]
        val composition = preview.currentComposition().copy(
            outputWidth = if (outputFormat == OutputFormat.VERTICAL) quality.height else quality.width,
            outputHeight = if (outputFormat == OutputFormat.VERTICAL) quality.width else quality.height
        )
        val settings = StreamSettings(
            videoUri = uri,
            serverUrl = server,
            streamKey = key,
            outputFormat = outputFormat,
            quality = quality,
            fps = listOf(24, 30, 60)[fpsSpinner.selectedItemPosition],
            videoAudio = videoAudioSwitch.isChecked,
            microphone = microphoneSwitch.isChecked,
            composition = composition
        )
        (application as LiveVipApplication).settings.save(server, key, uri)
        val intent = Intent(this, LiveStreamingForegroundService::class.java).apply { putExtras(settings.toBundle()) }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopStream() {
        startService(Intent(this, LiveStreamingForegroundService::class.java).setAction(LiveStreamingForegroundService.ACTION_STOP))
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
