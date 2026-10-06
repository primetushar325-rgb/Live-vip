package com.livevip.app.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PART 2: layer animation math composited into the encoded stream.
 */
class LayerAnimationsTest {

    @Test
    fun `NONE is the identity`() {
        val m = LayerAnimations.modulate(OverlayAnimation.NONE, 123.456f)
        assertEquals(1f, m.scaleFactor, 0f)
        assertEquals(1f, m.alphaFactor, 0f)
        assertEquals(0f, m.offsetPercentX, 0f)
        assertEquals(0f, m.offsetPercentY, 0f)
    }

    @Test
    fun `FADE_IN ramps from 0 to 1 and clamps after`() {
        assertEquals(0f, LayerAnimations.modulate(OverlayAnimation.FADE_IN, 0f).alphaFactor, 0.01f)
        val mid = LayerAnimations.modulate(OverlayAnimation.FADE_IN, 0.6f).alphaFactor
        assertTrue("mid should be partial: $mid", mid in 0.01f..0.99f)
        assertEquals(1f, LayerAnimations.modulate(OverlayAnimation.FADE_IN, 5f).alphaFactor, 0f)
    }

    @Test
    fun `PULSE stays within bounds and is periodic`() {
        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        var t = 0f
        while (t < LayerAnimations.PULSE_PERIOD_SEC * 2) {
            val s = LayerAnimations.modulate(OverlayAnimation.PULSE, t).scaleFactor
            assertTrue(s > 0.99f && s < 1.09f)
            min = minOf(min, s); max = maxOf(max, s)
            t += 0.05f
        }
        // It actually oscillates.
        assertTrue(max - min > 0.05f)
        // Periodic: same phase one period later.
        val a = LayerAnimations.modulate(OverlayAnimation.PULSE, 0.3f)
        val b = LayerAnimations.modulate(OverlayAnimation.PULSE, 0.3f + LayerAnimations.PULSE_PERIOD_SEC)
        assertEquals(a.scaleFactor, b.scaleFactor, 0.001f)
    }

    @Test
    fun `BLINK alternates between visible and dim`() {
        val visible = LayerAnimations.modulate(OverlayAnimation.BLINK, 0.1f).alphaFactor
        val dim = LayerAnimations.modulate(OverlayAnimation.BLINK, 0.8f).alphaFactor
        assertEquals(1f, visible, 0f)
        assertEquals(0.15f, dim, 0f)
    }

    @Test
    fun `FLOAT offset is bounded and periodic`() {
        var t = 0f
        while (t < 10f) {
            val m = LayerAnimations.modulate(OverlayAnimation.FLOAT, t)
            assertTrue(m.offsetPercentY >= -2f && m.offsetPercentY <= 2f)
            assertEquals(0f, m.offsetPercentX, 0f)
            t += 0.07f
        }
    }

    @Test
    fun `BOUNCE offset is always at or below the base position`() {
        var t = 0f
        while (t < 10f) {
            val m = LayerAnimations.modulate(OverlayAnimation.BOUNCE, t)
            assertTrue("bounce must not push down: ${m.offsetPercentY}", m.offsetPercentY <= 0f)
            assertTrue(m.offsetPercentY >= -6f)
            t += 0.07f
        }
    }

    @Test
    fun `SLIDE_IN starts displaced and settles at the base position`() {
        val left = LayerAnimations.modulate(OverlayAnimation.SLIDE_IN_LEFT, 0f)
        assertEquals(LayerAnimations.SLIDE_IN_DISTANCE_PERCENT, left.offsetPercentX, 0.01f)
        assertEquals(
            0f,
            LayerAnimations.modulate(OverlayAnimation.SLIDE_IN_LEFT, 10f).offsetPercentX,
            0f
        )
        val right = LayerAnimations.modulate(OverlayAnimation.SLIDE_IN_RIGHT, 0f)
        assertEquals(-LayerAnimations.SLIDE_IN_DISTANCE_PERCENT, right.offsetPercentX, 0.01f)
    }

    @Test
    fun `every animation keeps alpha and scale in sane ranges`() {
        OverlayAnimation.entries.forEach { mode ->
            var t = 0f
            while (t < 12f) {
                val m = LayerAnimations.modulate(mode, t)
                assertTrue("$mode alpha ${m.alphaFactor}", m.alphaFactor in 0f..1f)
                assertTrue("$mode scale ${m.scaleFactor}", m.scaleFactor in 0.5f..2f)
                assertTrue("$mode offX ${m.offsetPercentX}", m.offsetPercentX in -60f..60f)
                assertTrue("$mode offY ${m.offsetPercentY}", m.offsetPercentY in -30f..30f)
                t += 0.13f
            }
        }
    }
}
