package com.livevip.wallpaper.project

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlin.math.sqrt

/**
 * Checks manifest.json.
 *
 * [check] records every problem it finds, each with the exact field path, so the import report can
 * list them all. [parse] is the older all-or-nothing API: it throws the first error, or returns the
 * manifest. Both use the same rules.
 */
object ManifestParser {
    private const val FILE = "manifest.json"
    private val gson = Gson()
    private val idRegex = Regex("^[A-Za-z0-9_-]{1,32}$")
    private val hexRegex = Regex("^#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$")

    private val TOP_KEYS = setOf(
        "format", "formatVersion", "name", "canvas", "preview", "background",
        "layers", "particles", "effects", "motion", "mesh",
    )
    private val BACKGROUND_KEYS = setOf("file", "depth")
    private val LAYER_KEYS = setOf("id", "role", "file", "x", "y", "depth", "mask", "glowMask", "rig")
    private val RIG_KEYS = setOf("mode", "amplitude", "frequency", "phase", "pivot", "direction")
    private val PARTICLE_KEYS = setOf("type", "count", "region", "color")

    /** All-or-nothing parse. Throws [ProjectException] with the first error. */
    fun parse(json: String): ProjectManifest {
        val report = ReportBuilder()
        val manifest = check(json, report)
        report.firstError()?.let { throw ProjectException(it.message) }
        return manifest ?: throw ProjectException("manifest.json could not be read")
    }

    /**
     * Checks manifest.json and records every problem in [report]. Returns the manifest when the
     * canvas and background are usable, even if other fields have errors (the caller decides whether
     * to import). Returns null when the canvas or background cannot be read at all.
     */
    fun check(json: String, report: ReportBuilder, limits: CheckLimits = CheckLimits.DEFAULT): ProjectManifest? {
        // Windows editors often save a UTF-8 byte-order mark first; it is not part of the JSON.
        val text = json.removePrefix("\uFEFF").trim()
        if (text.isEmpty()) {
            report.addError(Section.MANIFEST, Code.MANIFEST_EMPTY, FILE, "manifest.json is empty. It must contain a JSON object.")
            return null
        }
        if (maxNesting(text) > limits.maxJsonDepth) {
            report.addError(
                Section.MANIFEST, Code.JSON_TOO_DEEP, FILE,
                "manifest.json is nested more than ${limits.maxJsonDepth} levels deep, which is not allowed.",
            )
            return null
        }
        val root = try {
            JsonParser.parseString(text)
        } catch (e: Exception) {
            report.addError(
                Section.MANIFEST, Code.INVALID_JSON, FILE,
                "manifest.json is not valid JSON. ${positionOf(e)}",
                "Open manifest.json in a text editor and fix the syntax at that position: check quotes, commas and brackets.",
            )
            return null
        }
        if (!root.isJsonObject) {
            report.addError(
                Section.MANIFEST, Code.NOT_AN_OBJECT, FILE,
                "manifest.json must contain one JSON object { ... } at the top level.",
            )
            return null
        }
        val o = root.asJsonObject
        unknownKeys(o, TOP_KEYS, "", report)

        // Format and version come first. An unknown version makes the rest of the file unreliable.
        val format = o.str("format")
        when {
            format == null -> report.missing("format", "Add \"format\": \"mwproj\".")
            format != ProjectLimits.FORMAT_ID -> report.addError(
                Section.MANIFEST, Code.UNSUPPORTED_FORMAT, "format",
                "Unsupported format '$format' (expected '${ProjectLimits.FORMAT_ID}').",
                "Set \"format\": \"mwproj\".",
            )
            else -> report.addPass(Section.MANIFEST, "format", "format is \"${ProjectLimits.FORMAT_ID}\"")
        }

        val versionEl = o.get("formatVersion")
        if (versionEl == null) {
            report.missing("formatVersion", "Add \"formatVersion\": ${ProjectLimits.FORMAT_VERSION}. This app reads version ${ProjectLimits.SUPPORTED_VERSIONS.joinToString()} only.")
        } else {
            val version = o.int("formatVersion")
            when {
                version == null -> report.addError(
                    Section.MANIFEST, Code.INVALID_FIELD, "formatVersion",
                    "manifest.formatVersion must be a whole number, for example ${ProjectLimits.FORMAT_VERSION}.",
                    "Set \"formatVersion\": ${ProjectLimits.FORMAT_VERSION}.",
                )
                version !in ProjectLimits.SUPPORTED_VERSIONS -> report.addError(
                    Section.MANIFEST, Code.UNSUPPORTED_VERSION, "formatVersion",
                    "Unsupported project version $version. This app reads version ${ProjectLimits.SUPPORTED_VERSIONS.joinToString()} only.",
                    "Export the project again as formatVersion ${ProjectLimits.FORMAT_VERSION}.",
                )
                else -> report.addPass(Section.MANIFEST, "formatVersion", "formatVersion $version is supported")
            }
        }

        val rawName = o.str("name")
        val cleanName = rawName?.trim()
        when {
            rawName == null -> report.missing("name", "Add \"name\": \"My Project\" (1-80 characters).")
            cleanName == null || cleanName.isEmpty() || cleanName.length > 80 -> report.addError(
                Section.MANIFEST, Code.INVALID_FIELD, "name", "manifest.name must be 1-80 characters.",
            )
            else -> report.addPass(Section.MANIFEST, "name", "name is \"$cleanName\"")
        }

        var canvasW: Int? = null
        var canvasH: Int? = null
        val canvasEl = o.get("canvas")
        if (canvasEl == null) {
            report.missing("canvas", "Add \"canvas\": { \"width\": 720, \"height\": 1280 }.")
        } else if (!canvasEl.isJsonObject) {
            report.addError(
                Section.MANIFEST, Code.INVALID_FIELD, "canvas",
                "manifest.canvas must be an object with width and height, for example { \"width\": 720, \"height\": 1280 }.",
            )
        } else {
            val c = canvasEl.asJsonObject
            val w = intField(c, "width", "canvas.width", report)
            val h = intField(c, "height", "canvas.height", report)
            if (w != null && h != null) {
                val min = ProjectLimits.MIN_CANVAS_SIDE
                val max = ProjectLimits.MAX_IMAGE_SIDE
                if (w !in min..max || h !in min..max) {
                    report.addError(
                        Section.MANIFEST, Code.INVALID_FIELD, "canvas",
                        "canvas size must be between $min and $max px per side (got ${w}x$h).",
                        "Set width and height to whole numbers from $min to $max.",
                    )
                } else {
                    canvasW = w
                    canvasH = h
                    report.addPass(Section.MANIFEST, "canvas", "canvas is ${w}x$h px")
                }
            }
        }

        var bgFile: String? = null
        var bgDepth: String? = null
        val bgEl = o.get("background")
        if (bgEl == null) {
            report.missing("background", "Add \"background\": { \"file\": \"background.png\" }.")
        } else if (!bgEl.isJsonObject) {
            report.addError(
                Section.MANIFEST, Code.INVALID_FIELD, "background",
                "manifest.background must be an object, for example { \"file\": \"background.png\" }.",
            )
        } else {
            val bg = bgEl.asJsonObject
            unknownKeys(bg, BACKGROUND_KEYS, "background.", report)
            bgFile = pngField(bg, "file", "background.file", required = true, report)
            bgDepth = pngField(bg, "depth", "background.depth", required = false, report)
            if (bgFile != null) report.addPass(Section.MANIFEST, "background.file", "background is \"$bgFile\"")
        }

        val preview = pngField(o, "preview", "preview", required = false, report)

        val layers = ArrayList<LayerSpec>()
        val layersEl = o.get("layers")
        if (layersEl == null) {
            report.addInfo(
                Section.MANIFEST, Code.NO_LAYERS, "layers",
                "No layers are listed. The background is animated with parallax only.",
            )
        } else if (!layersEl.isJsonArray) {
            report.addError(
                Section.MANIFEST, Code.INVALID_FIELD, "layers",
                "manifest.layers must be a list, for example [ { \"id\": \"body\", \"file\": \"layers/body.png\" } ].",
            )
        } else {
            val arr = layersEl.asJsonArray
            if (arr.size() > ProjectLimits.MAX_LAYERS) {
                report.addError(
                    Section.MANIFEST, Code.INVALID_FIELD, "layers",
                    "manifest.layers has ${arr.size()} layers; the maximum is ${ProjectLimits.MAX_LAYERS}.",
                )
            }
            val ids = HashSet<String>()
            arr.forEachIndexed { i, el -> parseLayer(el, i, ids, report)?.let { layers.add(it) } }
            if (arr.size() > 0 && layers.size == arr.size()) {
                report.addPass(Section.MANIFEST, "layers", "${layers.size} layer(s) are valid")
            }
        }

        val particles = ArrayList<ParticleSpec>()
        val particlesEl = o.get("particles")
        if (particlesEl != null) {
            if (!particlesEl.isJsonArray) {
                report.addError(
                    Section.MANIFEST, Code.INVALID_FIELD, "particles",
                    "manifest.particles must be a list of particle groups.",
                )
            } else {
                val arr = particlesEl.asJsonArray
                if (arr.size() > ProjectLimits.MAX_PARTICLE_GROUPS) {
                    report.addError(
                        Section.MANIFEST, Code.INVALID_FIELD, "particles",
                        "manifest.particles has ${arr.size()} groups; the maximum is ${ProjectLimits.MAX_PARTICLE_GROUPS}.",
                    )
                }
                arr.forEachIndexed { i, el -> parseParticle(el, i, report)?.let { particles.add(it) } }
            }
        }

        var mesh: String? = null
        val meshEl = o.get("mesh")
        if (meshEl != null && !meshEl.isJsonNull) {
            val raw = o.str("mesh")
            if (raw == null) {
                report.addError(Section.MANIFEST, Code.INVALID_FIELD, "mesh", "manifest.mesh must be a path to a .json file, or null.")
            } else {
                val norm = SafePath.normalize(stripDotSlash(raw))
                when {
                    norm == null -> report.addError(
                        Section.MANIFEST, Code.UNSAFE_PATH, "mesh",
                        "Unsafe or invalid path in mesh: '$raw'. Use a relative path without '..'.",
                    )
                    SafePath.extension(norm) != "json" -> report.addError(
                        Section.MANIFEST, Code.UNSUPPORTED_EXTENSION, "mesh",
                        "mesh refers to '$raw', which is not a .json file.",
                    )
                    else -> mesh = norm
                }
            }
        }

        val defaults = parseDefaults(o, report)

        val cw = canvasW
        val ch = canvasH
        val bg = bgFile
        if (cw == null || ch == null || bg == null) return null
        return ProjectManifest(
            name = cleanName?.takeIf { it.isNotEmpty() } ?: "Untitled project",
            canvasWidth = cw,
            canvasHeight = ch,
            preview = preview,
            background = bg,
            depth = bgDepth,
            layers = layers,
            particles = particles,
            mesh = mesh,
            defaults = defaults,
        )
    }

    private fun parseLayer(el: JsonElement, index: Int, ids: MutableSet<String>, report: ReportBuilder): LayerSpec? {
        val p = "layers[$index]"
        if (!el.isJsonObject) {
            report.addError(
                Section.MANIFEST, Code.INVALID_FIELD, p,
                "manifest.$p must be an object such as { \"id\": \"body\", \"file\": \"layers/body.png\" }.",
            )
            return null
        }
        val l = el.asJsonObject
        unknownKeys(l, LAYER_KEYS, "$p.", report)
        var valid = true

        val id = l.str("id")
        if (l.get("id") == null) {
            report.missing("$p.id", "Give each layer a unique id such as \"body\".")
            valid = false
        } else if (id == null || !idRegex.matches(id)) {
            report.addError(
                Section.MANIFEST, Code.INVALID_FIELD, "$p.id",
                "manifest.$p.id must be 1-32 letters, digits, '_' or '-'.",
            )
            valid = false
        } else if (!ids.add(id)) {
            report.addError(
                Section.MANIFEST, Code.INVALID_FIELD, "$p.id",
                "Layer id '$id' is used by more than one layer. Ids must be unique.",
            )
            valid = false
        }
        val label = if (id != null) "layer '$id'" else p

        val role = l.str("role") ?: "other"
        if (role !in ProjectLimits.LAYER_ROLES) {
            report.addError(
                Section.MANIFEST, Code.INVALID_FIELD, "$p.role",
                "$label has unknown role '$role'. Use one of: ${ProjectLimits.LAYER_ROLES.joinToString()}.",
            )
            valid = false
        }

        val file = pngField(l, "file", "$p.file", required = true, report)
        if (file == null) valid = false

        val x = optInt(l, "x", "$p.x", 0, report)
        val y = optInt(l, "y", "$p.y", 0, report)
        if (x == null || y == null) {
            valid = false
        } else if (x !in -ProjectLimits.MAX_IMAGE_SIDE..ProjectLimits.MAX_IMAGE_SIDE ||
            y !in -ProjectLimits.MAX_IMAGE_SIDE..ProjectLimits.MAX_IMAGE_SIDE
        ) {
            report.addError(
                Section.MANIFEST, Code.INVALID_FIELD, "$p.x",
                "$label position is out of range (x and y must be within ±${ProjectLimits.MAX_IMAGE_SIDE}).",
            )
            valid = false
        }

        val depth = optFloat(l, "depth", "$p.depth", 0.5f, 0f, 1f, report)
        if (depth == null) valid = false

        val mask = pngField(l, "mask", "$p.mask", required = false, report)
        if (l.has("mask") && mask == null) valid = false
        val glow = pngField(l, "glowMask", "$p.glowMask", required = false, report)
        if (l.has("glowMask") && glow == null) valid = false

        var rig: RigSpec? = null
        if (l.has("rig")) {
            rig = parseRig(l.get("rig"), "$p.rig", label, report)
            if (rig == null) valid = false
        }

        if (!valid || file == null || id == null || x == null || y == null || depth == null) return null
        return LayerSpec(
            id = id, role = role, file = file, x = x, y = y,
            width = 0, height = 0, // filled from PNG headers during validation
            depth = depth, mask = mask, glowMask = glow, rig = rig,
        )
    }

    private fun parseRig(el: JsonElement, path: String, label: String, report: ReportBuilder): RigSpec? {
        if (!el.isJsonObject) {
            report.addError(
                Section.MANIFEST, Code.INVALID_FIELD, path,
                "manifest.$path must be an object such as { \"mode\": \"sway\" }.",
            )
            return null
        }
        val r = el.asJsonObject
        unknownKeys(r, RIG_KEYS, "$path.", report)
        val mode = r.str("mode")
        if (mode == null) {
            report.missing("$path.mode", "Use \"mode\": \"sway\", \"ripple\" or \"flutter\".")
            return null
        }
        if (mode !in ProjectLimits.RIG_MODES) {
            report.addError(
                Section.MANIFEST, Code.INVALID_FIELD, "$path.mode",
                "$label has unknown rig mode '$mode'. Use one of: ${ProjectLimits.RIG_MODES.joinToString()}.",
            )
            return null
        }
        val amp = optFloat(r, "amplitude", "$path.amplitude", 0.01f, 0f, 0.08f, report) ?: return null
        val freq = optFloat(r, "frequency", "$path.frequency", 0.4f, 0f, 3f, report) ?: return null
        val phase = optFloat(r, "phase", "$path.phase", 0f, -1000f, 1000f, report) ?: return null
        val pivot = floatList(r, "pivot", "$path.pivot", floatArrayOf(0.5f, 0f), 2, report) ?: return null
        val dir = floatList(r, "direction", "$path.direction", floatArrayOf(1f, 0f), 2, report) ?: return null
        val len = sqrt(dir[0] * dir[0] + dir[1] * dir[1])
        if (len < 1e-3f) {
            report.addError(Section.MANIFEST, Code.INVALID_FIELD, "$path.direction", "$path.direction must not be zero.")
            return null
        }
        return RigSpec(
            mode = mode, amplitude = amp, frequency = freq, phase = phase,
            pivotX = pivot[0].coerceIn(0f, 1f), pivotY = pivot[1].coerceIn(0f, 1f),
            dirX = dir[0] / len, dirY = dir[1] / len,
        )
    }

    private fun parseParticle(el: JsonElement, index: Int, report: ReportBuilder): ParticleSpec? {
        val p = "particles[$index]"
        if (!el.isJsonObject) {
            report.addError(Section.MANIFEST, Code.INVALID_FIELD, p, "manifest.$p must be an object such as { \"type\": \"fire\" }.")
            return null
        }
        val q = el.asJsonObject
        unknownKeys(q, PARTICLE_KEYS, "$p.", report)
        val type = q.str("type")
        if (type == null) {
            report.missing("$p.type", "Use one of: ${ProjectLimits.PARTICLE_TYPES.joinToString()}.")
            return null
        }
        if (type !in ProjectLimits.PARTICLE_TYPES) {
            report.addError(
                Section.MANIFEST, Code.INVALID_FIELD, "$p.type",
                "manifest.$p.type must be one of ${ProjectLimits.PARTICLE_TYPES.joinToString()}.",
            )
            return null
        }
        val count = optInt(q, "count", "$p.count", 40, report) ?: return null
        if (count !in 0..ProjectLimits.MAX_PARTICLES_PER_GROUP) {
            report.addError(
                Section.MANIFEST, Code.INVALID_FIELD, "$p.count",
                "manifest.$p.count must be 0-${ProjectLimits.MAX_PARTICLES_PER_GROUP}.",
            )
            return null
        }
        val region = floatList(q, "region", "$p.region", floatArrayOf(0f, 0f, 1f, 1f), 4, report) ?: return null
        if (region.any { it !in 0f..1f } || region[0] + region[2] > 1.001f || region[1] + region[3] > 1.001f) {
            report.addError(
                Section.MANIFEST, Code.INVALID_FIELD, "$p.region",
                "manifest.$p.region must be [x,y,w,h] in 0..1 and inside the screen.",
            )
            return null
        }
        val color: Int? = if (q.has("color")) {
            parseColor(q.str("color"), "$p.color", report) ?: return null
        } else {
            null
        }
        return ParticleSpec(type, count, region[0], region[1], region[2], region[3], color)
    }

    private fun parseDefaults(o: JsonObject, report: ReportBuilder): ProjectTuning? {
        val effectsEl = o.get("effects")
        val motionEl = o.get("motion")
        if (effectsEl == null && motionEl == null) return null
        var ok = true

        var motionJson: JsonObject? = null
        if (motionEl != null) {
            if (motionEl.isJsonObject) {
                motionJson = motionEl.asJsonObject
            } else {
                report.addError(Section.MANIFEST, Code.INVALID_FIELD, "motion", "manifest.motion must be an object.")
                ok = false
            }
        }
        var effectsJson: JsonObject? = null
        if (effectsEl != null) {
            if (effectsEl.isJsonObject) {
                effectsJson = normalizeColors(effectsEl.asJsonObject, report)
            } else {
                report.addError(
                    Section.MANIFEST, Code.INVALID_FIELD, "effects",
                    "manifest.effects must be an object such as { \"outerGlowColor\": \"#00E5FF\" }.",
                )
                ok = false
            }
        }

        val motion = try {
            if (motionJson != null) gson.fromJson(motionJson, MotionSettings::class.java) else MotionSettings()
        } catch (e: RuntimeException) {
            report.addError(
                Section.MANIFEST, Code.INVALID_FIELD, "motion",
                "manifest.motion has a value of the wrong type. Numbers and true/false are expected.",
            )
            return null
        }
        val effects = try {
            if (effectsJson != null) gson.fromJson(effectsJson, EffectSettings::class.java) else EffectSettings()
        } catch (e: RuntimeException) {
            report.addError(
                Section.MANIFEST, Code.INVALID_FIELD, "effects",
                "manifest.effects has a value of the wrong type. Numbers, true/false and #RRGGBB colors are expected.",
            )
            return null
        }
        if (!ok) return null
        return sanitize(ProjectTuning(motion = motion, effects = effects))
    }

    /** Converts "#RRGGBB"/"#AARRGGBB" strings in *Color keys into ARGB ints so Gson can read them. */
    private fun normalizeColors(obj: JsonObject, report: ReportBuilder): JsonObject {
        val copy = JsonObject()
        for ((k, v) in obj.entrySet()) {
            if (k.endsWith("Color") && v.isJsonPrimitive && v.asJsonPrimitive.isString) {
                val argb = parseColor(v.asString, "effects.$k", report)
                if (argb != null) copy.addProperty(k, argb)
            } else {
                copy.add(k, v)
            }
        }
        return copy
    }

    private fun parseColor(text: String?, field: String, report: ReportBuilder): Int? {
        if (text == null || !hexRegex.matches(text)) {
            report.addError(
                Section.MANIFEST, Code.INVALID_FIELD, field,
                "manifest.$field must be a hex color like #00E5FF.",
            )
            return null
        }
        val digits = text.substring(1)
        val argb = if (digits.length == 6) "FF$digits" else digits
        return java.lang.Long.parseLong(argb, 16).toInt()
    }

    /** Accepts "./layers/x.png" as "layers/x.png". Everything else is still checked by SafePath. */
    private fun stripDotSlash(raw: String): String {
        var p = raw.trim().replace('\\', '/')
        while (p.startsWith("./")) p = p.substring(2)
        return p
    }

    /**
     * Reads an optional or required PNG path field. Returns the normalized path, or null when the
     * field is absent or invalid (in which case the problem has been recorded).
     */
    private fun pngField(obj: JsonObject, key: String, path: String, required: Boolean, report: ReportBuilder): String? {
        val el = obj.get(key)
        if (el == null) {
            if (required) report.missing(path, "Add \"$key\": \"<name>.png\".")
            return null
        }
        val raw = obj.str(key)
        if (raw == null) {
            report.addError(
                Section.MANIFEST, Code.INVALID_FIELD, path,
                "manifest.$path must be text such as \"layers/body.png\" (got ${kindName(el)}).",
            )
            return null
        }
        return pngPath(raw, path, report)
    }

    private fun pngPath(raw: String, path: String, report: ReportBuilder): String? {
        val norm = SafePath.normalize(stripDotSlash(raw))
        if (norm == null) {
            report.addError(
                Section.MANIFEST, Code.UNSAFE_PATH, path,
                "Unsafe or invalid path in $path: '$raw'. Use a relative path with / separators, such as layers/body.png, without '..'.",
            )
            return null
        }
        if (SafePath.extension(norm) != "png") {
            report.addError(
                Section.MANIFEST, Code.UNSUPPORTED_EXTENSION, path,
                "$path refers to '$raw', which is not a .png file. Only PNG images can be used here.",
                "Export the image as PNG and point manifest.json at the new file.",
            )
            return null
        }
        return norm
    }

    private fun intField(obj: JsonObject, key: String, path: String, report: ReportBuilder): Int? {
        val el = obj.get(key)
        if (el == null) {
            report.missing(path, "Add \"$key\": a whole number.")
            return null
        }
        val v = obj.int(key)
        if (v == null) {
            report.addError(
                Section.MANIFEST, Code.INVALID_FIELD, path,
                "manifest.$path must be a whole number (got ${kindName(el)}).",
            )
        }
        return v
    }

    private fun optInt(obj: JsonObject, key: String, path: String, default: Int, report: ReportBuilder): Int? {
        val el = obj.get(key) ?: return default
        val v = obj.int(key)
        if (v == null) {
            report.addError(
                Section.MANIFEST, Code.INVALID_FIELD, path,
                "manifest.$path must be a whole number (got ${kindName(el)}).",
            )
        }
        return v
    }

    private fun optFloat(
        obj: JsonObject, key: String, path: String, default: Float, min: Float, max: Float, report: ReportBuilder,
    ): Float? {
        val el = obj.get(key) ?: return default
        val v = obj.num(key)?.toFloat()
        if (v == null) {
            report.addError(
                Section.MANIFEST, Code.INVALID_FIELD, path,
                "manifest.$path must be a number (got ${kindName(el)}).",
            )
            return null
        }
        if (v !in min..max) {
            report.addError(
                Section.MANIFEST, Code.INVALID_FIELD, path,
                "manifest.$path must be between $min and $max (got $v).",
            )
            return null
        }
        return v
    }

    private fun floatList(
        obj: JsonObject, key: String, path: String, default: FloatArray, size: Int, report: ReportBuilder,
    ): FloatArray? {
        val el = obj.get(key) ?: return default
        if (!el.isJsonArray || el.asJsonArray.size() != size) {
            report.addError(
                Section.MANIFEST, Code.INVALID_FIELD, path,
                "manifest.$path must be a list of $size numbers, such as [${default.joinToString(", ")}].",
            )
            return null
        }
        val out = FloatArray(size)
        for ((i, item) in el.asJsonArray.withIndex()) {
            if (!item.isJsonPrimitive || !item.asJsonPrimitive.isNumber || !item.asDouble.isFinite()) {
                report.addError(Section.MANIFEST, Code.INVALID_FIELD, path, "manifest.$path must contain only numbers.")
                return null
            }
            out[i] = item.asFloat
        }
        return out
    }

    /** Reports each key that the app does not use. These are warnings: the file can still be imported. */
    private fun unknownKeys(obj: JsonObject, allowed: Set<String>, prefix: String, report: ReportBuilder) {
        for (key in obj.keySet()) {
            if (key in allowed) continue
            val hint = closestName(key, allowed)
            report.addWarning(
                Section.MANIFEST, Code.UNKNOWN_FIELD, "$prefix$key",
                "Unknown field '$prefix$key' is ignored." + (hint?.let { " Did you mean '$prefix$it'?" } ?: ""),
            )
        }
    }

    private fun closestName(key: String, allowed: Set<String>): String? =
        allowed.minByOrNull { editDistance(key.lowercase(), it.lowercase()) }
            ?.takeIf { editDistance(key.lowercase(), it.lowercase()) <= 2 }

    private fun editDistance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            prev = cur
        }
        return prev[b.length]
    }

    /** Deepest nesting of { } or [ ] outside strings. Checked before parsing so deep input cannot exhaust the stack. */
    private fun maxNesting(text: String): Int {
        var depth = 0
        var max = 0
        var inString = false
        var escape = false
        for (c in text) {
            if (inString) {
                if (escape) {
                    escape = false
                } else if (c == '\\') {
                    escape = true
                } else if (c == '"') {
                    inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{', '[' -> {
                    depth++
                    if (depth > max) max = depth
                }
                '}', ']' -> depth--
            }
        }
        return max
    }

    private fun positionOf(e: Exception): String {
        val m = Regex("line (\\d+) column (\\d+)").find(e.message.orEmpty())
        return if (m != null) "Problem at line ${m.groupValues[1]}, column ${m.groupValues[2]}." else "Check quotes, commas and brackets."
    }

    private fun kindName(el: JsonElement): String = when {
        el.isJsonNull -> "null"
        el.isJsonArray -> "a list"
        el.isJsonObject -> "an object"
        el.asJsonPrimitive.isBoolean -> "true or false"
        el.asJsonPrimitive.isNumber -> "a number"
        else -> "text"
    }

    private fun ReportBuilder.missing(path: String, hint: String) {
        addError(
            Section.MANIFEST, Code.MISSING_FIELD, path,
            "manifest.$path is missing. $hint",
            hint,
        )
    }

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.num(key: String): Double? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble?.takeIf { it.isFinite() }

    private fun JsonObject.int(key: String): Int? =
        num(key)?.takeIf { it % 1.0 == 0.0 && it >= Int.MIN_VALUE && it <= Int.MAX_VALUE }?.toInt()

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
