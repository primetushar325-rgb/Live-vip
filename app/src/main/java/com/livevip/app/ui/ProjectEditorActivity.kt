package com.livevip.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.PopupMenu
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.livevip.app.R
import com.livevip.app.data.Destination
import com.livevip.app.data.PlaylistItem
import com.livevip.app.data.Project
import com.livevip.app.data.ProjectRepository
import com.livevip.app.data.SecretsVault
import com.livevip.app.data.SettingsRepository
import com.livevip.app.databinding.ActivityProjectEditorBinding
import com.livevip.app.databinding.ItemDestinationRowBinding
import com.livevip.app.databinding.ItemOverlayRowBinding
import com.livevip.app.databinding.ItemPlaylistRowBinding
import com.livevip.app.databinding.ItemSceneRowBinding
import com.livevip.app.media.VideoItem
import com.livevip.app.media.VideoRepository
import com.livevip.app.overlay.OverlayConfig
import com.livevip.app.overlay.OverlayType
import com.livevip.app.overlay.SceneConfig
import com.livevip.app.streaming.CapabilityDetector
import com.livevip.app.streaming.LoopMode
import com.livevip.app.streaming.QualityProfiles
import com.livevip.app.streaming.StreamConfig
import com.livevip.app.streaming.StreamPlatform
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * NEW LIVE / EDIT LIVE — the project setup studio:
 * name → video/playlist → destinations → metadata → quality → audio →
 * overlays → scenes. Everything is saved as a reusable Project.
 *
 * Capability honesty: resolution options reflect the real hardware encoder
 * and source ("4K unavailable on this device" when true).
 */
class ProjectEditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PROJECT_ID = "project_id"
    }

    private lateinit var binding: ActivityProjectEditorBinding
    private lateinit var repo: ProjectRepository
    private lateinit var videos: VideoRepository
    private lateinit var vault: SecretsVault
    private lateinit var settings: SettingsRepository

    private val bgExecutor = Executors.newSingleThreadExecutor()

    private var projectId: Long = 0
    private var isVideoMode = true

    // Editor state
    private val playlist = mutableListOf<VideoItem>()
    private val destinations = mutableListOf<Destination>()
    private val destinationKeys = mutableMapOf<Long, String>()
    private val overlays = mutableListOf<OverlayConfig>()
    private val scenes = mutableListOf<SceneConfig>()
    private var loopMode = LoopMode.LOOP_ALL
    /** Part 2 live canvas persisted with the project. */
    private var canvasJson = ""

    // Adapters
    private lateinit var playlistAdapter: PlaylistAdapter
    private lateinit var destinationsAdapter: DestinationsAdapter
    private lateinit var overlaysAdapter: OverlaysAdapter
    private lateinit var scenesAdapter: ScenesAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProjectEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repo = ProjectRepository.get(this)
        videos = VideoRepository.get(this)
        vault = SecretsVault.get(this)
        settings = SettingsRepository.get(this)

        projectId = intent.getLongExtra(EXTRA_PROJECT_ID, 0)

        binding.btnBack.setOnClickListener { finish() }
        binding.editorTitle.text =
            getString(if (projectId == 0L) R.string.project_editor_new else R.string.project_editor_edit)

        setupAdapters()
        setupSourceToggle()
        setupLoopMode()
        setupBroadcastMode()
        setupQuality()
        setupAudio()
        setupOverlayButtons()
        binding.btnSaveProject.setOnClickListener { save() }
        binding.btnOpenCanvas.setOnClickListener {
            save(finishAfter = false) { openCanvasEditor() }
        }

        if (projectId == 0L) {
            applyProject(defaultProject())
        } else {
            loadProject(projectId)
        }
    }

    private fun defaultProject(): Project {
        val defaults = StreamConfig.from(settings)
        return Project(
            name = "",
            mode = "VIDEO",
            loopMode = LoopMode.LOOP_ALL,
            width = defaults.videoWidth,
            height = defaults.videoHeight,
            fps = defaults.fps,
            videoBitrateKbps = defaults.videoBitrateKbps,
            audioBitrateKbps = defaults.audioBitrateKbps,
            sampleRate = defaults.sampleRate,
            stereo = defaults.stereo
        )
    }

    private fun renderCanvasSummary() {
        val canvas = com.livevip.app.overlay.CanvasConfig.fromJson(canvasJson)
        binding.canvasFormatSummary.text = if (canvas != null) {
            "${canvas.aspect.label} • ${canvas.resolutionLabel()} • ${canvas.transform.fitMode.label}"
        } else {
            getString(
                if (selectedWidth >= selectedHeight)
                    R.string.format_note_16_9 else R.string.format_note_9_16
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh canvas + layers after returning from the canvas editor.
        if (projectId != 0L && ::binding.isInitialized) {
            repo.async({ it.projectBundle(projectId) }) { bundle ->
                if (isFinishing || isDestroyed || bundle == null) return@async
                canvasJson = bundle.project.canvasJson
                overlays.clear()
                overlays += bundle.project.overlays
                scenes.clear()
                scenes += bundle.project.scenes
                overlaysAdapter.notifyDataSetChanged()
                if (com.livevip.app.overlay.CanvasConfig.fromJson(canvasJson) != null) {
                    // The canvas IS the encoded resolution — keep the quality
                    // section in sync with it (BroadcastPlan validates this).
                    setupQualityFor(
                        bundle.project.width, bundle.project.height,
                        bundle.project.fps, bundle.project.videoBitrateKbps
                    )
                }
                renderCanvasSummary()
            }
        }
    }

    private fun openCanvasEditor() {
        if (projectId == 0L) return
        startActivity(
            android.content.Intent(this, CanvasEditorActivity::class.java)
                .putExtra(CanvasEditorActivity.EXTRA_PROJECT_ID, projectId)
        )
    }

    private fun loadProject(id: Long) {
        repo.async({ it.projectBundle(id) }) { bundle ->
            if (isFinishing || isDestroyed || bundle == null) return@async
            applyProject(bundle.project)
            canvasJson = bundle.project.canvasJson
            destinations.clear()
            destinations += bundle.destinations
            destinations.forEach { dest ->
                vault.destinationKey(dest.id).takeIf { it.isNotBlank() }
                    ?.let { destinationKeys[dest.id] = it }
            }
            playlist.clear()
            playlist += bundle.playlist.mapNotNull { videos.byId(it.videoId) }
            destinationsAdapter.notifyDataSetChanged()
            playlistAdapter.notifyDataSetChanged()
            // Quality options depend on the first playlist item (source caps).
            setupQualityFor(
                bundle.project.width, bundle.project.height,
                bundle.project.fps, bundle.project.videoBitrateKbps
            )
            refreshEmptyStates()
            renderCanvasSummary()
        }
    }

    private fun applyProject(project: Project) {
        binding.inputName.setText(project.name)
        binding.inputTitle.setText(project.title)
        binding.inputDescription.setText(project.description)
        isVideoMode = project.isVideoMode
        loopMode = project.loopMode
        binding.sourceToggle.check(
            if (isVideoMode) R.id.btnSourceVideo else R.id.btnSourceCamera
        )
        binding.playlistSection.visibility =
            if (isVideoMode) View.VISIBLE else View.GONE
        binding.radioDirect.isChecked = project.broadcastMode == com.livevip.app.streaming.BroadcastMode.DIRECT
        binding.radioRelay.isChecked = project.broadcastMode == com.livevip.app.streaming.BroadcastMode.SMART_RELAY
        binding.sliderVideoVolume.value = (project.videoVolume * 100f).roundToInt().coerceIn(0, 100).toFloat()
        binding.sliderMicVolume.value = (project.micVolume * 100f).roundToInt().coerceIn(0, 100).toFloat()
        binding.switchMicDefault.isChecked = project.micEnabledByDefault
        binding.switchEcho.isChecked = project.echoCanceler
        binding.switchNoise.isChecked = project.noiseSuppressor
        overlays.clear()
        overlays += project.overlays
        scenes.clear()
        scenes += project.scenes
        overlaysAdapter.notifyDataSetChanged()
        scenesAdapter.notifyDataSetChanged()
        selectLoopMode(loopMode)
        setupQualityFor(project.width, project.height, project.fps, project.videoBitrateKbps)
        refreshEmptyStates()
    }

    // ------------------------------------------------------------------
    // Setup
    // ------------------------------------------------------------------

    private fun setupAdapters() {
        playlistAdapter = PlaylistAdapter()
        binding.playlistList.layoutManager = LinearLayoutManager(this)
        binding.playlistList.adapter = playlistAdapter

        destinationsAdapter = DestinationsAdapter()
        binding.destinationsList.layoutManager = LinearLayoutManager(this)
        binding.destinationsList.adapter = destinationsAdapter

        overlaysAdapter = OverlaysAdapter()
        binding.overlaysList.layoutManager = LinearLayoutManager(this)
        binding.overlaysList.adapter = overlaysAdapter

        scenesAdapter = ScenesAdapter()
        binding.scenesList.layoutManager = LinearLayoutManager(this)
        binding.scenesList.adapter = scenesAdapter
    }

    private fun setupSourceToggle() {
        binding.sourceToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            isVideoMode = checkedId == R.id.btnSourceVideo
            binding.playlistSection.visibility =
                if (isVideoMode) View.VISIBLE else View.GONE
        }
    }

    private fun setupLoopMode() {
        val labels = listOf(
            LoopMode.LOOP_ALL.label,
            LoopMode.LOOP_ONE.label,
            LoopMode.SHUFFLE.label,
            LoopMode.PLAY_ONCE.label
        )
        (binding.loopModeDropdown as AutoCompleteTextView).apply {
            setAdapter(ArrayAdapter(context, android.R.layout.simple_list_item_1, labels))
            setOnClickListener { showDropDown() }
        }
        binding.loopModeDropdown.setOnItemClickListener { _, _, position, _ ->
            selectLoopMode(
                when (position) {
                    0 -> LoopMode.LOOP_ALL
                    1 -> LoopMode.LOOP_ONE
                    2 -> LoopMode.SHUFFLE
                    else -> LoopMode.PLAY_ONCE
                }
            )
        }
    }

    private fun selectLoopMode(mode: LoopMode) {
        loopMode = mode
        binding.loopModeDropdown.setText(mode.label, false)
        binding.loopModeDescription.text = when (mode) {
            LoopMode.LOOP_ALL -> getString(R.string.loop_all_desc)
            LoopMode.LOOP_ONE -> getString(R.string.loop_one_desc)
            LoopMode.SHUFFLE -> getString(R.string.shuffle_desc)
            LoopMode.PLAY_ONCE -> getString(R.string.play_once_desc)
        }
    }

    private fun setupBroadcastMode() {
        binding.radioDirect.setOnCheckedChangeListener { _, _ -> /* state read on save */ }
        binding.radioRelay.setOnCheckedChangeListener { _, _ -> /* state read on save */ }
    }

    /** Quality dropdowns with REAL capability gating. */
    private var selectedWidth = 1280
    private var selectedHeight = 720
    private var selectedFps = 30
    private var selectedBitrate = 2500

    private fun setupQualityFor(width: Int, height: Int, fps: Int, bitrate: Int) {
        selectedWidth = width
        selectedHeight = height
        selectedFps = fps
        selectedBitrate = bitrate

        val source = playlist.firstOrNull()
        val sourceWidth = source?.displayWidth ?: 0
        val sourceHeight = source?.displayHeight ?: 0
        val support = CapabilityDetector.resolutionSupport(sourceWidth, sourceHeight)

        val labels = support.map { entry ->
            if (entry.supported) entry.preset.label
            else entry.preset.label + " — ✗"
        }
        (binding.resolutionDropdown as AutoCompleteTextView).apply {
            setAdapter(ArrayAdapter(context, android.R.layout.simple_list_item_1, labels))
            setOnClickListener { showDropDown() }
        }
        val current = support.firstOrNull { it.preset.width == width && it.preset.height == height }
            ?: support.firstOrNull { it.preset.height == 720 }
        current?.let { binding.resolutionDropdown.setText(it.preset.label, false) }
        updateResolutionNote(current)

        binding.resolutionDropdown.setOnItemClickListener { _, _, position, _ ->
            val entry = support[position]
            if (entry.supported) {
                selectedWidth = entry.preset.width
                selectedHeight = entry.preset.height
                binding.resolutionDropdown.setText(entry.preset.label, false)
                updateResolutionNote(entry)
                refreshBitrateOptions()
            } else {
                Snackbar.make(binding.root, entry.reason ?: "", Snackbar.LENGTH_LONG).show()
                binding.resolutionDropdown.setText(
                    (support.firstOrNull { it.supported }?.preset?.label ?: "720p"), false
                )
            }
        }

        refreshFpsOptions(source?.fps ?: 30)
        refreshBitrateOptions()
    }

    private fun updateResolutionNote(entry: CapabilityDetector.ResolutionSupport?) {
        binding.resolutionSupportNote.text = when {
            entry == null -> getString(R.string.rate_control_note)
            entry.supported -> getString(
                R.string.keyframe_label, QualityProfiles.KEYFRAME_INTERVAL_SEC
            ) + " • " + getString(R.string.rate_control_note)
            else -> entry.reason
        }
    }

    private fun refreshFpsOptions(sourceFps: Int) {
        val options = CapabilityDetector.supportedFpsOptions(sourceFps)
        val labels = options.map { "$it fps" }
        (binding.fpsDropdown as AutoCompleteTextView).apply {
            setAdapter(ArrayAdapter(context, android.R.layout.simple_list_item_1, labels))
            setOnClickListener { showDropDown() }
        }
        val current = if (selectedFps in options) selectedFps else options.lastOrNull() ?: 30
        selectedFps = current
        binding.fpsDropdown.setText("$current fps", false)
        binding.fpsDropdown.setOnItemClickListener { _, _, position, _ ->
            selectedFps = options[position]
            binding.fpsDropdown.setText("$selectedFps fps", false)
        }
    }

    private fun refreshBitrateOptions() {
        val options = QualityProfiles.bitrateOptionsFor(selectedHeight)
        val labels = options.map { if (it >= 1000) "%.1f Mbps".format(Locale.US, it / 1000f) else "$it kbps" }
        (binding.bitrateDropdown as AutoCompleteTextView).apply {
            setAdapter(ArrayAdapter(context, android.R.layout.simple_list_item_1, labels))
            setOnClickListener { showDropDown() }
        }
        val recommended = QualityProfiles.recommendedBitrateKbps(selectedHeight, selectedFps)
        if (selectedBitrate !in options) selectedBitrate = recommended
        binding.bitrateDropdown.setText(bitrateLabel(selectedBitrate), false)
        binding.bitrateDropdown.setOnItemClickListener { _, _, position, _ ->
            selectedBitrate = options[position]
            binding.bitrateDropdown.setText(bitrateLabel(selectedBitrate), false)
        }
    }

    private fun bitrateLabel(kbps: Int): String =
        if (kbps >= 1000) "%.1f Mbps".format(Locale.US, kbps / 1000f) else "$kbps kbps"

    private fun setupQuality() {
        // populated by setupQualityFor when the project loads / playlist changes
    }

    private fun setupAudio() {
        // Sliders and switches read on save.
    }

    private fun setupOverlayButtons() {
        binding.btnAddVideo.setOnClickListener { showVideoPickerDialog() }
        binding.btnAddDestination.setOnClickListener { editDestination(null) }
        binding.btnAddOverlay.setOnClickListener { editOverlay(null) }
        binding.btnAddScene.setOnClickListener { showSceneDialog(null) }
    }

    private fun refreshEmptyStates() {
        binding.playlistEmpty.visibility = if (playlist.isEmpty()) View.VISIBLE else View.GONE
        binding.overlaysEmpty.visibility = if (overlays.isEmpty()) View.VISIBLE else View.GONE
        binding.scenesEmpty.visibility = if (scenes.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun newInputLayout(hint: String): TextInputLayout =
        TextInputLayout(this, null, com.google.android.material.R.attr.textInputStyle)
            .apply { this.hint = hint }

    // ------------------------------------------------------------------
    // Playlist editing
    // ------------------------------------------------------------------

    private fun showVideoPickerDialog() {
        val items = videos.all()
        if (items.isEmpty()) {
            Snackbar.make(binding.root, R.string.empty_library, Snackbar.LENGTH_LONG)
                .setAction(R.string.import_video) { startActivity(
                    android.content.Intent(this, VideoLibraryActivity::class.java)
                ) }
                .show()
            return
        }
        // Validate audio-format homogeneity honestly as items are added.
        val reference = playlist.firstOrNull() ?: items.firstOrNull()
        val names = items.map {
            val compatible = reference == null || it.id == reference.id ||
                audioCompatible(reference, it)
            (if (compatible) "" else "⚠ ") + it.name + " • ${it.durationLabel()}"
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_video_to_playlist)
            .setItems(names) { _, which ->
                val item = items[which]
                val ref = playlist.firstOrNull()
                if (ref != null && !audioCompatible(ref, item)) {
                    Snackbar.make(
                        binding.root,
                        R.string.audio_format_mismatch,
                        Snackbar.LENGTH_LONG
                    ).show()
                    return@setItems
                }
                if (playlist.none { it.id == item.id }) {
                    playlist.add(item)
                    playlistAdapter.notifyItemInserted(playlist.size - 1)
                    refreshEmptyStates()
                    if (playlist.size == 1) setupQualityFor(
                        selectedWidth, selectedHeight, selectedFps, selectedBitrate
                    )
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun audioCompatible(a: VideoItem, b: VideoItem): Boolean {
        val aStereo = a.channels >= 2
        val bStereo = b.channels >= 2
        return (!a.hasAudio && !b.hasAudio) ||
            (a.hasAudio && b.hasAudio && a.sampleRate == b.sampleRate && aStereo == bStereo)
    }

    private inner class PlaylistAdapter :
        androidx.recyclerview.widget.RecyclerView.Adapter<PlaylistAdapter.Holder>() {

        inner class Holder(val b: ItemPlaylistRowBinding) :
            androidx.recyclerview.widget.RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemPlaylistRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = playlist.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = playlist[position]
            holder.b.playlistName.text = "${position + 1}. ${item.name}"
            holder.b.playlistMeta.text = "${item.resolutionLabel()} • ${item.fps} FPS • ${item.durationLabel()}"
            holder.b.playlistThumb.setImageDrawable(null)
            com.livevip.app.data.ThumbnailCache.get(this@ProjectEditorActivity)
                .load(item.id, item.uri) { bmp ->
                    if (!isFinishing && bmp != null) holder.b.playlistThumb.setImageBitmap(bmp)
                }
            holder.b.btnMoveUp.setOnClickListener { anchor ->
                val menu = PopupMenu(this@ProjectEditorActivity, anchor)
                menu.menu.add("▲ Move up").setEnabled(position > 0)
                    .setOnMenuItemClickListener {
                        move(position, position - 1); true
                    }
                menu.menu.add("▼ Move down").setEnabled(position < playlist.size - 1)
                    .setOnMenuItemClickListener {
                        move(position, position + 1); true
                    }
                menu.show()
            }
            holder.b.btnRemoveItem.setOnClickListener {
                val idx = holder.adapterPosition
                if (idx != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                    playlist.removeAt(idx)
                    playlistAdapter.notifyItemRemoved(idx)
                    refreshEmptyStates()
                }
            }
        }

        private fun move(from: Int, to: Int) {
            if (from == to) return
            val item = playlist.removeAt(from)
            playlist.add(to, item)
            playlistAdapter.notifyItemMoved(from, to)
            playlistAdapter.notifyDataSetChanged()
        }
    }

    // ------------------------------------------------------------------
    // Destination editing
    // ------------------------------------------------------------------

    private fun editDestination(existing: Destination?) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        fun field(hint: String, value: String, inputType: Int): Pair<TextInputEditText, TextInputLayout> {
            val layout = newInputLayout(hint)
            val edit = TextInputEditText(layout.context).apply {
                setText(value)
                this.inputType = inputType
            }
            layout.addView(edit)
            container.addView(layout)
            return edit to layout
        }

        val (nameEdit, _) = field(
            getString(R.string.destination_name_hint),
            existing?.name ?: "",
            android.text.InputType.TYPE_CLASS_TEXT
        )
        val (urlEdit, _) = field(
            getString(R.string.rtmp_url_hint),
            existing?.url ?: "",
            android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        )
        val (keyEdit, _) = field(
            getString(R.string.stream_key_hint),
            existing?.let { destinationKeys[it.id] } ?: "",
            android.text.InputType.TYPE_CLASS_TEXT
        )

        val platforms = StreamPlatform.entries.map { it.label }
        val platformInput = AutoCompleteTextView(this).apply {
            setText(platforms[existing?.platform?.ordinal ?: 0], false)
            setAdapter(ArrayAdapter(this@ProjectEditorActivity, android.R.layout.simple_list_item_1, platforms))
            inputType = android.text.InputType.TYPE_NULL
        }
        val platformLayout = newInputLayout(getString(R.string.dest_platform_label))
        platformLayout.addView(platformInput)
        container.addView(platformLayout)

        MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) R.string.add_destination else R.string.edit)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = nameEdit.text?.toString()?.trim().orEmpty()
                    .ifEmpty { platforms[0] + " ${destinations.size + 1}" }
                val url = urlEdit.text?.toString()?.trim().orEmpty()
                val key = keyEdit.text?.toString()?.trim().orEmpty()
                val platform = StreamPlatform.entries.getOrNull(
                    platforms.indexOf(platformInput.text.toString())
                ) ?: StreamPlatform.CUSTOM_RTMP
                if (!url.startsWith("rtmp")) {
                    Snackbar.make(binding.root, R.string.error_url_invalid, Snackbar.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                if (existing == null) {
                    val tempId = System.currentTimeMillis()
                    destinations += Destination(
                        id = tempId, name = name, platform = platform, url = url, enabled = true
                    )
                    if (key.isNotBlank()) destinationKeys[tempId] = key
                    destinationsAdapter.notifyItemInserted(destinations.size - 1)
                } else {
                    val index = destinations.indexOfFirst { it.id == existing.id }
                    if (index >= 0) {
                        destinations[index] = existing.copy(
                            name = name, platform = platform, url = url
                        )
                        if (key.isNotBlank()) destinationKeys[existing.id] = key
                        destinationsAdapter.notifyItemChanged(index)
                    }
                }
            }
            .show()
    }

    private inner class DestinationsAdapter :
        androidx.recyclerview.widget.RecyclerView.Adapter<DestinationsAdapter.Holder>() {

        inner class Holder(val b: ItemDestinationRowBinding) :
            androidx.recyclerview.widget.RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemDestinationRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = destinations.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val dest = destinations[position]
            holder.b.destName.text = dest.name
            val hasKey = destinationKeys[dest.id].orEmpty().isNotBlank() ||
                vault.destinationKey(dest.id).isNotBlank()
            holder.b.destUrl.text = "${dest.url} • key " + if (hasKey) "✓" else "✗"
            holder.b.destEnabled.isChecked = dest.enabled
            holder.b.destEnabled.setOnCheckedChangeListener { _, checked ->
                val index = destinations.indexOfFirst { it.id == dest.id }
                if (index >= 0) destinations[index] = destinations[index].copy(enabled = checked)
            }
            holder.b.btnEditDest.setOnClickListener { editDestination(dest) }
            holder.b.btnRemoveDest.setOnClickListener {
                val idx = destinations.indexOfFirst { it.id == dest.id }
                if (idx >= 0) {
                    destinations.removeAt(idx)
                    destinationKeys.remove(dest.id)
                    destinationsAdapter.notifyItemRemoved(idx)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Overlay editing
    // ------------------------------------------------------------------

    private fun editOverlay(existing: OverlayConfig?) {
        val types = OverlayType.entries.map { it.label }.toTypedArray()
        var selectedType = existing?.type ?: OverlayType.TEXT

        val pad = (16 * resources.displayMetrics.density).toInt()
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val typeInput = AutoCompleteTextView(this).apply {
            setText(selectedType.label, false)
            setAdapter(ArrayAdapter(this@ProjectEditorActivity, android.R.layout.simple_list_item_1, types))
            inputType = android.text.InputType.TYPE_NULL
        }
        val typeLayout = newInputLayout("Type")
        typeLayout.addView(typeInput)
        container.addView(typeLayout)

        fun field(hint: String, value: String, numeric: Boolean = false): TextInputEditText {
            val layout = newInputLayout(hint)
            val edit = TextInputEditText(layout.context).apply {
                setText(value)
                inputType = if (numeric)
                    android.text.InputType.TYPE_CLASS_NUMBER
                else android.text.InputType.TYPE_CLASS_TEXT
            }
            layout.addView(edit)
            container.addView(layout)
            return edit
        }

        val textEdit = field(getString(R.string.overlay_text_hint), existing?.text ?: "")
        val secondaryEdit = field(getString(R.string.overlay_secondary_hint), existing?.secondaryText ?: "")
        val xEdit = field("X (0–100)", ((existing?.positionX ?: 0.5f) * 100).roundToInt().toString(), true)
        val yEdit = field("Y (0–100)", ((existing?.positionY ?: 0.88f) * 100).roundToInt().toString(), true)
        val sizeEdit = field(getString(R.string.overlay_size_hint), ((existing?.scale ?: 0.35f) * 100).roundToInt().toString(), true)

        MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) R.string.add_overlay else R.string.edit)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val text = textEdit.text?.toString().orEmpty()
                val type = selectedType
                val config = (existing ?: OverlayConfig(id = System.currentTimeMillis())).copy(
                    type = type,
                    text = text,
                    secondaryText = secondaryEdit.text?.toString().orEmpty(),
                    positionX = (xEdit.text?.toString()?.toFloatOrNull() ?: 50f) / 100f,
                    positionY = (yEdit.text?.toString()?.toFloatOrNull() ?: 88f) / 100f,
                    scale = ((sizeEdit.text?.toString()?.toFloatOrNull() ?: 35f) / 100f)
                        .coerceIn(0.05f, 1f)
                )
                if (existing == null) {
                    overlays += config
                    overlaysAdapter.notifyItemInserted(overlays.size - 1)
                } else {
                    val index = overlays.indexOfFirst { it.id == existing.id }
                    if (index >= 0) {
                        overlays[index] = config
                        overlaysAdapter.notifyItemChanged(index)
                    }
                }
                refreshEmptyStates()
            }
            .show()

        typeInput.setOnItemClickListener { _, _, position, _ ->
            selectedType = OverlayType.entries[position]
        }
    }

    private inner class OverlaysAdapter :
        androidx.recyclerview.widget.RecyclerView.Adapter<OverlaysAdapter.Holder>() {

        inner class Holder(val b: ItemOverlayRowBinding) :
            androidx.recyclerview.widget.RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemOverlayRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = overlays.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val config = overlays[position]
            holder.b.overlayName.text = "${config.type.label} — " +
                (config.text.ifEmpty { config.secondaryText.ifEmpty { "…" } })
            holder.b.overlayDetail.text = "X ${(config.positionX * 100).roundToInt()}% " +
                "Y ${(config.positionY * 100).roundToInt()}% • ${(config.scale * 100).roundToInt()}%"
            holder.b.overlayIcon.setImageResource(
                when (config.type) {
                    OverlayType.IMAGE, OverlayType.WATERMARK -> R.drawable.ic_image
                    OverlayType.CLOCK, OverlayType.COUNTDOWN -> R.drawable.ic_clock
                    OverlayType.TEXT, OverlayType.LOWER_THIRD, OverlayType.SCROLLING_TEXT ->
                        R.drawable.ic_text_overlay
                    OverlayType.BORDER -> R.drawable.ic_layers
                    OverlayType.VIDEO -> R.drawable.ic_folder_video
                    OverlayType.GIF -> R.drawable.ic_analytics
                    OverlayType.SUBSCRIBE -> R.drawable.ic_broadcast
                    OverlayType.BACKGROUND -> R.drawable.ic_image
                }
            )
            holder.b.overlayEnabled.isChecked = config.enabled
            holder.b.overlayEnabled.setOnCheckedChangeListener { _, checked ->
                val index = overlays.indexOfFirst { it.id == config.id }
                if (index >= 0) overlays[index] = overlays[index].copy(enabled = checked)
            }
            holder.b.btnRemoveOverlay.setOnClickListener {
                val idx = overlays.indexOfFirst { it.id == config.id }
                if (idx >= 0) {
                    overlays.removeAt(idx)
                    overlaysAdapter.notifyItemRemoved(idx)
                    refreshEmptyStates()
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Scene editing
    // ------------------------------------------------------------------

    private fun showSceneDialog(existing: SceneConfig?) {
        if (overlays.isEmpty()) {
            Snackbar.make(binding.root, R.string.no_overlays, Snackbar.LENGTH_LONG).show()
            return
        }
        val input = TextInputEditText(this).apply {
            setText(existing?.name ?: "")
            hint = getString(R.string.scene_name_hint)
            setPadding(48, 48, 48, 24)
        }
        val selected = (existing?.overlayIds ?: emptyList()).toMutableSet()
        val names = overlays.map { "${it.type.label} — ${it.text.ifEmpty { "…" }}" }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_scene)
            .setView(input)
            .setMultiChoiceItems(names, overlays.map { it.id in selected }.toBooleanArray()) { _, which, checked ->
                val id = overlays[which].id
                if (checked) selected += id else selected -= id
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) return@setPositiveButton
                if (existing == null) {
                    scenes += SceneConfig(id = System.currentTimeMillis(), name = name, overlayIds = selected.toList())
                    scenesAdapter.notifyItemInserted(scenes.size - 1)
                } else {
                    val index = scenes.indexOfFirst { it.id == existing.id }
                    if (index >= 0) {
                        scenes[index] = existing.copy(name = name, overlayIds = selected.toList())
                        scenesAdapter.notifyItemChanged(index)
                    }
                }
                refreshEmptyStates()
            }
            .show()
    }

    private inner class ScenesAdapter :
        androidx.recyclerview.widget.RecyclerView.Adapter<ScenesAdapter.Holder>() {

        inner class Holder(val b: ItemSceneRowBinding) :
            androidx.recyclerview.widget.RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemSceneRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = scenes.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val scene = scenes[position]
            holder.b.sceneName.text = scene.name
            holder.b.root.setOnClickListener { showSceneDialog(scene) }
            holder.b.btnRemoveScene.setOnClickListener {
                val idx = scenes.indexOfFirst { it.id == scene.id }
                if (idx >= 0) {
                    scenes.removeAt(idx)
                    scenesAdapter.notifyItemRemoved(idx)
                    refreshEmptyStates()
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Save
    // ------------------------------------------------------------------

    private fun save(finishAfter: Boolean = true, onDone: () -> Unit = {}) {
        val name = binding.inputName.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) {
            binding.nameLayout.error = getString(R.string.project_name_required)
            return
        }
        binding.nameLayout.error = null
        if (isVideoMode && playlist.isEmpty()) {
            Snackbar.make(binding.root, R.string.playlist_empty, Snackbar.LENGTH_LONG).show()
            return
        }
        if (destinations.isEmpty()) {
            Snackbar.make(binding.root, R.string.add_destination, Snackbar.LENGTH_LONG).show()
            return
        }

        val project = Project(
            id = projectId,
            name = name,
            mode = if (isVideoMode) "VIDEO" else "CAMERA",
            loopMode = loopMode,
            broadcastMode = if (binding.radioRelay.isChecked)
                com.livevip.app.streaming.BroadcastMode.SMART_RELAY
            else com.livevip.app.streaming.BroadcastMode.DIRECT,
            title = binding.inputTitle.text?.toString()?.trim().orEmpty(),
            description = binding.inputDescription.text?.toString()?.trim().orEmpty(),
            width = selectedWidth,
            height = selectedHeight,
            fps = selectedFps,
            videoBitrateKbps = selectedBitrate,
            audioBitrateKbps = 128,
            videoVolume = binding.sliderVideoVolume.value / 100f,
            micVolume = binding.sliderMicVolume.value / 100f,
            micEnabledByDefault = binding.switchMicDefault.isChecked,
            echoCanceler = binding.switchEcho.isChecked,
            noiseSuppressor = binding.switchNoise.isChecked,
            overlays = overlays.toList(),
            scenes = scenes.toList(),
            canvasJson = canvasJson,
            createdAt = System.currentTimeMillis()
        )

        val playlistItems = playlist.map { PlaylistItem(videoId = it.id) }
        val keys = destinationKeys.toMap()

        bgExecutor.execute {
            val id = repo.saveProject(project, destinations.toList(), keys, playlistItems)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                settings.currentProjectId = id
                projectId = id
                Snackbar.make(binding.root, R.string.project_saved, Snackbar.LENGTH_SHORT).show()
                onDone()
                if (finishAfter) finish()
            }
        }
    }
}
