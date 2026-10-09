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

    /** Smoothing rate (1/s) for a soft_body layer's tilt lag. [damping] 0 follows quickly, 1 is heavy and slow. */
    fun softBodyRate(damping: Float): Float {
        val d = damping.coerceIn(0f, 1f)
        return SOFT_RATE_FAST + (SOFT_RATE_HEAVY - SOFT_RATE_FAST) * d
    }

    /** Layer offset, in UV units, produced by a full normalized tilt on a soft_body layer (before its mask). */
    const val SOFT_TILT_UV = 0.015f

    private const val SOFT_RATE_FAST = 18f
    private const val SOFT_RATE_HEAVY = 1.5f
}
