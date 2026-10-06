package com.livevip.app.model

import android.graphics.Matrix
import kotlin.math.max

/** One immutable transform model shared by the preview and encoder compositor. */
data class CompositionState(
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    val outputWidth: Int = 1280,
    val outputHeight: Int = 720,
    val scale: Float = 1f,
    val translationX: Float = 0f,
    val translationY: Float = 0f,
    val rotation: Float = 0f,
    val fitMode: FitMode = FitMode.FIT
) {
    fun withSource(width: Int, height: Int): CompositionState = copy(sourceWidth = width, sourceHeight = height)

    fun reset(): CompositionState = copy(scale = 1f, translationX = 0f, translationY = 0f, rotation = 0f)

    fun fit(): CompositionState = copy(fitMode = FitMode.FIT, scale = 1f, translationX = 0f, translationY = 0f)

    fun fill(): CompositionState = copy(fitMode = FitMode.FILL, scale = 1f, translationX = 0f, translationY = 0f)

    fun zoomBy(factor: Float): CompositionState = copy(scale = (scale * factor).coerceIn(0.25f, 8f))

    fun dragBy(dx: Float, dy: Float): CompositionState = copy(translationX = translationX + dx, translationY = translationY + dy)

    /** Returns a matrix in output pixels. X/Y are never scaled independently. */
    fun outputMatrix(): Matrix {
        val matrix = Matrix()
        if (sourceWidth <= 0 || sourceHeight <= 0) return matrix
        val sourceAspect = sourceWidth.toFloat() / sourceHeight
        val outputAspect = outputWidth.toFloat() / outputHeight
        val base = if (fitMode == FitMode.FILL) {
            max(outputWidth.toFloat() / sourceWidth, outputHeight.toFloat() / sourceHeight)
        } else {
            minOf(outputWidth.toFloat() / sourceWidth, outputHeight.toFloat() / sourceHeight)
        } * scale
        val dx = (outputWidth - sourceWidth * base) / 2f + translationX
        val dy = (outputHeight - sourceHeight * base) / 2f + translationY
        matrix.setScale(base, base)
        matrix.postTranslate(dx, dy)
        if (rotation != 0f) matrix.postRotate(rotation, outputWidth / 2f, outputHeight / 2f)
        return matrix
    }

    fun isLandscape(): Boolean = outputWidth >= outputHeight
}

enum class FitMode { FIT, FILL }

enum class OutputFormat(val label: String, val width: Int, val height: Int) {
    LANDSCAPE("Landscape 16:9", 1280, 720),
    VERTICAL("Vertical 9:16", 720, 1280)
}

enum class StreamQuality(val label: String, val width: Int, val height: Int, val bitrate: Int) {
    P480("480p", 854, 480, 2_500_000),
    P720("720p", 1280, 720, 4_500_000),
    P1080("1080p", 1920, 1080, 6_000_000)
}
