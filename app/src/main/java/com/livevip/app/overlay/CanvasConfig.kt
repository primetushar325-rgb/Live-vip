package com.livevip.app.overlay

import org.json.JSONObject

/**
 * OUTPUT COMPOSITION — the model that defines the ACTUAL encoded output:
 * aspect (16:9 / 9:16), resolution, and how the video is transformed
 * (fit/fill/zoom/pan) inside the frame.
 *
 * [com.livevip.app.streaming.LiveStreamingManager] prepares the H.264
 * encoder AT this resolution and the GL filter ([CanvasVideoTransformRender])
 * composites the video quad inside the encoded frames — preview and stream
 * share ONE pipeline, so what the user sees is exactly what viewers receive.
 */

/**
 * Output format — exactly two user-facing choices (LANDSCAPE 16:9 and
 * VERTICAL 9:16). OTHER is an internal fallback for inferred dimensions,
 * never selectable in the UI.
 */
enum class CanvasAspect(val label: String, val ratioW: Int, val ratioH: Int) {
    LANDSCAPE_16_9("16:9 Landscape", 16, 9),
    PORTRAIT_9_16("9:16 Vertical", 9, 16),
    OTHER("Other", 0, 0);

    fun ratio(): Float = if (ratioW == 0 || ratioH == 0) 16f / 9f
    else ratioW.toFloat() / ratioH.toFloat()

    companion object {
        fun from(name: String?): CanvasAspect =
            entries.firstOrNull { it.name == name } ?: LANDSCAPE_16_9

        /** Infer the format from output dimensions (16:9 / 9:16 / other). */
        fun from(w: Int, h: Int): CanvasAspect = when {
            w * 16 == h * 9 && w < h -> PORTRAIT_9_16
            w * 9 == h * 16 && w > h -> LANDSCAPE_16_9
            else -> OTHER
        }
    }
}

/**
 * How the main video sits inside the canvas.
 *
 * FIT     — entire video visible, letterbox/pillarbox bars (DEFAULT).
 * FILL    — video covers the whole canvas, overflow cropped.
 * STRETCH — explicit distortion. Only when the user asks for it. Never default.
 * CUSTOM  — user zoom/pan/rotation on top of a proportion-correct FIT base.
 */
enum class FitMode(val label: String) {
    FIT("Fit"), FILL("Fill"), STRETCH("Stretch"), CUSTOM("Custom")
}


/**
 * Main-video transform inside the canvas. All pure math — unit tested.
 *
 * @param scale zoom multiplier on the FIT base (1 = fit). >1 zooms in.
 * @param offsetX/offsetY pan, fraction of canvas size, origin center,
 *        Y positive = down (screen convention).
 * @param rotationDeg counter-clockwise rotation around the video center.
 * @param cropL/T/R/B fraction of the source cropped from each edge (0..0.45).
 */
data class VideoTransform(
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val rotationDeg: Float = 0f,
    val fitMode: FitMode = FitMode.FIT,
    val cropL: Float = 0f,
    val cropT: Float = 0f,
    val cropR: Float = 0f,
    val cropB: Float = 0f
) {
    fun cropValid(): Boolean =
        cropL in 0f..0.45f && cropT in 0f..0.45f &&
            cropR in 0f..0.45f && cropB in 0f..0.45f &&
            (cropL + cropR) < 0.9f && (cropT + cropB) < 0.9f

    fun toJson(): JSONObject = JSONObject().apply {
        put("scale", scale.toDouble())
        put("offsetX", offsetX.toDouble())
        put("offsetY", offsetY.toDouble())
        put("rotation", rotationDeg.toDouble())
        put("fitMode", fitMode.name)
        put("cropL", cropL.toDouble())
        put("cropT", cropT.toDouble())
        put("cropR", cropR.toDouble())
        put("cropB", cropB.toDouble())
    }

    companion object {
        fun fromJson(o: JSONObject): VideoTransform = VideoTransform(
            scale = o.optDouble("scale", 1.0).toFloat().coerceIn(0.1f, 8f),
            offsetX = o.optDouble("offsetX", 0.0).toFloat().coerceIn(-1.5f, 1.5f),
            offsetY = o.optDouble("offsetY", 0.0).toFloat().coerceIn(-1.5f, 1.5f),
            rotationDeg = o.optDouble("rotation", 0.0).toFloat(),
            fitMode = runCatching { FitMode.valueOf(o.optString("fitMode", "FIT")) }
                .getOrDefault(FitMode.FIT),
            cropL = o.optDouble("cropL", 0.0).toFloat().coerceIn(0f, 0.45f),
            cropT = o.optDouble("cropT", 0.0).toFloat().coerceIn(0f, 0.45f),
            cropR = o.optDouble("cropR", 0.0).toFloat().coerceIn(0f, 0.45f),
            cropB = o.optDouble("cropB", 0.0).toFloat().coerceIn(0f, 0.45f)
        )
    }

    /**
     * The transformed video quad in normalized device coordinates (±1 =
     * full canvas edge). Returns 20 floats matching RootEncoder's
     * square-vertex layout:
     * 4 vertices × (X, Y, Z, U, V), triangle-strip order
     * (bottom-left, bottom-right, top-left, top-right).
     *
     * The filter-chain input texture contains the source frame stretched to
     * the canvas FBO, so full-range UVs on the quad reproduce the source at
     * correct proportions on whatever rect we draw.
     */
    fun quadFor(sourceW: Int, sourceH: Int, canvasW: Int, canvasH: Int): FloatArray {
        val cW = canvasW.coerceAtLeast(1).toFloat()
        val cH = canvasH.coerceAtLeast(1).toFloat()
        val canvasAspect = cW / cH

        // Effective source rect after crop.
        val cl = cropL.coerceIn(0f, 0.45f); val cr = cropR.coerceIn(0f, 0.45f)
        val ct = cropT.coerceIn(0f, 0.45f); val cb = cropB.coerceIn(0f, 0.45f)
        val srcW = (sourceW.coerceAtLeast(1) * (1f - cl - cr)).toFloat()
        val srcH = (sourceH.coerceAtLeast(1) * (1f - ct - cb)).toFloat()
        val srcAspect = srcW / srcH

        var halfW: Float
        var halfH: Float
        when (fitMode) {
            FitMode.STRETCH -> { halfW = 0.5f; halfH = 0.5f }
            FitMode.FILL -> if (srcAspect > canvasAspect) {
                halfW = 0.5f * (srcAspect / canvasAspect); halfH = 0.5f
            } else {
                halfW = 0.5f; halfH = 0.5f * (canvasAspect / srcAspect)
            }
            else -> {
                // FIT and CUSTOM keep proportions: base = contain.
                if (srcAspect > canvasAspect) {
                    halfW = 0.5f; halfH = 0.5f * (canvasAspect / srcAspect)
                } else {
                    halfW = 0.5f * (srcAspect / canvasAspect); halfH = 0.5f
                }
                if (fitMode == FitMode.CUSTOM) {
                    val z = scale.coerceIn(0.05f, 8f)
                    halfW *= z; halfH *= z
                }
            }
        }

        // Fraction → NDC: half-extents 0.5 = full canvas ⇒ ×2.
        halfW *= 2f
        halfH *= 2f

        // Center + pan. Screen-space Y (down) → NDC Y (up): negate.
        val cx = offsetX.coerceIn(-1.5f, 1.5f)
        val cy = offsetY.coerceIn(-1.5f, 1.5f)
        val centerX = cx * 2f // fraction of canvas → NDC units.
        val centerY = -cy * 2f

        val rad = Math.toRadians(rotationDeg.toDouble())
        val cos = Math.cos(rad).toFloat()
        val sin = Math.sin(rad).toFloat()

        fun vertex(lx: Float, ly: Float, u: Float, v: Float): FloatArray =
            floatArrayOf(
                centerX + lx * cos - ly * sin,
                centerY + lx * sin + ly * cos,
                0f, u, v
            )

        // UVs: the visible (cropped) source region. V=1 is the top of the frame.
        val u0 = cl; val u1 = 1f - cr
        val v0 = cb; val v1 = 1f - ct

        val bl = vertex(-halfW, -halfH, u0, v0)
        val br = vertex(halfW, -halfH, u1, v0)
        val tl = vertex(-halfW, halfH, u0, v1)
        val tr = vertex(halfW, halfH, u1, v1)
        return bl + br + tl + tr
    }
}

/**
 * The output composition persisted with the Saved Live.
 * [width]×[height] IS the encoder resolution used for the live stream.
 */
data class CanvasConfig(
    val aspect: CanvasAspect = CanvasAspect.LANDSCAPE_16_9,
    val width: Int = 1280,
    val height: Int = 720,
    val backgroundColor: Int = 0xFF000000.toInt(),
    val transform: VideoTransform = VideoTransform()
) {
    init {
        require(width % 2 == 0 && height % 2 == 0) {
            "Encoder dimensions must be even (H.264 macroblocks)."
        }
        require(width in 128..4096 && height in 128..4096) { "Canvas out of range." }
    }

    fun resolutionLabel(): String = "${width}×$height"

    fun aspectLabel(): String = "${aspect.label} • ${resolutionLabel()}"

    fun copyWithResolution(w: Int, h: Int): CanvasConfig {
        // Cross-multiplication: w/h == 9/16 ⇔ 16w == 9h, etc.
        val aspect = when {
            w * 16 == h * 9 && w < h -> CanvasAspect.PORTRAIT_9_16
            w * 9 == h * 16 && w > h -> CanvasAspect.LANDSCAPE_16_9
            else -> CanvasAspect.OTHER
        }
        return copy(aspect = aspect, width = w - (w % 2), height = h - (h % 2))
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("aspect", aspect.name)
        put("width", width)
        put("height", height)
        put("background", backgroundColor.toLong())
        put("transform", transform.toJson())
    }

    companion object {
        fun fromJson(raw: String?): CanvasConfig? {
            if (raw.isNullOrBlank() || raw == "{}") return null
            return try {
                val o = JSONObject(raw)
                val w = o.optInt("width", 1280)
                val h = o.optInt("height", 720)
                CanvasConfig(
                    aspect = CanvasAspect.from(o.optString("aspect")),
                    width = w - (w % 2),
                    height = h - (h % 2),
                    backgroundColor = o.optLong("background", 0xFF000000.toLong()).toInt(),
                    transform = VideoTransform.fromJson(o.optJSONObject("transform") ?: JSONObject())
                )
            } catch (_: Throwable) {
                null
            }
        }
    }
}

/**
 * Pure preview-sizing math (unit tested): fit a canvas-aspect surface inside
 * a container, preserving the canvas aspect ratio — the editor preview must
 * show the TRUE broadcast proportions (9:16 canvas ⇒ centered portrait).
 */
object CanvasPreviewMath {

    /** Returns (width, height) in px: the largest canvas-aspect rect fitting the container. */
    fun fit(containerW: Float, containerH: Float, canvasW: Float, canvasH: Float): Pair<Float, Float> {
        if (containerW <= 0f || containerH <= 0f) return 0f to 0f
        val canvasAspect = (canvasW.coerceAtLeast(1f)) / (canvasH.coerceAtLeast(1f))
        var w = containerW
        var h = w / canvasAspect
        if (h > containerH) {
            h = containerH
            w = h * canvasAspect
        }
        return w to h
    }
}

