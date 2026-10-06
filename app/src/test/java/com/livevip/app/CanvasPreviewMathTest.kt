package com.livevip.app.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PART 3: the preview-surface sizing math — a 9:16 canvas must show a
 * centered PORTRAIT surface inside any container (no giant black bars, no
 * squished preview), and a 16:9 canvas a landscape one.
 */
class CanvasPreviewMathTest {

    private val eps = 0.01f

    @Test
    fun `9by16 canvas in a wide container becomes a centered portrait rect`() {
        // Container 800×600 (landscape), canvas 1080×1920 (9:16).
        val (w, h) = CanvasPreviewMath.fit(800f, 600f, 1080f, 1920f)
        // Height-bound: h = 600, w = 600 * (9/16) = 337.5.
        assertEquals(600f, h, eps)
        assertEquals(337.5f, w, eps)
        assertEquals(1080f / 1920f, w / h, 0.001f)
    }

    @Test
    fun `16by9 canvas in a tall container becomes a landscape rect`() {
        // Container 400×800 (portrait), canvas 1920×1080 (16:9).
        val (w, h) = CanvasPreviewMath.fit(400f, 800f, 1920f, 1080f)
        assertEquals(400f, w, eps)
        assertEquals(225f, h, eps)
        assertEquals(16f / 9f, w / h, 0.001f)
    }

    @Test
    fun `same aspect fills the container exactly`() {
        val (w, h) = CanvasPreviewMath.fit(600f, 400f, 1920f, 1280f) // both 3:2
        assertEquals(600f, w, eps)
        assertEquals(400f, h, eps)
    }

    @Test
    fun `square canvas in a wide container is width-bound`() {
        val (w, h) = CanvasPreviewMath.fit(800f, 600f, 1080f, 1080f)
        assertEquals(600f, w, eps)
        assertEquals(600f, h, eps)
    }

    @Test
    fun `degenerate container returns zero safely`() {
        val (w, h) = CanvasPreviewMath.fit(0f, 0f, 1080f, 1920f)
        assertEquals(0f, w, 0f)
        assertEquals(0f, h, 0f)
    }

    @Test
    fun `never exceeds the container in either dimension`() {
        // Exhaustive-ish: aspect ratios across the board.
        val canvases = listOf(
            1080f to 1920f, 1920f to 1080f, 1080f to 1080f,
            1080f to 1350f, 720f to 1280f, 480f to 854f
        )
        canvases.forEach { (cw, chh) ->
            listOf(360f to 640f, 400f to 400f, 800f to 600f).forEach { (kw, kh) ->
                val (w, h) = CanvasPreviewMath.fit(kw, kh, cw, chh)
                org.junit.Assert.assertTrue("w overflow $w>$kw", w <= kw + eps)
                org.junit.Assert.assertTrue("h overflow $h>$kh", h <= kh + eps)
                org.junit.Assert.assertTrue("w degenerate", w > 0f)
                org.junit.Assert.assertTrue("h degenerate", h > 0f)
                assertEquals(cw / chh, w / h, 0.01f)
            }
        }
    }
}
