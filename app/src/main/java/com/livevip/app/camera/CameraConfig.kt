package com.livevip.app.camera

import android.util.Size
import kotlin.math.abs

/**
 * Resolution / FPS presets and "closest supported" fallback logic.
 * If a device cannot provide the requested configuration we pick the
 * nearest supported one instead of crashing.
 */
object CameraConfig {

    data class Preset(val label: String, val width: Int, val height: Int)

    val RESOLUTIONS = listOf(
        Preset("360p", 640, 360),
        Preset("480p", 854, 480),
        Preset("720p", 1280, 720),
        Preset("1080p", 1920, 1080)
    )

    val FPS_OPTIONS = listOf(24, 30, 60)

    /** Bitrate presets in kbps. */
    val BITRATE_OPTIONS = listOf(1000, 1800, 2500, 3500, 4500, 6000)

    /** Recommended quality presets (LOW / MEDIUM / HIGH). */
    fun recommendedBitrateKbps(height: Int, fps: Int): Int = when {
        height >= 1080 -> if (fps > 30) 6000 else 4500
        height >= 720 -> if (fps > 30) 3500 else 2500
        height >= 480 -> 1800
        else -> 1000
    }

    /**
     * Pick the supported camera size closest to the requested one.
     * Returns the request unchanged when the list is empty (the encoder
     * still validates and the engine falls back internally).
     */
    fun closestSupported(requested: Size, supported: List<Size>): Size {
        if (supported.isEmpty()) return requested
        if (supported.any { it.width == requested.width && it.height == requested.height }) {
            return requested
        }
        val target = requested.width.toLong() * requested.height.toLong()
        return supported.minByOrNull { abs(it.width.toLong() * it.height.toLong() - target) }
            ?: requested
    }
}
