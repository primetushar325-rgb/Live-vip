package com.livevip.app.ui

import android.annotation.SuppressLint
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.livevip.app.R
import com.livevip.app.data.OverlayTemplate
import com.livevip.app.data.Project
import com.livevip.app.data.ProjectRepository
import com.livevip.app.data.SettingsRepository
import com.livevip.app.databinding.ActivityCanvasEditorBinding
import com.livevip.app.databinding.ItemLayerRowBinding
import com.livevip.app.databinding.ItemSceneRowBinding
import com.livevip.app.databinding.ItemTemplateRowBinding
import com.livevip.app.media.VideoRepository
import com.livevip.app.overlay.CanvasAspect
import com.livevip.app.overlay.CanvasConfig
import com.livevip.app.overlay.OverlayAnimation
import com.livevip.app.overlay.OverlayConfig
import com.livevip.app.overlay.OverlayType
import com.livevip.app.overlay.SceneConfig
import com.livevip.app.streaming.CapabilityDetector
import com.livevip.app.streaming.CanvasPresets
import com.livevip.app.streaming.LiveStreamingManager
import com.livevip.app.streaming.StreamState
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * PROFESSIONAL LIVE CANVAS / SCENE EDITOR (Part 2).
 *
 * Everything composed here is REAL: the preview runs through the same GL
 * filter chain as the encoder (RootEncoder draws the filtered frames to both
 * the preview surface and the encoder surface), so the canvas preview is the
 * broadcast. Layers are GL filters composited into the encoded frames —
 * nothing here is UI-only decoration.
 *
 * While LIVE: aspect/resolution are locked (encoder is fixed), but layers,
 * text, scenes, transforms and animations can all change live — RTMP stays
 * connected because every change is a filter add/remove/update only.
 */
class CanvasEditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PROJECT_ID = "project_id"
    }

    private lateinit var binding: ActivityCanvasEditorBinding
    private lateinit var repo: ProjectRepository
    private lateinit var videos: VideoRepository
    private lateinit var settings: SettingsRepository

    private var projectId: Long = 0
    private var project: Project? = null
    private var canvas = CanvasConfig()
    private var firstVideo: com.livevip.app.media.VideoItem? = null

    /** z-order: index 0 = bottom (drawn first). */
    private val layers = mutableListOf<OverlayConfig>()
    private val scenes = mutableListOf<SceneConfig>()
    private var selectedLayerId: Long? = null
    private var activeSceneId: Long? = null

    private val uiHandler = Handler(Looper.getMainLooper())
    private var surfaceReady = false

    // Gesture state.
    private var gestureMode = 0 // 0 none, 1 drag, 2 pinch/rotate
    private var lastX = 0f
    private var lastY = 0f
    private var pinchStartDist = 0f
    private var pinchStartScale = 1f
    private var rotateStartAngle = 0f
    private var rotateStartRotation = 0f

    // Pickers.
    private val imagePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) addImageLayer(uri) }

    private val videoPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) addVideoLayer(uri) }

    private val gifPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) addGifLayer(uri) }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCanvasEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repo = ProjectRepository.get(this)
        videos = VideoRepository.get(this)
        settings = SettingsRepository.get(this)
        projectId = intent.getLongExtra(EXTRA_PROJECT_ID, 0)

        binding.btnCanvasBack.setOnClickListener { finish() }
        binding.btnCanvasSave.setOnClickListener { save() }

        setupSurface()
        setupFormatRow()
        setupTransformRow()
        setupAids()
        setupTabs()
        setupLayerButtons()

        load()
        LiveStreamingManager.addListener(managerListener)
    }

    override fun onResume() {
        super.onResume()
        startPreview()
    }

    override fun onPause() {
        if (!LiveStreamingManager.isStreaming) {
            LiveStreamingManager.stopPreview()
        }
        super.onPause()
    }

    override fun onDestroy() {
        LiveStreamingManager.removeListener(managerListener)
        super.onDestroy()
    }

    private val managerListener = object : LiveStreamingManager.Listener {
        override fun onStateChanged(state: StreamState, message: String?) {
            if (state == StreamState.OFFLINE || state == StreamState.ERROR) {
                uiHandler.post { if (!isFinishing) startPreview() }
            }
        }

        override fun onStatsChanged(stats: com.livevip.app.streaming.StreamStats) = Unit
    }

    // ------------------------------------------------------------------
    // Load / save
    // ------------------------------------------------------------------

    private fun load() {
        if (projectId == 0L) {
            finish()
            return
        }
        repo.async({ r -> r.projectBundle(projectId) }) { bundle ->
            if (isFinishing || isDestroyed || bundle == null) return@async
            project = bundle.project
            firstVideo = bundle.playlist
                .firstOrNull()?.let { videos.byId(it.videoId) }
            layers.clear()
            layers += bundle.project.overlays
            scenes.clear()
            scenes += bundle.project.scenes
            val saved = bundle.project.canvas
            canvas = saved ?: run {
                val p = bundle.project
                CanvasConfig(width = p.width, height = p.height).copyWithResolution(p.width, p.height)
            }
            selectedLayerId = null
            renderAll()
            startPreview()
        }
    }

    private fun save() {
        val p = project ?: return
        val updated = p.copy(
            overlays = layers.toList(),
            scenes = scenes.toList(),
            width = canvas.width,
            height = canvas.height,
            canvasJson = canvas.toJson().toString()
        )
        repo.async({ r -> r.updateProjectShell(updated); true }) {
            Snackbar.make(binding.root, R.string.canvas_saved, Snackbar.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------------
    // Preview — same GL chain as the encoder, so this IS the broadcast view.
    // ------------------------------------------------------------------

    private fun setupSurface() {
        binding.canvasPreviewSurface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceReady = true
                startPreview()
            }

            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) = Unit

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
            }
        })
        setupGestures()
    }

    private fun startPreview() {
        if (!surfaceReady || isFinishing || isDestroyed) return
        val p = project ?: return
        if (LiveStreamingManager.isStreaming) return // engine owns the preview while live
        val video = firstPlaylistVideo()
        val error = LiveStreamingManager.previewCanvas(
            this, binding.canvasPreviewSurface,
            video?.uriParsed(), canvas,
            layers.filter { it.enabled }
        )
        if (error != null) {
            binding.canvasFormatNote.text = error
        }
        applyPreviewAspect()
    }

    private fun firstPlaylistVideo(): com.livevip.app.media.VideoItem? = firstVideo

    /** Size the preview surface to the canvas aspect (preview = broadcast frame). */
    private fun applyPreviewAspect() {
        val container = binding.previewContainer
        container.post {
            val cw = container.width.coerceAtLeast(1)
            val ch = container.height.coerceAtLeast(1)
            val canvasAspect = canvas.width.toFloat() / canvas.height.toFloat()
            var w = cw.toFloat()
            var h = w / canvasAspect
            if (h > ch) {
                h = ch.toFloat()
                w = h * canvasAspect
            }
            binding.canvasPreviewSurface.layoutParams = (
                binding.canvasPreviewSurface.layoutParams as ViewGroup.MarginLayoutParams
                ).apply {
                width = w.toInt()
                height = h.toInt()
            }
            binding.canvasAids.layoutParams = binding.canvasAids.layoutParams.apply {
                width = w.toInt()
                height = h.toInt()
            }
            binding.canvasPreviewSurface.requestLayout()
            binding.canvasAids.requestLayout()
        }
    }

    // ------------------------------------------------------------------
    // Format row — aspect presets + validated resolutions
    // ------------------------------------------------------------------

    private fun setupFormatRow() {
        binding.btnAspect169.setOnClickListener { pickAspect(CanvasAspect.LANDSCAPE_16_9) }
        binding.btnAspect916.setOnClickListener { pickAspect(CanvasAspect.PORTRAIT_9_16) }
        binding.btnAspect11.setOnClickListener { pickAspect(CanvasAspect.SQUARE_1_1) }
        binding.btnAspect45.setOnClickListener { pickAspect(CanvasAspect.PORTRAIT_4_5) }
        binding.btnAspectCustom.setOnClickListener {
            if (!LiveStreamingManager.isStreaming) showCustomResolutionDialog()
            else Snackbar.make(binding.root, R.string.canvas_locked_while_live, Snackbar.LENGTH_LONG).show()
        }
        renderResolutionOptions()
    }

    /** Aspect preset tap: pick the best validated resolution for it. */
    private fun pickAspect(aspect: CanvasAspect) {
        if (LiveStreamingManager.isStreaming) {
            Snackbar.make(binding.root, R.string.canvas_locked_while_live, Snackbar.LENGTH_LONG).show()
            renderFormat()
            return
        }
        val caps = CapabilityDetector.videoEncoderCaps()
        val options = CanvasPresets.optionsFor(aspect)
        val preset = options.firstOrNull { caps == null || caps.supports(it.width, it.height) }
            ?: options.last()
        canvas = CanvasConfig(
            aspect = aspect, width = preset.width, height = preset.height,
            backgroundColor = canvas.backgroundColor, transform = canvas.transform
        )
        renderFormat()
        renderResolutionOptions()
        startPreview()
    }

    private fun renderResolutionOptions() {
        val options = CanvasPresets.optionsFor(canvas.aspect)
        val labels = options.map { it.label } + getString(R.string.custom)
        (binding.canvasResolution as AutoCompleteTextView).apply {
            setAdapter(ArrayAdapter(context, android.R.layout.simple_list_item_1, labels))
            setOnClickListener { showDropDown() }
        }
        val current = options.firstOrNull { it.width == canvas.width && it.height == canvas.height }
        binding.canvasResolution.setText(current?.label ?: canvas.resolutionLabel(), false)
        binding.canvasResolution.setOnItemClickListener { _, _, position, _ ->
            if (position < options.size) {
                val preset = options[position]
                canvas = canvas.copyWithResolution(preset.width, preset.height)
                renderFormat()
                startPreview()
            } else {
                showCustomResolutionDialog()
            }
        }
    }

    private fun showCustomResolutionDialog() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        fun input(hint: String): TextInputEditText {
            val layout = TextInputLayout(this, null,
                com.google.android.material.R.attr.textInputStyle).apply { this.hint = hint }
            val edit = TextInputEditText(layout.context).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
            }
            layout.addView(edit)
            container.addView(layout)
            return edit
        }
        val wEdit = input("Width (even, e.g. 1080)")
        val hEdit = input("Height (even, e.g. 1920)")
        wEdit.setText(canvas.width.toString())
        hEdit.setText(canvas.height.toString())
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.custom)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.apply) { _, _ ->
                val w = wEdit.text?.toString()?.toIntOrNull() ?: return@setPositiveButton
                val h = hEdit.text?.toString()?.toIntOrNull() ?: return@setPositiveButton
                val fps = project?.fps ?: 30
                val bitrate = project?.videoBitrateKbps ?: 2500
                val result = CanvasPresets.validate(
                    w, h, fps, bitrate, CapabilityDetector.videoEncoderCaps()
                )
                if (!result.supported) {
                    Snackbar.make(binding.root, result.reason ?: "Invalid", Snackbar.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                canvas = canvas.copyWithResolution(w, h)
                renderFormat()
                renderResolutionOptions()
                startPreview()
            }
            .show()
    }

    private fun renderFormat() {
        val aspect = canvas.aspect
        binding.btnAspect169.isSelected = aspect == CanvasAspect.LANDSCAPE_16_9
        binding.btnAspect916.isSelected = aspect == CanvasAspect.PORTRAIT_9_16
        binding.btnAspect11.isSelected = aspect == CanvasAspect.SQUARE_1_1
        binding.btnAspect45.isSelected = aspect == CanvasAspect.PORTRAIT_4_5
        binding.btnAspectCustom.isSelected = aspect == CanvasAspect.CUSTOM

        binding.canvasProjectName.text = project?.name ?: ""
        binding.canvasFormatLabel.text = "${aspect.label} • ${canvas.resolutionLabel()} • " +
            "${project?.fps ?: 30} FPS"

        // Honest validation note.
        val caps = CapabilityDetector.videoEncoderCaps()
        val v = CanvasPresets.validate(
            canvas.width, canvas.height, project?.fps ?: 30,
            project?.videoBitrateKbps ?: 2500, caps
        )
        binding.canvasFormatNote.text = if (v.supported) {
            "Encoder ready ✓ • ${canvas.resolutionLabel()} • previews = broadcast"
        } else {
            "✗ ${v.reason}"
        }
    }

    // ------------------------------------------------------------------
    // Transform row (main video)
    // ------------------------------------------------------------------

    private fun setupTransformRow() {
        binding.btnFit.setOnClickListener { updateTransform(canvas.transform.copy(fitMode = com.livevip.app.overlay.FitMode.FIT)) }
        binding.btnFill.setOnClickListener { updateTransform(canvas.transform.copy(fitMode = com.livevip.app.overlay.FitMode.FILL)) }
        binding.btnStretch.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.stretch)
                .setMessage("Stretch distorts the video to fill the canvas. Continue?")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.stretch) { _, _ ->
                    updateTransform(canvas.transform.copy(fitMode = com.livevip.app.overlay.FitMode.STRETCH))
                }
                .show()
        }
        binding.btnCenter.setOnClickListener {
            updateTransform(canvas.transform.copy(offsetX = 0f, offsetY = 0f))
        }
        binding.btnZoomIn.setOnClickListener {
            updateTransform(canvas.transform.copy(
                fitMode = com.livevip.app.overlay.FitMode.CUSTOM,
                scale = (canvas.transform.scale * 1.15f).coerceAtMost(8f)
            ))
        }
        binding.btnZoomOut.setOnClickListener {
            updateTransform(canvas.transform.copy(
                fitMode = com.livevip.app.overlay.FitMode.CUSTOM,
                scale = (canvas.transform.scale / 1.15f).coerceAtLeast(0.1f)
            ))
        }
        binding.btnRotateLeft.setOnClickListener {
            updateTransform(canvas.transform.copy(rotationDeg = canvas.transform.rotationDeg - 90f))
        }
        binding.btnRotateRight.setOnClickListener {
            updateTransform(canvas.transform.copy(rotationDeg = canvas.transform.rotationDeg + 90f))
        }
        binding.btnResetTransform.setOnClickListener {
            updateTransform(com.livevip.app.overlay.VideoTransform())
        }
    }

    /**
     * Live transform update — one thread-safe GL buffer swap, works both
     * while LIVE and in the editor preview. Encoder and RTMP untouched.
     */
    private fun updateTransform(t: com.livevip.app.overlay.VideoTransform) {
        canvas = canvas.copy(transform = t)
        LiveStreamingManager.updateVideoTransform(t)
        renderSelection()
    }

    // ------------------------------------------------------------------
    // Preview aids (grid / safe area / snap)
    // ------------------------------------------------------------------

    private fun setupAids() {
        binding.chipGrid.setOnCheckedChangeListener { _, checked ->
            binding.canvasAids.showGrid = checked
            binding.canvasAids.invalidate()
        }
        binding.chipSafeArea.setOnCheckedChangeListener { _, checked ->
            binding.canvasAids.showSafeArea = checked
            binding.canvasAids.invalidate()
        }
        binding.chipSnap.isChecked = true
    }

    private val snapEnabled get() = binding.chipSnap.isChecked

    // ------------------------------------------------------------------
    // Gestures: drag / pinch / rotate on the canvas
    // ------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        binding.canvasAids.setOnTouchListener { _, event -> handleGesture(event) }
    }

    private fun handleGesture(event: MotionEvent): Boolean {
        val view = binding.canvasAids
        val w = view.width.coerceAtLeast(1).toFloat()
        val h = view.height.coerceAtLeast(1).toFloat()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureMode = 1
                lastX = event.x
                lastY = event.y
                if (event.pointerCount == 1) selectAt(event.x / w, event.y / h)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    gestureMode = 2
                    pinchStartDist = spacing(event)
                    pinchStartScale = currentGestureScale()
                    rotateStartAngle = angle(event)
                    rotateStartRotation = currentGestureRotation()
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (gestureMode == 1 && event.pointerCount == 1) {
                    val dx = (event.x - lastX) / w
                    val dy = (event.y - lastY) / h
                    lastX = event.x
                    lastY = event.y
                    dragBy(dx, dy)
                } else if (gestureMode == 2 && event.pointerCount >= 2) {
                    val dist = spacing(event)
                    if (pinchStartDist > 10f && dist > 10f) {
                        val factor = (dist / pinchStartDist) * pinchStartScale / currentGestureScale()
                        scaleBy(factor)
                    }
                    val ang = angle(event)
                    val delta = ang - rotateStartAngle
                    if (kotlin.math.abs(delta) > 4f) {
                        rotateTo(rotateStartRotation + delta)
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP -> gestureMode = if (event.pointerCount == 2) 1 else 2

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                gestureMode = 0
                commitLayerChanges()
            }
        }
        return true
    }

    private fun spacing(e: MotionEvent): Float {
        val dx = e.getX(0) - e.getX(1)
        val dy = e.getY(0) - e.getY(1)
        return hypot(dx, dy)
    }

    private fun angle(e: MotionEvent): Float {
        val dx = e.getX(1) - e.getX(0)
        val dy = e.getY(1) - e.getY(0)
        return Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
    }

    /** Hit-test: top-most layer first, else the main video. */
    private fun selectAt(fx: Float, fy: Float) {
        for (index in layers.indices.reversed()) {
            val layer = layers[index]
            if (!layer.enabled || layer.locked) continue
            val rect = layerRect(index) ?: continue
            val pad = 0.02f
            if (fx >= rect.left - pad && fx <= rect.right + pad &&
                fy >= rect.top - pad && fy <= rect.bottom + pad
            ) {
                selectedLayerId = layer.id
                renderSelection()
                return
            }
        }
        selectedLayerId = null // main video
        renderSelection()
    }

    private fun indexOfSelected(): Int =
        selectedLayerId?.let { id -> layers.indexOfFirst { it.id == id } } ?: -1

    private fun currentGestureScale(): Float {
        val idx = indexOfSelected()
        return if (idx >= 0) layers[idx].scale else canvas.transform.scale
    }

    private fun currentGestureRotation(): Float =
        if (indexOfSelected() >= 0) layers[indexOfSelected()].rotation.toFloat()
        else canvas.transform.rotationDeg

    private fun dragBy(dx: Float, dy: Float) {
        val idx = indexOfSelected()
        if (idx >= 0) {
            var x = layers[idx].positionX + dx
            var y = layers[idx].positionY + dy
            if (snapEnabled) {
                if (kotlin.math.abs(x - 0.5f) < 0.035f) x = 0.5f
                if (kotlin.math.abs(y - 0.5f) < 0.035f) y = 0.5f
            }
            layers[idx] = layers[idx].copy(positionX = x.coerceIn(-0.5f, 1.5f), positionY = y.coerceIn(-0.5f, 1.5f))
            applyLayersLive()
            renderSelection()
        } else {
            var ox = canvas.transform.offsetX + dx
            var oy = canvas.transform.offsetY + dy
            if (snapEnabled) {
                if (kotlin.math.abs(ox) < 0.035f) ox = 0f
                if (kotlin.math.abs(oy) < 0.035f) oy = 0f
            }
            updateTransform(canvas.transform.copy(
                fitMode = com.livevip.app.overlay.FitMode.CUSTOM,
                offsetX = ox.coerceIn(-1.2f, 1.2f), offsetY = oy.coerceIn(-1.2f, 1.2f)
            ))
        }
    }

    private fun scaleBy(factor: Float) {
        val idx = indexOfSelected()
        if (idx >= 0) {
            layers[idx] = layers[idx].copy(
                scale = (layers[idx].scale * factor).coerceIn(0.03f, 1f)
            )
            applyLayersLive()
            renderSelection()
        } else {
            updateTransform(canvas.transform.copy(
                fitMode = com.livevip.app.overlay.FitMode.CUSTOM,
                scale = (canvas.transform.scale * factor).coerceIn(0.1f, 8f)
            ))
        }
    }

    private fun rotateTo(deg: Float) {
        val idx = indexOfSelected()
        if (idx >= 0) {
            layers[idx] = layers[idx].copy(rotation = deg.toInt().mod(360))
            applyLayersLive()
            renderSelection()
        } else {
            updateTransform(canvas.transform.copy(rotationDeg = deg))
        }
    }

    /** Push layer geometry into the compositor immediately (in-place, no rebuild). */
    private fun applyLayersLive() {
        LiveStreamingManager.applyOverlays(layers.filter { it.enabled })
    }

    private fun commitLayerChanges() {
        renderLayers()
    }

    /** Approximate layer rect in canvas fractions (for hit-test + selection box). */
    private fun layerRect(index: Int): RectF? {
        val layer = layers.getOrNull(index) ?: return null
        return when (layer.type) {
            OverlayType.BACKGROUND -> RectF(0f, 0f, 1f, 1f)
            OverlayType.BORDER -> RectF(0f, 0f, 1f, 1f)
            else -> {
                val width = layer.scale.coerceIn(0.01f, 1f)
                val height = width * 0.5f // approximation; exact aspect is applied in GL
                RectF(
                    layer.positionX - width / 2f, layer.positionY - height / 2f,
                    layer.positionX + width / 2f, layer.positionY + height / 2f
                )
            }
        }
    }

    private fun videoRect(): RectF {
        // Main-video quad rect in canvas fractions (unrotated approximation).
        val t = canvas.transform
        val srcW = firstPlaylistVideo()?.displayWidth ?: canvas.width
        val srcH = firstPlaylistVideo()?.displayHeight ?: canvas.height
        val quad = t.quadFor(srcW, srcH, canvas.width, canvas.height)
        val xs = listOf(quad[0], quad[5], quad[10], quad[15])
        val ys = listOf(quad[1], quad[6], quad[11], quad[16])
        val left = (xs.min() + 1f) / 2f
        val right = (xs.max() + 1f) / 2f
        val top = (1f - ys.max()) / 2f
        val bottom = (1f - ys.min()) / 2f
        return RectF(left, top, right, bottom)
    }

    private fun renderSelection() {
        val idx = indexOfSelected()
        binding.canvasAids.selectionRect = if (idx >= 0) layerRect(idx) else videoRect()
        binding.canvasAids.invalidate()
        val t = canvas.transform
        val hint = if (idx >= 0) {
            "${layers[idx].type.label} • zoom ${(layers[idx].scale * 100).toInt()}% • " +
                "${layers[idx].rotation}°"
        } else {
            getString(R.string.main_video_layer) + " • zoom ${(t.scale * 100).toInt()}% • ${t.rotationDeg.toInt()}°"
        }
        binding.canvasFormatNote.text = hint
    }

    // ------------------------------------------------------------------
    // Tabs + panels
    // ------------------------------------------------------------------

    private fun setupTabs() {
        binding.canvasTabToggle.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            binding.layersPanel.visibility = if (id == R.id.tabLayers) View.VISIBLE else View.GONE
            binding.scenesPanel.visibility = if (id == R.id.tabScenes) View.VISIBLE else View.GONE
            binding.templatesPanel.visibility = if (id == R.id.tabTemplates) View.VISIBLE else View.GONE
            if (id == R.id.tabTemplates) renderTemplates()
            if (id == R.id.tabScenes) renderScenes()
        }
        binding.btnAddScene.setOnClickListener { promptSaveScene() }
        binding.btnSaveTemplate.setOnClickListener { promptSaveTemplate() }
        binding.layersList.layoutManager = LinearLayoutManager(this)
        binding.scenesList.layoutManager = LinearLayoutManager(this)
        binding.templatesList.layoutManager = LinearLayoutManager(this)
    }

    private fun renderAll() {
        renderFormat()
        renderResolutionOptions()
        renderLayers()
        renderScenes()
        renderSelection()
    }

    // ------------------------------------------------------------------
    // Layers panel
    // ------------------------------------------------------------------

    private fun setupLayerButtons() {
        binding.btnAddImage.setOnClickListener { launchPicker(imagePicker, "image/*") }
        binding.btnAddVideo.setOnClickListener { launchPicker(videoPicker, "video/*") }
        binding.btnAddGif.setOnClickListener { launchPicker(gifPicker, "image/gif") }
        binding.btnAddText.setOnClickListener { promptTextLayer() }
        binding.btnAddSubscribe.setOnClickListener {
            addLayer(OverlayConfig(
                id = newId(), type = OverlayType.SUBSCRIBE,
                text = "SUBSCRIBE", positionX = 0.5f, positionY = 0.86f,
                scale = 0.4f, animation = OverlayAnimation.PULSE
            ))
        }
        binding.btnAddClock.setOnClickListener {
            addLayer(OverlayConfig(
                id = newId(), type = OverlayType.CLOCK,
                text = "00:00:00", positionX = 0.87f, positionY = 0.07f, scale = 0.18f
            ))
        }
        binding.btnAddCountdown.setOnClickListener {
            val minutes = 10L
            addLayer(OverlayConfig(
                id = newId(), type = OverlayType.COUNTDOWN,
                text = "10:00", positionX = 0.5f, positionY = 0.12f, scale = 0.22f,
                countdownTargetEpochMs = System.currentTimeMillis() + minutes * 60_000
            ))
        }
        binding.btnAddScroll.setOnClickListener {
            promptTextLayer(OverlayType.SCROLLING_TEXT)
        }
        binding.btnAddLowerThird.setOnClickListener { promptLowerThird() }
        binding.btnAddBorder.setOnClickListener {
            addLayer(OverlayConfig(
                id = newId(), type = OverlayType.BORDER, text = "Border",
                color = 0xFF7C4DFF.toInt(), borderThickness = 0.02f
            ))
        }
        binding.btnAddBackground.setOnClickListener { promptBackground() }
    }

    private fun launchPicker(
        picker: androidx.activity.result.ActivityResultLauncher<Array<String>>,
        mime: String
    ) {
        try {
            picker.launch(arrayOf(mime))
        } catch (_: Throwable) {
            Snackbar.make(binding.root, R.string.picker_unavailable, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun takePermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Throwable) {
        }
    }

    private fun addImageLayer(uri: Uri) {
        takePermission(uri)
        addLayer(OverlayConfig(
            id = newId(), type = OverlayType.IMAGE, imageUri = uri.toString(),
            text = "Image", positionX = 0.82f, positionY = 0.12f, scale = 0.18f
        ))
    }

    private fun addVideoLayer(uri: Uri) {
        takePermission(uri)
        Snackbar.make(binding.root, R.string.pip_video_note, Snackbar.LENGTH_LONG).show()
        addLayer(OverlayConfig(
            id = newId(), type = OverlayType.VIDEO, imageUri = uri.toString(),
            text = "Video", positionX = 0.8f, positionY = 0.8f, scale = 0.25f
        ))
    }

    private fun addGifLayer(uri: Uri) {
        takePermission(uri)
        try {
            val size = contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L
            if (size > 4_000_000) {
                Snackbar.make(binding.root, R.string.gif_too_large, Snackbar.LENGTH_LONG).show()
            }
        } catch (_: Throwable) {
        }
        addLayer(OverlayConfig(
            id = newId(), type = OverlayType.GIF, imageUri = uri.toString(),
            text = "GIF", positionX = 0.15f, positionY = 0.85f, scale = 0.2f
        ))
    }

    private fun promptTextLayer(type: OverlayType = OverlayType.TEXT) {
        val input = TextInputEditText(this).apply {
            hint = getString(R.string.new_text_hint)
            setPadding(48, 48, 48, 24)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(if (type == OverlayType.SCROLLING_TEXT) R.string.add_scrolling else R.string.add_text)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.add_text) { _, _ ->
                val text = input.text?.toString()?.trim().orEmpty()
                if (text.isEmpty()) return@setPositiveButton
                addLayer(OverlayConfig(
                    id = newId(), type = type, text = text,
                    positionX = 0.5f,
                    positionY = if (type == OverlayType.SCROLLING_TEXT) 0.9f else 0.85f,
                    scale = 0.5f
                ))
            }
            .show()
    }

    private fun promptLowerThird() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        fun field(hint: String): TextInputEditText {
            val layout = TextInputLayout(this, null,
                com.google.android.material.R.attr.textInputStyle).apply { this.hint = hint }
            val edit = TextInputEditText(layout.context)
            layout.addView(edit)
            container.addView(layout)
            return edit
        }
        val title = field("Title (e.g. LIVE • Dhaka)")
        val subtitle = field("Subtitle (e.g. Guest name)")
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_lower_third)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.add_text) { _, _ ->
                addLayer(OverlayConfig(
                    id = newId(), type = OverlayType.LOWER_THIRD,
                    text = title.text?.toString()?.trim().orEmpty().ifEmpty { "LIVE" },
                    secondaryText = subtitle.text?.toString()?.trim().orEmpty(),
                    positionX = 0.5f, positionY = 0.9f, scale = 0.6f
                ))
            }
            .show()
    }

    private fun promptBackground() {
        val colors = arrayOf(
            "Black #000000", "Dark #09090D", "Purple #1A1030",
            "Gradient-like blue #0A1A2F", "White #FFFFFF"
        )
        val values = intArrayOf(
            0xFF000000.toInt(), 0xFF09090D.toInt(), 0xFF1A1030.toInt(),
            0xFF0A1A2F.toInt(), 0xFFFFFFFF.toInt()
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_background)
            .setItems(colors) { _, which ->
                canvas = canvas.copy(backgroundColor = values[which])
                addLayer(OverlayConfig(
                    id = newId(), type = OverlayType.BACKGROUND, text = "Background",
                    background = values[which]
                ))
                startPreview()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun addLayer(config: OverlayConfig) {
        layers += config
        selectedLayerId = config.id
        applyLayersLive()
        renderLayers()
        renderSelection()
    }

    private fun newId(): Long = System.currentTimeMillis() + layers.size

    private fun renderLayers() {
        // Display top-most first (reverse z).
        val display = layers.withIndex().reversed()
        binding.layersList.adapter = object :
            androidx.recyclerview.widget.RecyclerView.Adapter<LayerHolder>() {

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = LayerHolder(
                ItemLayerRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            )

            override fun getItemCount() = display.size

            override fun onBindViewHolder(holder: LayerHolder, position: Int) {
                val (index, layer) = display.toList()[position]
                val b = holder.b
                b.layerName.text = layer.text.ifEmpty { layer.type.label }
                b.layerMeta.text = "${layer.type.label} • " +
                    "X ${(layer.positionX * 100).toInt()}% Y ${(layer.positionY * 100).toInt()}%" +
                    " • ${(layer.scale * 100).toInt()}%" +
                    if (layer.rotation != 0) " • ${layer.rotation}°" else ""
                b.layerIcon.setImageResource(iconFor(layer.type))
                b.btnLayerVisible.setImageResource(
                    if (layer.enabled) R.drawable.ic_check else R.drawable.ic_close
                )
                b.btnLayerVisible.setColorFilter(
                    if (layer.enabled) getColor(R.color.success_green)
                    else getColor(R.color.text_secondary)
                )
                b.btnLayerLock.setColorFilter(
                    if (layer.locked) getColor(R.color.warning_amber)
                    else getColor(R.color.text_secondary)
                )
                b.root.setOnClickListener {
                    selectedLayerId = layer.id
                    renderSelection()
                    showLayerProperties(layer)
                }
                b.btnLayerUp.setOnClickListener {
                    if (index < layers.size - 1) {
                        val item = layers.removeAt(index)
                        layers.add(index + 1, item)
                        applyLayersLive()
                        renderLayers()
                    }
                }
                b.btnLayerVisible.setOnClickListener {
                    layers[index] = layers[index].copy(enabled = !layers[index].enabled)
                    applyLayersLive()
                    renderLayers()
                }
                b.btnLayerLock.setOnClickListener {
                    layers[index] = layers[index].copy(locked = !layers[index].locked)
                    renderLayers()
                }
                b.btnLayerDuplicate.setOnClickListener {
                    layers.add(index + 1, layers[index].copy(id = newId(), locked = false))
                    applyLayersLive()
                    renderLayers()
                }
                b.btnLayerDelete.setOnClickListener {
                    layers.removeAt(index)
                    if (selectedLayerId == layer.id) selectedLayerId = null
                    applyLayersLive()
                    renderLayers()
                    renderSelection()
                }
            }
        }
    }

    private fun iconFor(type: OverlayType): Int = when (type) {
        OverlayType.TEXT, OverlayType.LOWER_THIRD, OverlayType.SCROLLING_TEXT -> R.drawable.ic_text_overlay
        OverlayType.IMAGE, OverlayType.WATERMARK, OverlayType.BACKGROUND -> R.drawable.ic_image
        OverlayType.VIDEO -> R.drawable.ic_folder_video
        OverlayType.GIF -> R.drawable.ic_analytics
        OverlayType.SUBSCRIBE -> R.drawable.ic_broadcast
        OverlayType.CLOCK, OverlayType.COUNTDOWN -> R.drawable.ic_clock
        OverlayType.BORDER -> R.drawable.ic_layers
    }

    private class LayerHolder(val b: ItemLayerRowBinding) :
        androidx.recyclerview.widget.RecyclerView.ViewHolder(b.root)

    // ------------------------------------------------------------------
    // Layer properties
    // ------------------------------------------------------------------

    @SuppressLint("InflateParams")
    private fun showLayerProperties(layer: OverlayConfig) {
        val idx = layers.indexOfFirst { it.id == layer.id }
        if (idx < 0) return
        val view = layoutInflater.inflate(R.layout.dialog_layer_props, null)

        fun slider(id: Int): Slider = view.findViewById(id)
        val scaleS = slider(R.id.sliderLayerScale)
        val opacityS = slider(R.id.sliderLayerOpacity)
        val rotationS = slider(R.id.sliderLayerRotation)
        val textInput: TextInputEditText = view.findViewById(R.id.inputLayerText)
        val animationInput: AutoCompleteTextView = view.findViewById(R.id.layerAnimation)

        scaleS.value = (layer.scale * 100f).coerceIn(3f, 100f)
        opacityS.value = (layer.opacity * 100f).coerceIn(0f, 100f)
        val rotationDisplay = layer.rotation.let { if (it > 180) it - 360 else it }
        rotationS.value = rotationDisplay.toFloat().coerceIn(-180f, 180f)
        textInput.setText(layer.text)
        if (layer.type == OverlayType.TEXT || layer.type == OverlayType.SCROLLING_TEXT ||
            layer.type == OverlayType.SUBSCRIBE
        ) {
            textInput.visibility = View.VISIBLE
        } else {
            view.findViewById<TextInputLayout>(R.id.inputLayerTextLayout).visibility = View.GONE
        }

        val animations = OverlayAnimation.entries.map { it.label }
        animationInput.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, animations)
        )
        animationInput.setText(layer.animation.label, false)
        var chosenAnimation = layer.animation
        animationInput.setOnItemClickListener { _, _, position, _ ->
            chosenAnimation = OverlayAnimation.entries[position]
        }

        fun updated(): OverlayConfig = layers[idx].copy(
            scale = scaleS.value / 100f,
            opacity = opacityS.value / 100f,
            rotation = rotationS.value.toInt(),
            text = textInput.text?.toString().orEmpty(),
            animation = chosenAnimation
        )

        fun push() {
            layers[idx] = updated()
            applyLayersLive()
        }

        scaleS.addOnChangeListener { _, _, _ -> push() }
        opacityS.addOnChangeListener { _, _, _ -> push() }
        rotationS.addOnChangeListener { _, _, _ -> push() }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.layer_properties)
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                push()
                renderLayers()
                renderSelection()
            }
            .setNeutralButton(R.string.delete) { _, _ ->
                layers.removeAt(idx)
                selectedLayerId = null
                applyLayersLive()
                renderLayers()
                renderSelection()
            }
            .show()
    }

    // ------------------------------------------------------------------
    // Scenes
    // ------------------------------------------------------------------

    private fun renderScenes() {
        binding.scenesList.adapter = object :
            androidx.recyclerview.widget.RecyclerView.Adapter<SceneHolder>() {

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = SceneHolder(
                ItemSceneRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            )

            override fun getItemCount() = scenes.size

            override fun onBindViewHolder(holder: SceneHolder, position: Int) {
                val scene = scenes[position]
                holder.b.sceneName.text = scene.name
                holder.b.btnActivateScene.setImageResource(
                    if (scene.id == activeSceneId) R.drawable.ic_check else R.drawable.ic_playlist
                )
                holder.b.root.setOnClickListener { activateScene(scene) }
                holder.b.btnActivateScene.setOnClickListener { activateScene(scene) }
                holder.b.btnDeleteScene.setOnClickListener {
                    scenes.removeAt(position)
                    renderScenes()
                }
            }
        }
    }

    private fun promptSaveScene() {
        val input = TextInputEditText(this).apply {
            hint = getString(R.string.scene_name_hint)
            setPadding(48, 48, 48, 24)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.save_scene)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text?.toString()?.trim()
                    ?.ifEmpty { "Scene ${scenes.size + 1}" } ?: return@setPositiveButton
                val scene = SceneConfig(
                    id = System.currentTimeMillis(),
                    name = name,
                    overlayIds = layers.filter { it.enabled }.map { it.id }
                )
                scenes += scene
                activeSceneId = scene.id
                Snackbar.make(binding.root, R.string.scene_saved, Snackbar.LENGTH_SHORT).show()
                renderScenes()
            }
            .show()
    }

    private fun promptSaveTemplate() {
        if (layers.isEmpty()) {
            Snackbar.make(binding.root, R.string.no_layers_hint, Snackbar.LENGTH_LONG).show()
            return
        }
        val input = TextInputEditText(this).apply {
            hint = getString(R.string.template_name_hint)
            setPadding(48, 48, 48, 24)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.save_as_template)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text?.toString()?.trim()
                    ?.ifEmpty { "Overlay ${System.currentTimeMillis() % 1000}" }
                    ?: return@setPositiveButton
                repo.async({ r -> r.saveTemplate(name, layers.toList()); true }) {
                    Snackbar.make(binding.root, R.string.template_saved, Snackbar.LENGTH_SHORT).show()
                    renderTemplates()
                }
            }
            .show()
    }

    /** LIVE scene switch: overlay set diff only — RTMP never disconnects. */
    private fun activateScene(scene: SceneConfig) {
        activeSceneId = scene.id
        LiveStreamingManager.applyScene(scene, layers)
        renderScenes()
        renderSelection()
    }

    private class SceneHolder(val b: ItemSceneRowBinding) :
        androidx.recyclerview.widget.RecyclerView.ViewHolder(b.root)

    // ------------------------------------------------------------------
    // Templates
    // ------------------------------------------------------------------

    private fun renderTemplates() {
        repo.async({ it.templates() }) { list ->
            if (isFinishing || isDestroyed) return@async
            binding.templatesList.adapter = object :
                androidx.recyclerview.widget.RecyclerView.Adapter<TemplateHolder>() {

                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = TemplateHolder(
                    ItemTemplateRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                )

                override fun getItemCount() = list.size

                override fun onBindViewHolder(holder: TemplateHolder, position: Int) {
                    val template = list[position]
                    holder.b.templateName.text = template.name
                    holder.b.templateMeta.text = "${template.layers.size} layers"
                    holder.b.btnApplyTemplate.setOnClickListener { applyTemplate(template) }
                    holder.b.btnDeleteTemplate.setOnClickListener {
                        repo.async({ r -> r.deleteTemplate(template.id); true }) { renderTemplates() }
                    }
                }
            }
        }
    }

    private fun applyTemplate(template: OverlayTemplate) {
        template.layers.forEach { t ->
            layers += t.copy(id = newId())
        }
        applyLayersLive()
        renderLayers()
        renderSelection()
    }

    private class TemplateHolder(val b: ItemTemplateRowBinding) :
        androidx.recyclerview.widget.RecyclerView.ViewHolder(b.root)

}
