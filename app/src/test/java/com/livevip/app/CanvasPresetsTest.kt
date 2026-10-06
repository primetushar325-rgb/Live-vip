package com.livevip.app.streaming

import com.livevip.app.overlay.CanvasAspect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PART 2 OUTPUT VALIDATION: honest encoder/preset checks with suggestions.
 */
class CanvasPresetsTest {

    /** Typical mid-range hardware encoder caps. */
    private fun midRangeCaps() = CapabilityDetector.VideoEncoderCaps(
        mime = "video/avc", isHardware = true,
        maxWidth = 1920, maxHeight = 1920, maxFps = 30,
        supportsConstantBitrate = true
    )

    /** High-end caps (UHD-capable). */
    private fun uhdCaps() = CapabilityDetector.VideoEncoderCaps(
        mime = "video/avc", isHardware = true,
        maxWidth = 4096, maxHeight = 4096, maxFps = 60,
        supportsConstantBitrate = true
    )

    @Test
    fun `preset lists cover all aspects with real resolutions`() {
        CanvasAspect.entries.forEach { aspect ->
            val options = CanvasPresets.optionsFor(aspect)
            assertTrue("no options for $aspect", options.isNotEmpty())
            options.forEach { p ->
                assertEquals("even width for ${p.label}", 0, p.width % 2)
                assertEquals("even height for ${p.label}", 0, p.height % 2)
                assertTrue(p.width >= 128 && p.height >= 128)
            }
        }
    }

    @Test
    fun `shorts presets are portrait 9 to 16`() {
        val shorts = CanvasPresets.optionsFor(CanvasAspect.PORTRAIT_9_16)
        shorts.forEach { p -> assertTrue(p.height > p.width) }
        assertEquals(1080, shorts.first().width)
        assertEquals(1920, shorts.first().height)
    }

    @Test
    fun `valid 1080p at 30fps is supported on mid range device`() {
        val r = CanvasPresets.validate(1920, 1080, 30, 4500, midRangeCaps())
        assertTrue(r.reason ?: "", r.supported)
        assertNull(r.suggestion)
    }

    @Test
    fun `odd dimensions are rejected with an even suggestion`() {
        val r = CanvasPresets.validate(1081, 1920, 30, 4000, uhdCaps())
        assertFalse(r.supported)
        assertNotNull(r.suggestion)
        assertEquals(0, r.suggestion!!.width % 2)
        assertEquals(0, r.suggestion!!.height % 2)
        assertTrue(r.reason!!.contains("even", ignoreCase = true))
    }

    @Test
    fun `too small resolution is rejected`() {
        val r = CanvasPresets.validate(64, 64, 30, 500, uhdCaps())
        assertFalse(r.supported)
        assertNotNull(r.suggestion)
    }

    @Test
    fun `beyond encoder caps gets a working fallback`() {
        // Mid-range: max 1920×1920 → 3840 wide is impossible.
        val r = CanvasPresets.validate(3840, 2160, 30, 12000, midRangeCaps())
        assertFalse(r.supported)
        assertNotNull(r.suggestion)
        val s = r.suggestion!!
        assertTrue("suggestion must fit the caps", s.width <= 1920 && s.height <= 1920)
    }

    @Test
    fun `60fps heavy canvas on a 30fps device is rejected with a 30fps suggestion`() {
        // "1080×1920 at 60 FPS not supported — use 720×1280 at 30 FPS".
        val r = CanvasPresets.validate(1080, 1920, 60, 4000, midRangeCaps())
        assertFalse(r.supported)
        assertTrue(r.reason!!.contains("60 FPS"))
        assertTrue(r.reason!!.contains("30 FPS"))
        assertNotNull(r.suggestion)
    }

    @Test
    fun `60fps is allowed on a capable device`() {
        val r = CanvasPresets.validate(1920, 1080, 60, 6000, uhdCaps())
        assertTrue(r.reason ?: "", r.supported)
    }

    @Test
    fun `missing encoder is reported honestly`() {
        val r = CanvasPresets.validate(1280, 720, 30, 2500, null)
        assertFalse(r.supported)
        assertTrue(r.reason!!.contains("encoder", ignoreCase = true))
    }

    @Test
    fun `shorts and landscape helpers return canvas sized presets`() {
        val caps = midRangeCaps()
        val shorts = CanvasPresets.shortsCanvas(caps)
        assertTrue(shorts.height > shorts.width)
        assertEquals(0, shorts.width % 2)

        val landscape = CanvasPresets.landscapeCanvas(caps)
        assertTrue(landscape.width > landscape.height)
        assertEquals(0, landscape.width % 2)
    }
}
