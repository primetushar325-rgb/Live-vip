package com.livevip.wallpaper.project

/** Format constants for the `.mwproj` project archive (see docs/PROJECT_FORMAT.md). */
object ProjectLimits {
    const val FORMAT_ID = "mwproj"
    const val FORMAT_VERSION = 1
    val SUPPORTED_VERSIONS = setOf(1)

    const val MAX_ARCHIVE_BYTES = 150L * 1024 * 1024
    const val MAX_UNCOMPRESSED_BYTES = 300L * 1024 * 1024
    const val MAX_ENTRY_BYTES = 40L * 1024 * 1024
    const val MAX_ENTRY_COUNT = 200
    const val MAX_PATH_DEPTH = 4
    const val MAX_MANIFEST_BYTES = 256 * 1024
    const val MIN_CANVAS_SIDE = 128
    const val MAX_IMAGE_SIDE = 4096
    const val MAX_TEXTURE_BYTES_TOTAL = 256L * 1024 * 1024
    const val MAX_LAYERS = 24
    const val MAX_PARTICLE_GROUPS = 8
    const val MAX_PARTICLES_PER_GROUP = 300

    val PARTICLE_TYPES = setOf("fire", "sparks", "magic", "ambient")
    val RIG_MODES = setOf("sway", "ripple", "flutter")
    val LAYER_ROLES = setOf("body", "hair", "cloth", "cape", "sword", "accessory", "effect", "other")
    val ALLOWED_EXTENSIONS = setOf("png", "json")
}

class ProjectException(message: String) : Exception(message)

/** Motion & depth settings (Motion and Depth screen). */
data class MotionSettings(
    val strength: Float = 1f,        // 0..2 multiplier on tilt
    val perspective: Float = 0.5f,   // 0..1 depth scaling of near layers
    val depthScale: Float = 1f,      // 0..2 multiplier on each layer's depth value
    val smoothing: Float = 6f,       // 1..20 damping rate (higher = snappier)
    val motionLimit: Float = 0.6f,   // 0.05..1 clamp of total normalized offset
    val idleAmount: Float = 0.2f,    // 0..1 subtle idle drift when the device is still
    val invertX: Boolean = false,
    val invertY: Boolean = false,
)

/** Visual effects settings (Effects Editor). Colors are ARGB ints. */
data class EffectSettings(
    val outerGlowEnabled: Boolean = true,
    val outerGlowColor: Int = 0xFF00E5FF.toInt(),
    val outerGlowIntensity: Float = 0.7f,   // 0..2
    val outerGlowRadius: Float = 0.02f,     // 0.002..0.06 of canvas height
    val innerGlowEnabled: Boolean = false,
    val innerGlowColor: Int = 0xFFFF2BD6.toInt(),
    val innerGlowIntensity: Float = 0.5f,   // 0..2
    val glowPulseSpeed: Float = 1f,         // 0..3 Hz
    val fireAmount: Float = 0.5f,           // 0..1 multiplier on fire emitters
    val sparksAmount: Float = 0.5f,
    val magicAmount: Float = 0.5f,
    val ambientAmount: Float = 0.5f,
    val bgBlur: Float = 0f,                 // 0..1
    val bgBrightness: Float = 0f,           // -0.5..0.5
    val bgContrast: Float = 1f,             // 0.5..1.5
    val bgSaturation: Float = 1f,           // 0..2
    val bgTintColor: Int = 0xFFFFFFFF.toInt(),
    val bgTintAmount: Float = 0f,           // 0..1
    val rigStrength: Float = 1f,            // 0..2 multiplier on hair/cloth/sword rig motion
)

/** Everything the user can tune for one project. */
data class ProjectTuning(
    val motion: MotionSettings = MotionSettings(),
    val effects: EffectSettings = EffectSettings(),
)

data class RigSpec(
    val mode: String = "sway",
    val amplitude: Float = 0.01f,    // uv units
    val frequency: Float = 0.4f,     // Hz
    val phase: Float = 0f,
    val pivotX: Float = 0.5f,
    val pivotY: Float = 0f,
    val dirX: Float = 1f,
    val dirY: Float = 0f,
)

data class LayerSpec(
    val id: String,
    val role: String,
    val file: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val depth: Float,
    val mask: String?,
    val glowMask: String?,
    val rig: RigSpec?,
)

data class ParticleSpec(
    val type: String,
    val count: Int,
    val regionX: Float,
    val regionY: Float,
    val regionW: Float,
    val regionH: Float,
    val color: Int?,
)

data class ProjectManifest(
    val name: String,
    val canvasWidth: Int,
    val canvasHeight: Int,
    val preview: String?,
    val background: String,
    val depth: String?,
    val layers: List<LayerSpec>,
    val particles: List<ParticleSpec>,
    val mesh: String?,
    val defaults: ProjectTuning?,
)

/** Metadata the app keeps next to an extracted project (meta.json). */
data class ProjectMeta(
    val id: String = "",
    val name: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)
