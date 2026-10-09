package com.livevip.wallpaper.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The lag used by soft_body layers: heavier damping follows the tilt more slowly, never overshooting. */
class SoftBodyMotionTest {

    /** Runs the lag for [seconds] at 60 fps toward a constant tilt of 1 and returns the value at the end. */
    private fun lagAfter(damping: Float, seconds: Float): Float {
        val rate = Motion.softBodyRate(damping)
        var v = 0f
        val frames = (seconds * 60f).toInt()
        repeat(frames) {
            v = Motion.damp(v, 1f, 1f / 60f, rate)
            assertTrue("never overshoots the tilt", v <= 1f + 1e-6f)
        }
        return v
    }

    @Test
    fun rateFallsAsDampingRises() {
        val fast = Motion.softBodyRate(0f)
        val mid = Motion.softBodyRate(0.5f)
        val heavy = Motion.softBodyRate(1f)
        assertTrue(fast > mid && mid > heavy && heavy > 0f)
        // Out-of-range input is clamped, so bad values cannot produce a negative or huge rate.
        assertEquals(fast, Motion.softBodyRate(-3f), 1e-6f)
        assertEquals(heavy, Motion.softBodyRate(7f), 1e-6f)
    }

    @Test
    fun lightDampingFollowsQuicklyAndHeavyDampingLagsVisibly() {
        assertTrue("light damping is close to the tilt after 0.2 s", lagAfter(0f, 0.2f) > 0.9f)
        assertTrue("heavy damping is still far behind after 0.2 s", lagAfter(1f, 0.2f) < 0.5f)
    }

    @Test
    fun lagEventuallySettlesOnTheTilt() {
        assertTrue(lagAfter(1f, 4f) > 0.99f)
    }

    @Test
    fun tiltOffsetIsSmallSoTheMotionStaysSubtle() {
        // Full tilt moves a soft_body part by at most this fraction of its layer size, before its mask and strength.
        assertTrue(Motion.SOFT_TILT_UV in 0.001f..0.03f)
    }
}
