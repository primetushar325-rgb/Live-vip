package com.livevip.wallpaper.project

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Parses manifest.json. Throws [ProjectException] with a user-readable message on the first problem.
 * File existence and image dimensions are checked later by [ProjectValidator].
 */
object ManifestParser {
    private val gson = Gson()
    private val idRegex = Regex("^[A-Za-z0-9_-]{1,32}$")
    private val hexRegex = Regex("^#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$")

    fun parse(json: String): ProjectManifest {
        val root = try {
            JsonParser.parseString(json)
        } catch (e: Exception) {
            throw ProjectException("manifest.json is not valid JSON")
        }
        if (!root.isJsonObject) throw ProjectException("manifest.json must contain a JSON object")
        val o = root.asJsonObject

        val format = o.str("format") ?: throw ProjectException("manifest.format is missing")
        if (format != ProjectLimits.FORMAT_ID) {
            throw ProjectException("Unsupported format '$format' (expected '${ProjectLimits.FORMAT_ID}')")
        }
        val version = o.int("formatVersion") ?: throw ProjectException("manifest.formatVersion is missing")
        if (version !in ProjectLimits.SUPPORTED_VERSIONS) {
            throw ProjectException("Unsupported project version $version (this app supports ${ProjectLimits.SUPPORTED_VERSIONS.joinToString()})")
        }

        val name = o.str("name")?.trim()?.takeIf { it.isNotEmpty() && it.length <= 80 }
            ?: throw ProjectException("manifest.name must be 1-80 characters")

        val canvas = o.obj("canvas") ?: throw ProjectException("manifest.canvas is missing")
        val cw = canvas.int("width") ?: throw ProjectException("canvas.width must be an integer")
        val ch = canvas.int("height") ?: throw ProjectException("canvas.height must be an integer")
        if (cw !in ProjectLimits.MIN_CANVAS_SIDE..ProjectLimits.MAX_IMAGE_SIDE ||
            ch !in ProjectLimits.MIN_CANVAS_SIDE..ProjectLimits.MAX_IMAGE_SIDE
        ) {
            throw ProjectException("canvas size must be between ${ProjectLimits.MIN_CANVAS_SIDE} and ${ProjectLimits.MAX_IMAGE_SIDE} px per side")
        }

        val bg = o.obj("background") ?: throw ProjectException("manifest.background is missing")
        val background = pngPath(bg.str("file"), "background.file")
        val depth = bg.str("depth")?.let { pngPath(it, "background.depth") }

        val preview = o.str("preview")?.let { pngPath(it, "preview") }

        val layersJson = o.arr("layers") ?: JsonArray()
        if (layersJson.size() > ProjectLimits.MAX_LAYERS) {
            throw ProjectException("Too many layers (max ${ProjectLimits.MAX_LAYERS})")
        }
        val layers = layersJson.mapIndexed { i, el -> parseLayer(el, i) }
        val ids = layers.map { it.id }
        if (ids.size != ids.toSet().size) throw ProjectException("Layer ids must be unique")

        val particles = (o.arr("particles") ?: JsonArray()).let { arr ->
            if (arr.size() > ProjectLimits.MAX_PARTICLE_GROUPS) {
                throw ProjectException("Too many particle groups (max ${ProjectLimits.MAX_PARTICLE_GROUPS})")
            }
            arr.mapIndexed { i, el -> parseParticle(el, i) }
        }

        val mesh = o.str("mesh")?.let { path ->
            val p = SafePath.normalize(path) ?: throw ProjectException("Unsafe mesh path: $path")
            if (SafePath.extension(p) != "json") throw ProjectException("mesh must be a .json file")
            p
        }

        val defaults = parseDefaults(o)

        return ProjectManifest(
            name = name,
            canvasWidth = cw,
            canvasHeight = ch,
            preview = preview,
            background = background,
            depth = depth,
            layers = layers,
            particles = particles,
            mesh = mesh,
            defaults = defaults,
        )
    }

    private fun parseLayer(el: JsonElement, index: Int): LayerSpec {
        if (!el.isJsonObject) throw ProjectException("layers[$index] must be an object")
        val l = el.asJsonObject
        val id = l.str("id")?.takeIf { idRegex.matches(it) }
            ?: throw ProjectException("layers[$index].id must be 1-32 letters, digits, '_' or '-'")
        val role = l.str("role") ?: "other"
        if (role !in ProjectLimits.LAYER_ROLES) throw ProjectException("layer '$id' has unknown role '$role'")
        val file = pngPath(l.str("file"), "layer '$id' file")
        val x = l.int("x") ?: 0
        val y = l.int("y") ?: 0
        if (x !in -ProjectLimits.MAX_IMAGE_SIDE..ProjectLimits.MAX_IMAGE_SIDE ||
            y !in -ProjectLimits.MAX_IMAGE_SIDE..ProjectLimits.MAX_IMAGE_SIDE
        ) {
            throw ProjectException("layer '$id' position is out of range")
        }
        val depth = l.num("depth")?.toFloat() ?: 0.5f
        if (depth !in 0f..1f) throw ProjectException("layer '$id' depth must be between 0 and 1")
        val mask = l.str("mask")?.let { pngPath(it, "layer '$id' mask") }
        val glow = l.str("glowMask")?.let { pngPath(it, "layer '$id' glowMask") }
        val rig = l.get("rig")?.takeIf { it.isJsonObject }?.asJsonObject?.let { parseRig(it, id) }
        return LayerSpec(
            id = id, role = role, file = file, x = x, y = y,
            width = 0, height = 0, // filled from PNG headers during validation
            depth = depth, mask = mask, glowMask = glow, rig = rig,
        )
    }

    private fun parseRig(r: JsonObject, id: String): RigSpec {
        val mode = r.str("mode") ?: throw ProjectException("layer '$id' rig.mode is missing")
        if (mode !in ProjectLimits.RIG_MODES) throw ProjectException("layer '$id' rig.mode must be one of ${ProjectLimits.RIG_MODES.joinToString()}")
        val amp = (r.num("amplitude") ?: 0.01).toFloat()
        if (amp !in 0f..0.08f) throw ProjectException("layer '$id' rig.amplitude must be between 0 and 0.08")
        val freq = (r.num("frequency") ?: 0.4).toFloat()
        if (freq !in 0f..3f) throw ProjectException("layer '$id' rig.frequency must be between 0 and 3 Hz")
        val phase = (r.num("phase") ?: 0.0).toFloat()
        val pivot = r.floats("pivot", listOf(0.5f, 0f), id)
        val dir = r.floats("direction", listOf(1f, 0f), id)
        val len = kotlin.math.sqrt(dir[0] * dir[0] + dir[1] * dir[1])
        if (len < 1e-3f) throw ProjectException("layer '$id' rig.direction must not be zero")
        return RigSpec(
            mode = mode, amplitude = amp, frequency = freq, phase = phase,
            pivotX = pivot[0].coerceIn(0f, 1f), pivotY = pivot[1].coerceIn(0f, 1f),
            dirX = dir[0] / len, dirY = dir[1] / len,
        )
    }

    private fun parseParticle(el: JsonElement, index: Int): ParticleSpec {
        if (!el.isJsonObject) throw ProjectException("particles[$index] must be an object")
        val p = el.asJsonObject
        val type = p.str("type") ?: throw ProjectException("particles[$index].type is missing")
        if (type !in ProjectLimits.PARTICLE_TYPES) throw ProjectException("particles[$index].type must be one of ${ProjectLimits.PARTICLE_TYPES.joinToString()}")
        val count = p.int("count") ?: 40
        if (count !in 0..ProjectLimits.MAX_PARTICLES_PER_GROUP) {
            throw ProjectException("particles[$index].count must be 0-${ProjectLimits.MAX_PARTICLES_PER_GROUP}")
        }
        val region = p.floats("region", listOf(0f, 0f, 1f, 1f), "particles[$index]")
        if (region.any { it !in 0f..1f } || region[0] + region[2] > 1.001f || region[1] + region[3] > 1.001f) {
            throw ProjectException("particles[$index].region must be [x,y,w,h] in 0..1 and inside the screen")
        }
        val color = p.str("color")?.let { parseColor(it, "particles[$index].color") }
        return ParticleSpec(type, count, region[0], region[1], region[2], region[3], color)
    }

    private fun parseDefaults(o: JsonObject): ProjectTuning? {
        val effectsJson = o.obj("effects")
        val motionJson = o.obj("motion")
        if (effectsJson == null && motionJson == null) return null
        val effects = effectsJson?.let { normalizeColors(it) }
        val motion = motionJson
        return ProjectTuning(
            motion = if (motion != null) gson.fromJson(motion, MotionSettings::class.java) else MotionSettings(),
            effects = if (effects != null) gson.fromJson(effects, EffectSettings::class.java) else EffectSettings(),
        ).let { sanitize(it) }
    }

    /** Converts "#RRGGBB"/"#AARRGGBB" strings in *Color keys into ARGB ints so Gson can read them. */
    private fun normalizeColors(obj: JsonObject): JsonObject {
        val copy = JsonObject()
        for ((k, v) in obj.entrySet()) {
            if (k.endsWith("Color") && v.isJsonPrimitive && v.asJsonPrimitive.isString) {
                copy.addProperty(k, parseColor(v.asString, "effects.$k"))
            } else {
                copy.add(k, v)
            }
        }
        return copy
    }

    private fun parseColor(text: String, field: String): Int {
        if (!hexRegex.matches(text)) throw ProjectException("$field must be a hex color like #00E5FF")
        val digits = text.substring(1)
        val argb = if (digits.length == 6) "FF$digits" else digits
        return java.lang.Long.parseLong(argb, 16).toInt()
    }

    private fun pngPath(raw: String?, field: String): String {
        if (raw == null) throw ProjectException("$field is missing")
        val p = SafePath.normalize(raw) ?: throw ProjectException("Unsafe or invalid path in $field: $raw")
        if (SafePath.extension(p) != "png") throw ProjectException("$field must reference a .png file")
        return p
    }

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.num(key: String): Double? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble?.takeIf { it.isFinite() }

    private fun JsonObject.int(key: String): Int? =
        num(key)?.takeIf { it % 1.0 == 0.0 && it >= Int.MIN_VALUE && it <= Int.MAX_VALUE }?.toInt()

    private fun JsonObject.obj(key: String): JsonObject? =
        get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.arr(key: String): JsonArray? =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray

    private fun JsonObject.floats(key: String, default: List<Float>, owner: String): List<Float> {
        val arr = arr(key) ?: return default
        if (arr.size() != default.size) throw ProjectException("$owner.$key must have ${default.size} numbers")
        return arr.map { el ->
            if (!el.isJsonPrimitive || !el.asJsonPrimitive.isNumber) throw ProjectException("$owner.$key must contain numbers")
            el.asFloat
        }
    }

    /** Clamps tuning values to their documented ranges so bad files can't break rendering. */
    fun sanitize(t: ProjectTuning): ProjectTuning {
        val m = t.motion
        val e = t.effects
        return ProjectTuning(
            motion = m.copy(
                strength = m.strength.coerceIn(0f, 2f),
                perspective = m.perspective.coerceIn(0f, 1f),
                depthScale = m.depthScale.coerceIn(0f, 2f),
                smoothing = m.smoothing.coerceIn(1f, 20f),
                motionLimit = m.motionLimit.coerceIn(0.05f, 1f),
                idleAmount = m.idleAmount.coerceIn(0f, 1f),
            ),
            effects = e.copy(
                outerGlowIntensity = e.outerGlowIntensity.coerceIn(0f, 2f),
                outerGlowRadius = e.outerGlowRadius.coerceIn(0.002f, 0.06f),
                innerGlowIntensity = e.innerGlowIntensity.coerceIn(0f, 2f),
                glowPulseSpeed = e.glowPulseSpeed.coerceIn(0f, 3f),
                fireAmount = e.fireAmount.coerceIn(0f, 1f),
                sparksAmount = e.sparksAmount.coerceIn(0f, 1f),
                magicAmount = e.magicAmount.coerceIn(0f, 1f),
                ambientAmount = e.ambientAmount.coerceIn(0f, 1f),
                bgBlur = e.bgBlur.coerceIn(0f, 1f),
                bgBrightness = e.bgBrightness.coerceIn(-0.5f, 0.5f),
                bgContrast = e.bgContrast.coerceIn(0.5f, 1.5f),
                bgSaturation = e.bgSaturation.coerceIn(0f, 2f),
                bgTintAmount = e.bgTintAmount.coerceIn(0f, 1f),
                rigStrength = e.rigStrength.coerceIn(0f, 2f),
            ),
        )
    }
}
