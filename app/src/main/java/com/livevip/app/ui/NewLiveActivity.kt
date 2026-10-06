package com.livevip.app.ui

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.livevip.app.R
import com.livevip.app.data.Destination
import com.livevip.app.data.Project
import com.livevip.app.data.ProjectRepository
import com.livevip.app.data.SettingsRepository
import com.livevip.app.data.ThumbnailCache
import com.livevip.app.databinding.ActivityNewLiveBinding
import com.livevip.app.media.MediaAnalyzer
import com.livevip.app.media.VideoRepository
import com.livevip.app.overlay.CanvasAspect
import com.livevip.app.overlay.CanvasConfig
import com.livevip.app.streaming.CanvasPresets
import com.livevip.app.streaming.CapabilityDetector
import com.livevip.app.streaming.StreamConfig
import com.livevip.app.streaming.StreamPlatform

/**
 * NEW LIVE wizard (Part 3 UX flow):
 *
 *   Name → Source (video/camera, NO playlist popup) → Output format
 *   (aspect cards + validated resolution) → Review + optional destination
 *   → CREATE LIVE PROJECT (project is READY — never auto-live).
 *
 * The created project opens in the Project Editor, where EDIT LIVE opens the
 * Canvas Editor for the real 9:16/16:9 preview and layers.
 */
class NewLiveActivity : AppCompatActivity() {

    companion object {
        /** Optional pre-seeded aspect from the SHORTS/LANDSCAPE shortcuts. */
        const val EXTRA_ASPECT = "aspect"
    }

    private lateinit var binding: ActivityNewLiveBinding
    private lateinit var repo: ProjectRepository
    private lateinit var videos: VideoRepository
    private lateinit var settings: SettingsRepository
    private lateinit var thumbs: ThumbnailCache

    private var step = 0
    private val stepPanels = mutableListOf<View>()

    private var isVideoMode = true
    private var selectedVideo: com.livevip.app.media.VideoItem? = null
    private var aspect = CanvasAspect.PORTRAIT_9_16
    private var preset = CanvasPresets.optionsFor(CanvasAspect.PORTRAIT_9_16).first()
    private val destinations = mutableListOf<Destination>()
    private val destinationKeys = mutableMapOf<Long, String>()
    private var chosenPlatform = StreamPlatform.CUSTOM_RTMP

    private val videoPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) importVideo(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNewLiveBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repo = ProjectRepository.get(this)
        videos = VideoRepository.get(this)
        settings = SettingsRepository.get(this)
        thumbs = ThumbnailCache.get(this)

        stepPanels += listOf(binding.step1Panel, binding.step2Panel, binding.step3Panel, binding.step4Panel)

        binding.btnWizardBackClose.setOnClickListener {
            if (step == 0) finish() else gotoStep(step - 1)
        }
        binding.btnStepBack.setOnClickListener {
            if (step == 0) finish() else gotoStep(step - 1)
        }
        binding.btnStepNext.setOnClickListener { advance() }

        setupStep1()
        setupStep2()
        setupStep3()
        setupStep4()
        gotoStep(0)
    }

    // ------------------------------------------------------------------
    // Steps
    // ------------------------------------------------------------------

    private fun setupStep1() {
        binding.wizardName.setText(defaultName())
    }

    private fun setupStep2() {
        binding.wizardSourceToggle.check(R.id.sourceVideo)
        binding.wizardSourceToggle.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            isVideoMode = id == R.id.sourceVideo
            renderSource()
        }
        binding.btnChangeVideo.setOnClickListener { pickVideo() }
        // Pre-select the library's last-used video so the video appears
        // AUTOMATICALLY (no forced "add to playlist" step).
        val preselect = settings.selectedVideoId.takeIf { it != 0L }?.let { videos.byId(it) }
        if (preselect != null) selectedVideo = preselect
        renderSource()
    }

    private fun pickVideo() {
        try {
            videoPicker.launch(arrayOf("video/*"))
        } catch (_: Throwable) {
            Snackbar.make(binding.root, R.string.picker_unavailable, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun importVideo(uri: Uri) {
        val info = try {
            MediaAnalyzer.analyze(this, uri)
        } catch (_: Throwable) {
            null
        }
        if (info == null) {
            Snackbar.make(binding.root, R.string.video_not_playable, Snackbar.LENGTH_LONG).show()
            return
        }
        val existing = videos.all().firstOrNull { it.uri == uri.toString() }
        val item = existing ?: videos.add(uri, info)
        selectedVideo = item
        settings.selectedVideoId = item.id
        renderSource()
    }

    private fun renderSource() {
        binding.videoCard.visibility = if (isVideoMode && selectedVideo != null) View.VISIBLE else View.GONE
        binding.noVideoHint.visibility = if (isVideoMode && selectedVideo == null) View.VISIBLE else View.GONE
        binding.btnChangeVideo.visibility = if (isVideoMode) View.VISIBLE else View.GONE
        val v = selectedVideo
        if (isVideoMode && v != null) {
            binding.videoCardName.text = v.name
            binding.videoCardMeta.text = "${v.resolutionLabel()} • ${v.fps} FPS • ${v.durationLabel()}"
            thumbs.load(v.id, v.uri) { bmp ->
                if (!isFinishing) binding.videoCardThumb.setImageBitmap(bmp)
            }
        }
    }

    private fun setupStep3() {
        fun pick(a: CanvasAspect) {
            aspect = a
            val caps = CapabilityDetector.videoEncoderCaps()
            val options = CanvasPresets.optionsFor(a)
            preset = options.firstOrNull { caps == null || caps.supports(it.width, it.height) }
                ?: options.last()
            renderFormat()
        }
        binding.format169.setOnClickListener { pick(CanvasAspect.LANDSCAPE_16_9) }
        binding.format916.setOnClickListener { pick(CanvasAspect.PORTRAIT_9_16) }
        binding.format11.setOnClickListener { pick(CanvasAspect.SQUARE_1_1) }
        binding.format45.setOnClickListener { pick(CanvasAspect.PORTRAIT_4_5) }
        // Default: Shorts (or the SHORTS/LANDSCAPE shortcut extra); tapping
        // a card switches immediately — the resolution list follows.
        pick(
            when (intent.getStringExtra(EXTRA_ASPECT)) {
                "16:9" -> CanvasAspect.LANDSCAPE_16_9
                else -> CanvasAspect.PORTRAIT_9_16
            }
        )
    }

    private fun renderFormat() {
        binding.format169.isSelected = aspect == CanvasAspect.LANDSCAPE_16_9
        binding.format916.isSelected = aspect == CanvasAspect.PORTRAIT_9_16
        binding.format11.isSelected = aspect == CanvasAspect.SQUARE_1_1
        binding.format45.isSelected = aspect == CanvasAspect.PORTRAIT_4_5

        val options = CanvasPresets.optionsFor(aspect)
        val labels = options.map { it.label }
        (binding.wizardResolution as AutoCompleteTextView).apply {
            setAdapter(ArrayAdapter(context, android.R.layout.simple_list_item_1, labels))
            setOnClickListener { showDropDown() }
        }
        binding.wizardResolution.setText(preset.label, false)
        binding.wizardResolution.setOnItemClickListener { _, _, position, _ ->
            preset = options[position]
            renderFormat()
        }
        val name = when (aspect) {
            CanvasAspect.LANDSCAPE_16_9 -> getString(R.string.format_landscape)
            CanvasAspect.PORTRAIT_9_16 -> getString(R.string.format_shorts)
            CanvasAspect.SQUARE_1_1 -> getString(R.string.format_square)
            else -> getString(R.string.format_portrait)
        }
        binding.formatNote.text = "$name • ${preset.width}×${preset.height} • FIT (no stretch)"
    }

    private fun setupStep4() {
        val platforms = StreamPlatform.entries.map { it.label }
        (binding.dstPlatform as AutoCompleteTextView).apply {
            setAdapter(ArrayAdapter(context, android.R.layout.simple_list_item_1, platforms))
            setOnClickListener { showDropDown() }
            setText(platforms.first(), false)
        }
        binding.dstPlatform.setOnItemClickListener { _, _, position, _ ->
            chosenPlatform = StreamPlatform.entries[position]
        }
        binding.btnAddDestination.setOnClickListener {
            val url = binding.dstUrl.text?.toString()?.trim().orEmpty()
            val key = binding.dstKey.text?.toString()?.trim().orEmpty()
            if (!url.startsWith("rtmp", ignoreCase = true)) {
                Snackbar.make(binding.root, R.string.invalid_rtmp_url, Snackbar.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val platform = chosenPlatform
            val dest = Destination(
                name = platform.label,
                platform = platform,
                url = url
            )
            destinations += dest
            if (key.isNotEmpty()) destinationKeys[dest.id] = key
            binding.dstUrl.setText("")
            binding.dstKey.setText("")
            renderDestinations()
            Snackbar.make(binding.root, R.string.destination_saved_toast, Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun renderDestinations() {
        binding.dstList.text = if (destinations.isEmpty()) {
            getString(R.string.no_destinations_yet)
        } else {
            destinations.joinToString("\n") { "🟢 ${it.name}" }
        }
        renderReview()
    }

    private fun renderReview() {
        val name = binding.wizardName.text?.toString()?.trim().orEmpty()
            .ifEmpty { defaultName() }
        val source = if (isVideoMode) {
            selectedVideo?.let { "Video: ${it.name}" } ?: "Video: —"
        } else "Camera"
        binding.reviewSummary.text = listOf(
            name,
            "${preset.width}×${preset.height} • ${aspect.label}",
            source,
            "Destinations: ${destinations.size}"
        ).joinToString("\n")
    }

    // ------------------------------------------------------------------
    // Navigation
    // ------------------------------------------------------------------

    private fun gotoStep(index: Int) {
        step = index.coerceIn(0, stepPanels.size - 1)
        stepPanels.forEachIndexed { i, panel -> panel.visibility = if (i == step) View.VISIBLE else View.GONE }
        val titles = listOf(
            getString(R.string.wizard_step_name),
            getString(R.string.wizard_step_source),
            getString(R.string.wizard_step_format),
            getString(R.string.wizard_step_create)
        )
        binding.wizardStepLabel.text =
            getString(R.string.step_of, step + 1, stepPanels.size) + " — " + titles[step]
        binding.btnStepBack.visibility = if (step == 0) View.INVISIBLE else View.VISIBLE
        binding.btnStepNext.text = if (step == stepPanels.size - 1) {
            getString(R.string.create_live_project)
        } else {
            getString(R.string.next)
        }
        if (step == stepPanels.size - 1) renderReview()
    }

    private fun advance() {
        if (step == 0) {
            val name = binding.wizardName.text?.toString()?.trim().orEmpty()
            if (name.isEmpty()) {
                binding.wizardNameLayout.error = getString(R.string.project_name_required)
                return
            }
            binding.wizardNameLayout.error = null
            gotoStep(1)
        } else if (step < stepPanels.size - 1) {
            gotoStep(step + 1)
        } else {
            createProject()
        }
    }

    // ------------------------------------------------------------------
    // Create — project is READY, never auto-live (spec §20).
    // ------------------------------------------------------------------

    private fun createProject() {
        val name = binding.wizardName.text?.toString()?.trim().orEmpty().ifEmpty { defaultName() }
        val defaults = StreamConfig.from(settings)
        val project = Project(
            id = 0,
            name = name,
            mode = if (isVideoMode) "VIDEO" else "CAMERA",
            loopMode = com.livevip.app.streaming.LoopMode.LOOP_ONE,
            width = preset.width,
            height = preset.height,
            fps = defaults.fps,
            videoBitrateKbps = defaults.videoBitrateKbps,
            canvasJson = CanvasConfig(
                aspect = aspect,
                width = preset.width,
                height = preset.height
            ).toJson().toString()
        )
        val playlist = if (isVideoMode && selectedVideo != null) {
            listOf(com.livevip.app.data.PlaylistItem(videoId = selectedVideo!!.id))
        } else {
            emptyList()
        }
        repo.async({ r ->
            val id = r.saveProject(project, destinations.toList(), destinationKeys.toMap(), playlist)
            r.projectBundle(id)?.project
        }) { saved ->
            if (isFinishing || isDestroyed || saved == null) return@async
            settings.currentProjectId = saved.id
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.project_created)
                .setMessage(
                    listOf(
                        saved.name,
                        "${aspect.label} • ${preset.width}×${preset.height}",
                        if (isVideoMode) "Video: ${selectedVideo?.name ?: "—"}" else "Camera",
                        "Destinations: ${destinations.size}",
                        getString(R.string.project_status_ready)
                    ).joinToString("\n")
                )
                .setPositiveButton(R.string.open_project) { _, _ -> openEditor(saved.id) }
                .setNegativeButton(R.string.edit_canvas) { _, _ -> openCanvas(saved.id) }
                .setCancelable(false)
                .show()
        }
    }

    private fun openEditor(projectId: Long) {
        startActivity(
            android.content.Intent(this, ProjectEditorActivity::class.java)
                .putExtra(ProjectEditorActivity.EXTRA_PROJECT_ID, projectId)
        )
        finish()
    }

    private fun openCanvas(projectId: Long) {
        startActivity(
            android.content.Intent(this, CanvasEditorActivity::class.java)
                .putExtra(CanvasEditorActivity.EXTRA_PROJECT_ID, projectId)
        )
        finish()
    }

    private fun defaultName(): String =
        "My Live ${System.currentTimeMillis() % 1000}"
}
