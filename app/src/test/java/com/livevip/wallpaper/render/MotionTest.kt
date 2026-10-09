package com.livevip.wallpaper.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionTest {
    @Test
    fun dampingMovesTowardTargetWithoutOvershoot() {
        var v = 0f
        var previous = 0f
        repeat(120) {
            v = Motion.damp(v, 1f, 1f / 60f, 6f)
            assertTrue("never exceeds target", v <= 1f + 1e-6f)
            assertTrue("monotonic", v >= previous - 1e-6f)
            previous = v
        }
        assertTrue("converges", v > 0.99f)
    }

    @Test
    fun dampingIsFrameRateIndependent() {
        val fast = (0 until 60).fold(0f) { acc, _ -> Motion.damp(acc, 1f, 1f / 60f, 6f) }
        val slow = (0 until 30).fold(0f) { acc, _ -> Motion.damp(acc, 1f, 1f / 30f, 6f) }
        assertEquals(fast, slow, 1e-4f)
    }

    @Test
    fun limitClampsSymmetrically() {
        assertEquals(0.5f, Motion.limit(2f, 0.5f), 1e-6f)
        assertEquals(-0.5f, Motion.limit(-2f, 0.5f), 1e-6f)
        assertEquals(0.2f, Motion.limit(0.2f, 0.5f), 1e-6f)
    }

    @Test
    fun angleMapsAndSaturates() {
        assertEquals(0f, Motion.angleToNormalized(0f), 1e-6f)
        assertEquals(1f, Motion.angleToNormalized(10f), 1e-6f)
        assertEquals(-1f, Motion.angleToNormalized(-10f), 1e-6f)
        assertEquals(0.5f, Motion.angleToNormalized(0.25f, 0.5f), 1e-6f)
    }
}
