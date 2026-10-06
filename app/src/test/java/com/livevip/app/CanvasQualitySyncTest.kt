package com.livevip.app.overlay

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PART 3: canvas ↔ encoder-resolution sync — the invariant BroadcastPlan
 * enforces ("canvas dims must equal quality dims") is maintained on save,
 * so Start Live never fails with a confusing mismatch.
 */
class CanvasQualitySyncTest {

    private fun canvasJson(w: Int, h: Int): String = CanvasConfig(
        aspect = if (h > w) CanvasAspect.PORTRAIT_9_16 else CanvasAspect.LANDSCAPE_16_9,
        width = w, height = h
    ).toJson().toString()

    @Test
    fun `matching dims are untouched`() {
        val json = canvasJson(1080, 1920)
        assertEquals(json, CanvasQualitySync.ensureMatches(json, 1080, 1920))
    }

    @Test
    fun `quality change re-resolves the canvas`() {
        val json = canvasJson(1920, 1080)
        val synced = CanvasQualitySync.ensureMatches(json, 1280, 720)
        val canvas = CanvasConfig.fromJson(synced)!!
        assertEquals(1280, canvas.width)
        assertEquals(720, canvas.height)
    }

    @Test
    fun `portrait sync infers the portrait aspect`() {
        val json = canvasJson(1920, 1080)
        val synced = CanvasQualitySync.ensureMatches(json, 1080, 1920)
        val canvas = CanvasConfig.fromJson(synced)!!
        assertEquals(CanvasAspect.PORTRAIT_9_16, canvas.aspect)
    }

    @Test
    fun `odd encoder dims are coerced even`() {
        val json = canvasJson(1920, 1080)
        val synced = CanvasQualitySync.ensureMatches(json, 1079, 1921)
        val canvas = CanvasConfig.fromJson(synced)!!
        assertEquals(0, canvas.width % 2)
        assertEquals(0, canvas.height % 2)
    }

    @Test
    fun `no canvas means no change`() {
        assertEquals("", CanvasQualitySync.ensureMatches("", 1280, 720))
        assertNull(CanvasConfig.fromJson(CanvasQualitySync.ensureMatches("junk", 1280, 720)))
    }

    @Test
    fun `transform and background survive the sync`() {
        val canvas = CanvasConfig(
            width = 1920, height = 1080,
            backgroundColor = 0xFF123456.toInt(),
            transform = VideoTransform(fitMode = FitMode.FILL, offsetX = 0.2f, rotationDeg = 12f)
        )
        val synced = CanvasConfig.fromJson(
            CanvasQualitySync.ensureMatches(canvas.toJson().toString(), 1280, 720)
        )!!
        assertEquals(0xFF123456.toInt(), synced.backgroundColor)
        assertEquals(FitMode.FILL, synced.transform.fitMode)
        assertEquals(0.2f, synced.transform.offsetX, 1e-4f)
        assertEquals(12f, synced.transform.rotationDeg, 1e-4f)
        assertTrue(JSONObject(synced.toJson().toString()).length() > 0)
    }
}
