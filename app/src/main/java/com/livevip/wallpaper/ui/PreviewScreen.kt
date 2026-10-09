package com.livevip.wallpaper.ui

import android.content.ActivityNotFoundException
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.livevip.wallpaper.project.QualityMode
import com.livevip.wallpaper.render.GlSupport
import com.livevip.wallpaper.render.RenderSettings
import com.livevip.wallpaper.render.SceneRenderer
import com.livevip.wallpaper.render.SceneRequest
import com.livevip.wallpaper.sensor.TiltSensor
import com.livevip.wallpaper.wallpaper.WallpaperStatus
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Bridges GLSurfaceView's callbacks to the shared [SceneRenderer]. */
private class PreviewGlRenderer(private val scene: SceneRenderer) : GLSurfaceView.Renderer {
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) = scene.init()
    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) = scene.resize(width, height)
    override fun onDrawFrame(gl: GL10?) = scene.drawFrame(System.nanoTime())
}

/** Live preview: real-time GL rendering with gyroscope/rotation tilt, or touch-drag when no sensor exists. */
@Composable
fun PreviewScreen(
    vm: AppViewModel,
    projectId: String,
    onBack: () -> Unit,
    onEffects: () -> Unit,
    onMotion: () -> Unit,
    onApply: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val current by vm.current.collectAsState()
    val tuning by vm.tuning.collectAsState()
    val quality by vm.quality.collectAsState()
    val dirty by vm.dirty.collectAsState()
    val message by vm.message.collectAsState()

    val renderer = remember { SceneRenderer() }
    val tilt = remember { TiltSensor(context) { x, y -> renderer.setTarget(x, y) } }
    var glView by remember { mutableStateOf<GLSurfaceView?>(null) }
    var tiltActive by remember { mutableStateOf(false) }
    var sensorLabel by remember { mutableStateOf("Motion sensors: checking…") }

    LaunchedEffect(projectId) {
        if (vm.current.value?.summary?.id != projectId) vm.open(projectId)
    }
    LaunchedEffect(current?.summary?.id) {
        val c = current ?: return@LaunchedEffect
        renderer.requestScene(SceneRequest(vm.store.projectDir(c.summary.id), c.manifest))
    }
    LaunchedEffect(tuning, quality) {
        renderer.updateSettings(RenderSettings(tuning, quality))
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    glView?.onResume()
                    tiltActive = tilt.start()
                    sensorLabel = if (tiltActive) "Live tilt: ${tilt.sourceName}"
                    else if (!tilt.isAvailable) "No motion sensor: drag the preview to tilt the scene"
                    else "Motion sensor unavailable: drag the preview"
                }
                Lifecycle.Event.ON_PAUSE -> {
                    tilt.stop()
                    tiltActive = false
                    glView?.onPause()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            tilt.stop()
            glView?.queueEvent { renderer.release() }
            glView?.onPause()
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                GLSurfaceView(ctx).apply {
                    setEGLContextClientVersion(GlSupport.preferredClientVersion(ctx))
                    setEGLConfigChooser(8, 8, 8, 8, 0, 0)
                    setRenderer(PreviewGlRenderer(renderer))
                    renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                    setOnTouchListener { view, event ->
                        if (!tiltActive) {
                            val w = view.width.coerceAtLeast(1).toFloat()
                            val h = view.height.coerceAtLeast(1).toFloat()
                            when (event.actionMasked) {
                                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                                    renderer.setTarget(event.x / w * 2f - 1f, event.y / h * 2f - 1f)
                                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> renderer.setTarget(0f, 0f)
                            }
                        }
                        true
                    }
                    glView = this
                }
            },
        )

        Column(
            Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))) {
                Column(Modifier.padding(12.dp)) {
                    Text(current?.summary?.name ?: "Loading project…", style = MaterialTheme.typography.titleMedium)
                    Text(sensorLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Renderer: ${GlSupport.describe(context)} · ${quality.label}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (dirty) Text("Unsaved changes", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                    message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onEffects) { Text("Effects") }
                OutlinedButton(onClick = onMotion) { Text("Motion & depth") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.save() }, enabled = dirty) { Text("Save") }
                Button(onClick = onApply) { Text("Set as live wallpaper") }
            }
            TextButton(onClick = onBack) { Text("Back to library") }
        }
    }
}

/**
 * Apply Wallpaper: launches Android's live-wallpaper preview. The status below is read back from the
 * system, so "Active" is only shown once Android has actually applied this wallpaper.
 */
@Composable
fun ApplyScreen(vm: AppViewModel, projectId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val current by vm.current.collectAsState()
    val dirty by vm.dirty.collectAsState()
    val quality by vm.quality.collectAsState()
    val activeId = vm.prefs.activeProjectId
    var active by remember { mutableStateOf(WallpaperStatus.isActive(context)) }
    var launchError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(projectId) {
        if (vm.current.value?.summary?.id != projectId) vm.open(projectId)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) active = WallpaperStatus.isActive(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Apply wallpaper", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        Panel {
            SectionTitle(current?.summary?.name ?: "No project selected")
            Text(
                if (active) "Status: Live VIP is the active live wallpaper."
                else "Status: not active yet. Confirm in Android's preview to apply it.",
                style = MaterialTheme.typography.bodyLarge,
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            if (activeId != projectId && current != null) {
                Hint("Tapping the button makes this project the one the wallpaper shows.")
            }
            if (dirty) Hint("You have unsaved effect changes. They are saved before the wallpaper is applied.")
            Hint("Renderer quality: ${quality.label}. ${quality.description}")
        }
        Panel {
            SectionTitle("Steps")
            Hint("1. Tap \"Open Android preview\".  2. Check the preview.  3. Tap Set wallpaper in Android's own screen.  4. Return here; the status updates from the system.")
            Hint("Home screen and lock screen choices appear in Android's own wallpaper screen when your device offers them. The app cannot choose them for you.")
            launchError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = {
                if (dirty) vm.save()
                vm.setActive(projectId)
                launchError = null
                try {
                    context.startActivity(WallpaperStatus.previewIntent(context))
                } catch (e: ActivityNotFoundException) {
                    launchError = "This device does not offer a live wallpaper preview."
                }
            }) { Text("Open Android preview") }
            OutlinedButton(onClick = {
                try {
                    context.startActivity(WallpaperStatus.chooserIntent())
                } catch (e: ActivityNotFoundException) {
                    launchError = "This device does not offer a live wallpaper chooser."
                }
            }) { Text("Open wallpaper chooser") }
            OutlinedButton(onClick = { active = WallpaperStatus.isActive(context) }) { Text("Check status again") }
        }
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onBack) { Text("Back to preview") }
    }
}
