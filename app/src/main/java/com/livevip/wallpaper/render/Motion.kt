package com.livevip.wallpaper.render

import kotlin.math.exp

/** Pure math used by the renderer; kept free of Android types so it can be unit tested. */
object Motion {

    /** Frame-rate independent exponential smoothing toward [target]. [rate] is in 1/s (higher = snappier). */
    fun damp(current: Float, target: Float, dtSeconds: Float, rate: Float): Float {
        val k = 1f - exp(-(dtSeconds.coerceAtLeast(0f)) * rate.coerceAtLeast(0f))
        return current + (target - current) * k
    }

    /** Clamps a value to [-limit, limit]. */
    fun limit(value: Float, limit: Float): Float = value.coerceIn(-limit, limit)

    /** Converts a tilt angle in radians to a normalized -1..1 value, saturating at [maxRadians]. */
    fun angleToNormalized(radians: Float, maxRadians: Float = 0.5f): Float =
        (radians / maxRadians).coerceIn(-1f, 1f)
}
