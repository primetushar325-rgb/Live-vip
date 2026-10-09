package com.livevip.wallpaper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.livevip.wallpaper.project.QualityMode
import com.livevip.wallpaper.render.GlSupport
import androidx.compose.ui.platform.LocalContext

private val GlowColors = listOf(
    "Cyan" to 0xFF00E5FF.toInt(),
    "Magenta" to 0xFFFF2BD6.toInt(),
    "Gold" to 0xFFFFC857.toInt(),
    "Lime" to 0xFF7CFF6B.toInt(),
    "Violet" to 0xFF9B5DE5.toInt(),
    "Red" to 0xFFFF4D4D.toInt(),
)

private val TintColors = listOf(
    "None" to 0xFFFFFFFF.toInt(),
    "Warm" to 0xFFFFD9A8.toInt(),
    "Cool" to 0xFFA8D8FF.toInt(),
    "Dusk" to 0xFFD8A8FF.toInt(),
)

/** Effects Editor: glow, particles, background grading and rig strength. Edits are live in preview. */
@Composable
fun EffectsScreen(vm: AppViewModel, projectId: String, onBack: () -> Unit, onPreview: () -> Unit) {
    LaunchedEffect(projectId) {
        if (vm.current.value?.summary?.id != projectId) vm.open(projectId)
    }
    val tuning by vm.tuning.collectAsState()
    val dirty by vm.dirty.collectAsState()
    val current by vm.current.collectAsState()
    val e = tuning.effects

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Effects", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        Hint(current?.summary?.name ?: "")
        if (dirty) Text("Unsaved changes", color = MaterialTheme.colorScheme.secondary)

        Panel {
            SectionTitle("Outer glow")
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Enabled", Modifier.weight(1f))
                Switch(checked = e.outerGlowEnabled, onCheckedChange = { on -> vm.updateEffects { it.copy(outerGlowEnabled = on) } })
            }
            Hint("Halo drawn around character layers, from their alpha or glowMask.")
            ColorSwatches(e.outerGlowColor, GlowColors) { c -> vm.updateEffects { it.copy(outerGlowColor = c) } }
            SliderRow("Intensity", e.outerGlowIntensity, 0f..2f, { v -> vm.updateEffects { it.copy(outerGlowIntensity = v) } })
            SliderRow("Radius", e.outerGlowRadius, 0.002f..0.06f, { v -> vm.updateEffects { it.copy(outerGlowRadius = v) } },
                format = { "${(it * 100).toInt()}% of height" })
        }

        Panel {
            SectionTitle("Inner glow")
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Enabled", Modifier.weight(1f))
                Switch(checked = e.innerGlowEnabled, onCheckedChange = { on -> vm.updateEffects { it.copy(innerGlowEnabled = on) } })
            }
            ColorSwatches(e.innerGlowColor, GlowColors) { c -> vm.updateEffects { it.copy(innerGlowColor = c) } }
            SliderRow("Intensity", e.innerGlowIntensity, 0f..2f, { v -> vm.updateEffects { it.copy(innerGlowIntensity = v) } })
            SliderRow("Pulse speed", e.glowPulseSpeed, 0f..3f, { v -> vm.updateEffects { it.copy(glowPulseSpeed = v) } },
                format = { String.format("%.1f Hz", it) })
        }

        Panel {
            SectionTitle("Particles")
            Hint("Procedural fire, sparks, magic motes and dust. Counts come from each project's particle settings.")
            SliderRow("Fire", e.fireAmount, 0f..1f, { v -> vm.updateEffects { it.copy(fireAmount = v) } })
            SliderRow("Sparks", e.sparksAmount, 0f..1f, { v -> vm.updateEffects { it.copy(sparksAmount = v) } })
            SliderRow("Magic", e.magicAmount, 0f..1f, { v -> vm.updateEffects { it.copy(magicAmount = v) } })
            SliderRow("Ambient dust", e.ambientAmount, 0f..1f, { v -> vm.updateEffects { it.copy(ambientAmount = v) } })
        }

        Panel {
            SectionTitle("Background")
            SliderRow("Blur", e.bgBlur, 0f..1f, { v -> vm.updateEffects { it.copy(bgBlur = v) } })
            SliderRow("Brightness", e.bgBrightness, -0.5f..0.5f, { v -> vm.updateEffects { it.copy(bgBrightness = v) } })
            SliderRow("Contrast", e.bgContrast, 0.5f..1.5f, { v -> vm.updateEffects { it.copy(bgContrast = v) } })
            SliderRow("Saturation", e.bgSaturation, 0f..2f, { v -> vm.updateEffects { it.copy(bgSaturation = v) } })
            Hint("Color grading tint")
            ColorSwatches(e.bgTintColor, TintColors) { c -> vm.updateEffects { it.copy(bgTintColor = c) } }
            SliderRow("Tint amount", e.bgTintAmount, 0f..1f, { v -> vm.updateEffects { it.copy(bgTintAmount = v) } })
        }

        Panel {
            SectionTitle("Hair, cloth, cape & sword")
            Hint("Only layers that have a rig (and a mask for per-part motion) move. Without them the character stays still.")
            SliderRow("Rig motion strength", e.rigStrength, 0f..2f, { v -> vm.updateEffects { it.copy(rigStrength = v) } })
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.save() }, enabled = dirty) { Text("Save") }
            OutlinedButton(onClick = { vm.open(projectId) }, enabled = dirty) { Text("Revert") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.resetToProjectDefaults() }) { Text("Reset to project defaults") }
            OutlinedButton(onClick = onPreview) { Text("Preview") }
        }
        TextButton(onClick = onBack) { Text("Back") }
    }
}

/** Motion & Depth: how far and how smoothly layers follow tilt. */
@Composable
fun MotionScreen(vm: AppViewModel, projectId: String, onBack: () -> Unit, onPreview: () -> Unit) {
    LaunchedEffect(projectId) {
        if (vm.current.value?.summary?.id != projectId) vm.open(projectId)
    }
    val tuning by vm.tuning.collectAsState()
    val dirty by vm.dirty.collectAsState()
    val m = tuning.motion

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Motion & depth", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        if (dirty) Text("Unsaved changes", color = MaterialTheme.colorScheme.secondary)
        Panel {
            SectionTitle("Parallax")
            Hint("Tilt the phone (or drag the preview when there is no motion sensor). Layers with a higher depth move more.")
            SliderRow("Strength", m.strength, 0f..2f, { v -> vm.updateMotion { it.copy(strength = v) } })
            SliderRow("Perspective", m.perspective, 0f..1f, { v -> vm.updateMotion { it.copy(perspective = v) } })
            SliderRow("Depth scale", m.depthScale, 0f..2f, { v -> vm.updateMotion { it.copy(depthScale = v) } })
        }
        Panel {
            SectionTitle("Smoothing & limits")
            SliderRow("Damping (responsiveness)", m.smoothing, 1f..20f, { v -> vm.updateMotion { it.copy(smoothing = v) } },
                format = { String.format("%.0f", it) })
            SliderRow("Motion limit", m.motionLimit, 0.05f..1f, { v -> vm.updateMotion { it.copy(motionLimit = v) } })
            SliderRow("Idle drift", m.idleAmount, 0f..1f, { v -> vm.updateMotion { it.copy(idleAmount = v) } })
        }
        Panel {
            SectionTitle("Direction")
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Invert left/right", Modifier.weight(1f))
                Switch(checked = m.invertX, onCheckedChange = { on -> vm.updateMotion { it.copy(invertX = on) } })
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Invert up/down", Modifier.weight(1f))
                Switch(checked = m.invertY, onCheckedChange = { on -> vm.updateMotion { it.copy(invertY = on) } })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.save() }, enabled = dirty) { Text("Save") }
            OutlinedButton(onClick = onPreview) { Text("Preview") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.resetToProjectDefaults() }) { Text("Reset to project defaults") }
        }
        TextButton(onClick = onBack) { Text("Back") }
    }
}

/** Performance settings: quality presets that trade resolution, frame rate and effect density. */
@Composable
fun PerformanceScreen(vm: AppViewModel, onBack: () -> Unit) {
    val quality by vm.quality.collectAsState()
    val context = LocalContext.current

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Performance", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        Panel {
            SectionTitle("Quality")
            QualityMode.values().forEach { mode ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = quality == mode, onClick = { vm.setQuality(mode) })
                    Column(Modifier.padding(start = 6.dp)) {
                        Text(mode.label, style = MaterialTheme.typography.bodyLarge)
                        Hint(mode.description)
                        Hint("Render ${(mode.renderScale * 100).toInt()}% · up to ${mode.maxFps} FPS · particles ${(mode.particleScale * 100).toInt()}%")
                    }
                }
            }
        }
        Panel {
            SectionTitle("Device")
            Hint(GlSupport.describe(context) + ". The renderer requests OpenGL ES 3.0 and falls back to ES 2.0 automatically.")
            Hint("Rendering pauses whenever the wallpaper is hidden, and motion sensors are only listened to while it is visible.")
            Hint("The app has no background service and no network access.")
        }
        TextButton(onClick = onBack) { Text("Back") }
    }
}
