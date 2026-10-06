package com.livevip.app.overlay

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PART 2 CORE: the main-video transform math that drives the GL compositor.
 *
 * quadFor() returns 20 floats: BL, BR, TL, TR × (x, y, z, u, v) in NDC
 * (±1 = full canvas edge).
 */
class VideoTransformTest {

    private val eps = 1e-4f

    /** BL, BR, TL, TR x/y accessors. */
    private fun x(quad: FloatArray, i: Int) = quad[i * 5]
    private fun y(quad: FloatArray, i: Int) = quad[i * 5 + 1]
    private fun u(quad: FloatArray, i: Int) = quad[i * 5 + 3]
    private fun v(quad: FloatArray, i: Int) = quad[i * 5 + 4]

    @Test
    fun `same aspect FIT covers the full canvas`() {
        // 16:9 source in a 16:9 canvas → quad is exactly the full frame.
        val q = VideoTransform().quadFor(1920, 1080, 1280, 720)
        assertEquals(-1f, x(q, 0), eps) // BL x
        assertEquals(1f, x(q, 1), eps) // BR x
        assertEquals(-1f, y(q, 0), eps) // BL y
        assertEquals(1f, y(q, 2), eps) // TL y
        // UVs are the full source.
        assertEquals(0f, u(q, 0), eps)
        assertEquals(1f, u(q, 1), eps)
        assertEquals(1f, v(q, 2), eps)
    }

    @Test
    fun `16by9 source in 9by16 FIT is pillarboxed without stretching`() {
        // Shorts canvas, landscape source: video must keep 16:9 proportions.
        val q = VideoTransform().quadFor(1920, 1080, 1080, 1920)
        // Width fills the canvas…
        assertEquals(-1f, x(q, 0), eps)
        assertEquals(1f, x(q, 1), eps)
        // …height is contain-ed (canvasAspect/srcAspect * 2 * 0.5).
        val expectedHalfH = (1080f / 1920f) / (1920f / 1080f) // 0.3164
        assertEquals(-expectedHalfH, y(q, 0), eps)
        assertEquals(expectedHalfH, y(q, 2), eps)
        // Proportion check in canvas pixels: 1080 × (2*0.3164*1920/2)=607 → 16:9.
        val pixelW = 1080f
        val pixelH = 2f * expectedHalfH * 1920f
        assertEquals(16f / 9f, pixelW / pixelH, 0.001f)
    }

    @Test
    fun `FILL covers the canvas and crops the source`() {
        val q = VideoTransform(fitMode = FitMode.FILL).quadFor(1920, 1080, 1080, 1920)
        // Height covers…
        assertEquals(-1f, y(q, 0), eps)
        assertEquals(1f, y(q, 2), eps)
        // …width overflows (cropped by the canvas bounds).
        assertTrue(x(q, 1) > 1f)
        assertTrue(x(q, 0) < -1f)
        // The drawn quad still keeps the source proportions.
        val quadAspect = (x(q, 1) - x(q, 0)) / (y(q, 2) - y(q, 0))
        // In pixels: (2*halfW*1080) / (2*1*1920) == 16/9.
        val pixelW = (x(q, 1) - x(q, 0)) * 1080f / 2f
        val pixelH = (y(q, 2) - y(q, 0)) * 1920f / 2f
        assertEquals(16f / 9f, pixelW / pixelH, 0.01f)
        assertEquals(quadAspect > 0f, true)
    }

    @Test
    fun `STRETCH fills the canvas ignoring proportions`() {
        val q = VideoTransform(fitMode = FitMode.STRETCH).quadFor(1920, 1080, 1080, 1920)
        assertEquals(-1f, x(q, 0), eps)
        assertEquals(1f, x(q, 1), eps)
        assertEquals(-1f, y(q, 0), eps)
        assertEquals(1f, y(q, 2), eps)
    }

    @Test
    fun `CUSTOM zoom scales the FIT base`() {
        val base = VideoTransform().quadFor(1920, 1080, 1080, 1920)
        val zoomed = VideoTransform(
            fitMode = FitMode.CUSTOM, scale = 2f
        ).quadFor(1920, 1080, 1080, 1920)
        assertEquals(2f * y(base, 2), y(zoomed, 2), eps)
        // X already full → clips beyond the canvas.
        assertTrue(x(zoomed, 1) > 1f)
    }

    @Test
    fun `pan moves the quad in canvas fractions`() {
        val q = VideoTransform(
            fitMode = FitMode.CUSTOM, offsetX = 0.25f, offsetY = -0.1f
        ).quadFor(1920, 1080, 1920, 1080)
        // offsetX 0.25 of canvas → NDC +0.5; y is negated (screen Y down).
        assertEquals(0.5f, (x(q, 0) + x(q, 1)) / 2f, eps)
        assertEquals(0.2f, (y(q, 0) + y(q, 2)) / 2f, eps)
    }

    @Test
    fun `90 degree rotation swaps the quad extents`() {
        val q0 = VideoTransform().quadFor(1920, 1080, 1080, 1920)
        val q90 = VideoTransform(rotationDeg = 90f).quadFor(1920, 1080, 1080, 1920)
        // Unrotated: x ±1, y ±0.3164 → rotated: x ±0.3164, y ±1.
        assertEquals(y(q0, 2), x(q90, 1), eps)
        assertEquals(y(q0, 0), x(q90, 0), eps)
        assertEquals(x(q0, 1), y(q90, 2), eps)
        assertEquals(x(q0, 0), y(q90, 0), eps)
    }

    @Test
    fun `crop maps UVs to the visible source sub-rect`() {
        val q = VideoTransform(cropL = 0.1f, cropR = 0.2f, cropT = 0.25f, cropB = 0.15f)
            .quadFor(1920, 1080, 1920, 1080)
        assertEquals(0.1f, u(q, 0), eps) // BL u = cropL
        assertEquals(0.8f, u(q, 1), eps) // BR u = 1 - cropR
        assertEquals(0.15f, v(q, 0), eps) // BL v = cropB
        assertEquals(0.75f, v(q, 2), eps) // TL v = 1 - cropT
        // Cropping then FIT: cropped source is 1344×648 (aspect 2.074),
        // wider than the 16:9 canvas → width fills, height contains.
        val srcAspect = (1920f * 0.7f) / (1080f * 0.6f)
        val halfW = (x(q, 1) - x(q, 0)) / 2f
        val halfH = (y(q, 2) - y(q, 0)) / 2f
        assertEquals(1f, halfW, eps)
        assertEquals((1920f / 1080f) / srcAspect, halfH, eps)
    }

    @Test
    fun `crop validity rejects extremes`() {
        assertTrue(VideoTransform().cropValid())
        assertTrue(VideoTransform(cropL = 0.45f, cropR = 0.44f).cropValid())
        assertEquals(false, VideoTransform(cropL = 0.5f).cropValid())
        assertEquals(false, VideoTransform(cropL = 0.45f, cropR = 0.46f).cropValid())
    }

    @Test
    fun `quad survives a JSON round trip`() {
        val t = VideoTransform(
            scale = 1.5f, offsetX = 0.2f, offsetY = -0.3f, rotationDeg = 45f,
            fitMode = FitMode.FILL, cropL = 0.05f, cropT = 0.1f, cropR = 0.15f, cropB = 0.2f
        )
        val parsed = VideoTransform.fromJson(JSONObject(t.toJson().toString()))
        val a = t.quadFor(1280, 720, 720, 1280)
        val b = parsed.quadFor(1280, 720, 720, 1280)
        assertTrue(a.contentEquals(b))
    }
}
