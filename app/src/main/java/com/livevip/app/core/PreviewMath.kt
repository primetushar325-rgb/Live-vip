package com.livevip.app.core

/**
 * Pure preview-surface sizing math (unit tested): fit an output-aspect
 * surface inside a container, preserving the output aspect ratio — the
 * preview must show the TRUE broadcast proportions (9:16 output ⇒ centered
 * portrait surface).
 */
object PreviewMath {

    /** Returns (width, height) in px: the largest output-aspect rect fitting the container. */
    fun fit(containerW: Float, containerH: Float, outputW: Float, outputH: Float): Pair<Float, Float> {
        if (containerW <= 0f || containerH <= 0f) return 0f to 0f
        val aspect = (outputW.coerceAtLeast(1f)) / (outputH.coerceAtLeast(1f))
        var w = containerW
        var h = w / aspect
        if (h > containerH) {
            h = containerH
            w = h * aspect
        }
        return w to h
    }
}
