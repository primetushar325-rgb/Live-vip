package com.livevip.app.core

import org.json.JSONObject

/**
 * THE ONE COMPOSITION — the single source of truth shared by the PREVIEW
 * and the ENCODER (spec: "ONE COMPOSITION PIPELINE").
 *
 * There is no second transform calculation anywhere in the app: the preview
 * surface and the encoded frames are produced by the same GL filter fed
 * with this exact state.
 *
 *  - outputWidth/outputHeight  = the real encoder resolution (16:9 or 9:16)
 *  - sourceWidth/sourceHeight  = the real decoded video dimensions
 *  - scale                     = zoom multiplier on the FIT base (1 = fit)
 *  - translationX/Y            = pan, fraction of output size, 0 = centered,
 *                                Y positive = down (screen convention)
 *  - rotation                  = degrees CCW around the video center
 *                                (reserved — no rotation UI, default 0)
 *
 * quadFor() turns this state into the GL quad (normalized device coords,
 * 4 vertices × x/y/z/u/v, triangle-strip order). Pure math — unit tested.
 */
data class LiveCompositionState(
    val outputWidth: Int,
    val outputHeight: Int,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val scale: Float = 1f,
    val translationX: Float = 0f,
    val translationY: Float = 0f,
    val rotation: Float = 0f,
    val fit: ContentFit = ContentFit.FIT
) {
    init {
        require(outputWidth % 2 == 0 && outputHeight % 2 == 0) {
            "Encoder dimensions must be even (H.264 macroblocks)."
        }
        require(outputWidth in 128..4096 && outputHeight in 128..4096) {
            "Output resolution out of range."
        }
    }

    /**
     * Switch output format (16:9 ↔ 9:16): the composition is RECALCULATED —
     * default transform (scale 1, centered, FIT). A stale matrix from the
     * previous aspect ratio is never carried over (spec: the video must
     * stay visible, never an extreme crop or off-screen quad).
     */
    fun withFormat(width: Int, height: Int, srcWidth: Int, srcHeight: Int): LiveCompositionState =
        LiveCompositionState(
            outputWidth = width - (width % 2),
            outputHeight = height - (height % 2),
            sourceWidth = srcWidth,
            sourceHeight = srcHeight
        )

    /** The transformed video quad in NDC — 20 floats for the GL filter. */
    fun quadFor(): FloatArray {
        val cW = outputWidth.coerceAtLeast(1).toFloat()
        val cH = outputHeight.coerceAtLeast(1).toFloat()
        val frameAspect = cW / cH
        val srcW = sourceWidth.coerceAtLeast(1).toFloat()
        val srcH = sourceHeight.coerceAtLeast(1).toFloat()
        val srcAspect = srcW / srcH

        var halfW: Float
        var halfH: Float
        when (fit) {
            // FILL: cover the whole output, overflow cropped. No distortion.
            ContentFit.FILL -> if (srcAspect > frameAspect) {
                halfW = 0.5f * (srcAspect / frameAspect); halfH = 0.5f
            } else {
                halfW = 0.5f; halfH = 0.5f * (frameAspect / srcAspect)
            }
            else -> {
                // FIT (and the CUSTOM base): contain — entire video visible,
                // letterbox/pillarbox bars. Proportions always preserved.
                if (srcAspect > frameAspect) {
                    halfW = 0.5f; halfH = 0.5f * (frameAspect / srcAspect)
                } else {
                    halfW = 0.5f * (srcAspect / frameAspect); halfH = 0.5f
                }
                if (fit == ContentFit.CUSTOM) {
                    val z = scale.coerceIn(0.05f, 8f)
                    halfW *= z; halfH *= z
                }
            }
        }

        // Fraction of frame → NDC units (half-extent 0.5 = full frame ⇒ ×2).
        halfW *= 2f
        halfH *= 2f

        val tx = translationX.coerceIn(-1.5f, 1.5f) * 2f
        val ty = -translationY.coerceIn(-1.5f, 1.5f) * 2f // screen-down → NDC-up

        val rad = Math.toRadians(rotation.toDouble())
        val cos = Math.cos(rad).toFloat()
        val sin = Math.sin(rad).toFloat()

        fun vertex(lx: Float, ly: Float, u: Float, v: Float): FloatArray = floatArrayOf(
            tx + lx * cos - ly * sin,
            ty + lx * sin + ly * cos,
            0f, u, v
        )

        val bl = vertex(-halfW, -halfH, 0f, 0f)
        val br = vertex(halfW, -halfH, 1f, 0f)
        val tl = vertex(-halfW, halfH, 0f, 1f)
        val tr = vertex(halfW, halfH, 1f, 1f)
        return bl + br + tl + tr
    }

    fun toJson(): JSONObject = JSONObject()
        .put("outputWidth", outputWidth)
        .put("outputHeight", outputHeight)
        .put("sourceWidth", sourceWidth)
        .put("sourceHeight", sourceHeight)
        .put("scale", scale.toDouble())
        .put("translationX", translationX.toDouble())
        .put("translationY", translationY.toDouble())
        .put("rotation", rotation.toDouble())
        .put("fit", fit.name)

    companion object {
        fun fromJson(o: JSONObject): LiveCompositionState = LiveCompositionState(
            outputWidth = o.optInt("outputWidth", 1280),
            outputHeight = o.optInt("outputHeight", 720),
            sourceWidth = o.optInt("sourceWidth", 1280),
            sourceHeight = o.optInt("sourceHeight", 720),
            scale = o.optDouble("scale", 1.0).toFloat().coerceIn(0.05f, 8f),
            translationX = o.optDouble("translationX", 0.0).toFloat().coerceIn(-1.5f, 1.5f),
            translationY = o.optDouble("translationY", 0.0).toFloat().coerceIn(-1.5f, 1.5f),
            rotation = o.optDouble("rotation", 0.0).toFloat(),
            fit = runCatching { ContentFit.valueOf(o.optString("fit", "FIT")) }
                .getOrDefault(ContentFit.FIT)
        )
    }
}

/**
 * How the video sits inside the output frame.
 * FIT    — entire video visible, bars where needed (default).
 * FILL   — video covers the whole frame, overflow cropped.
 * CUSTOM — user zoom/pan on top of the FIT base (gesture state).
 * The source is NEVER stretched — proportions are always preserved.
 */
enum class ContentFit { FIT, FILL, CUSTOM }
