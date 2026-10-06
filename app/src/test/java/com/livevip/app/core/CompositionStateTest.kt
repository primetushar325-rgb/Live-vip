package com.livevip.app.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE ONE COMPOSITION: quadFor() drives BOTH the preview and the encoder —
 * its math is the product contract (no stretching, no invisible video,
 * correct letterboxing in every source/format combination).
 */
class CompositionStateTest {

    private val eps = 1e-4f

    private fun x(q: FloatArray, i: Int) = q[i * 5]
    private fun y(q: FloatArray, i: Int) = q[i * 5 + 1]
    private fun u(q: FloatArray, i: Int) = q[i * 5 + 3]
    private fun v(q: FloatArray, i: Int) = q[i * 5 + 4]

    @Test
    fun `same aspect FIT covers the full output`() {
        val s = LiveCompositionState(1280, 720, 1920, 1080)
        val q = s.quadFor()
        assertEquals(-1f, x(q, 0), eps) // BL x
        assertEquals(1f, x(q, 1), eps)  // BR x
        assertEquals(-1f, y(q, 0), eps) // BL y
        assertEquals(1f, y(q, 2), eps)  // TL y
        assertEquals(0f, u(q, 0), eps)
        assertEquals(1f, u(q, 1), eps)
        assertEquals(1f, v(q, 2), eps)
    }

    @Test
    fun `16by9 source in 9by16 output letterboxes without stretching`() {
        // Source 1920×1080 (16:9), output 720×1280 (9:16).
        val s = LiveCompositionState(720, 1280, 1920, 1080)
        val q = s.quadFor()
        // Contain: width-bound. halfW = 1 (full width), halfH = 16/9... in NDC:
        // halfH = 0.5 * (frameAspect/srcAspect) * 2, computed below:
        val srcAspect = 1920f / 1080f
        val outAspect = 720f / 1280f
        val expectedHalfH = 0.5f * (outAspect / srcAspect) * 2f
        assertEquals(1f, x(q, 1), eps)                       // full width
        assertEquals(expectedHalfH, y(q, 2), eps)            // letterboxed height
        assertTrue("no vertical stretch", expectedHalfH < 1f)
    }

    @Test
    fun `9by16 source in 16by9 output pillarboxes without stretching`() {
        val s = LiveCompositionState(1280, 720, 1080, 1920)
        val q = s.quadFor()
        val srcAspect = 1080f / 1920f
        val outAspect = 1280f / 720f
        val expectedHalfW = 0.5f * (srcAspect / outAspect) * 2f
        assertEquals(1f, y(q, 2), eps)                       // full height
        assertEquals(expectedHalfW, x(q, 1), eps)            // pillarboxed width
        assertTrue("no horizontal stretch", expectedHalfW < 1f)
    }

    @Test
    fun `FILL covers the output and crops the overflow`() {
        // 16:9 source in 9:16 output, FILL: height full, width overflows.
        val s = LiveCompositionState(720, 1280, 1920, 1080, fit = ContentFit.FILL)
        val q = s.quadFor()
        assertEquals(1f, y(q, 2), eps)
        assertTrue("fills wider than the frame (cropped)", x(q, 1) > 1f)
    }

    @Test
    fun `CUSTOM zoom scales the FIT base`() {
        val base = LiveCompositionState(1280, 720, 1920, 1080, fit = ContentFit.CUSTOM)
        val zoomed = base.copy(scale = 2f)
        val qb = base.quadFor()
        val qz = zoomed.quadFor()
        assertEquals(2f * x(qb, 1), x(qz, 1), eps)
        assertEquals(2f * y(qb, 2), y(qz, 2), eps)
    }

    @Test
    fun `translation moves the quad and stays centered at zero`() {
        val s = LiveCompositionState(1280, 720, 1920, 1080, translationX = 0.25f)
        val q = s.quadFor()
        // Center shifted right by 0.25 * 2 = 0.5 NDC; quad width unchanged.
        assertEquals(0.5f, (x(q, 0) + x(q, 1)) / 2f, eps)
        assertEquals(0f, (y(q, 0) + y(q, 2)) / 2f, eps)
    }

    @Test
    fun `withFormat recalculates and never carries a stale matrix`() {
        val zoomed = LiveCompositionState(
            1280, 720, 1920, 1080,
            scale = 1.7f, translationX = 0.8f, translationY = -0.6f,
            fit = ContentFit.CUSTOM
        )
        val switched = zoomed.withFormat(720, 1280, 1920, 1080)
        assertEquals(1f, switched.scale, eps)
        assertEquals(0f, switched.translationX, eps)
        assertEquals(0f, switched.translationY, eps)
        assertEquals(ContentFit.FIT, switched.fit)
        assertEquals(720, switched.outputWidth)
        assertEquals(1280, switched.outputHeight)
        // FIT in the new format stays fully visible.
        val q = switched.quadFor()
        assertTrue(x(q, 0) >= -1f && x(q, 1) <= 1f)
        assertTrue(y(q, 0) >= -1f && y(q, 2) <= 1f)
    }

    @Test
    fun `even dimensions enforced for h264`() {
        val threw = runCatching {
            LiveCompositionState(1281, 721, 1920, 1080)
        }.isFailure
        assertTrue(threw)
    }

    @Test
    fun `json round trip preserves the composition`() {
        val s = LiveCompositionState(
            720, 1280, 1920, 1080,
            scale = 1.4f, translationX = 0.2f, translationY = -0.3f,
            rotation = 0f, fit = ContentFit.CUSTOM
        )
        val parsed = LiveCompositionState.fromJson(JSONObject(s.toJson().toString()))
        assertEquals(s, parsed)
    }

    @Test
    fun `corrupt json degrades to safe defaults`() {
        val parsed = LiveCompositionState.fromJson(JSONObject("{}"))
        assertEquals(1f, parsed.scale, eps)
        assertEquals(0f, parsed.translationX, eps)
        assertEquals(ContentFit.FIT, parsed.fit)
    }
}
