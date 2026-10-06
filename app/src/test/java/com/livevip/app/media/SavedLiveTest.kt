package com.livevip.app.media

import com.livevip.app.overlay.FitMode
import com.livevip.app.overlay.VideoTransform
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PART 5 — "SAVED LIVE" persistence: the selected video reference and the
 * gesture transform must round-trip through JSON so a reopened app restores
 * exactly what the user composed. (No project system — just these fields.)
 */
class SavedLiveTest {

    @Test
    fun `selected video round trips through json`() {
        val video = SelectedVideo(
            uri = "content://media/external/video/media/42",
            name = "holiday.mp4",
            durationMs = 2_282_000,
            width = 1920, height = 1080,
            fps = 24, hasAudio = true, rotation = 90
        )
        val parsed = SelectedVideo.fromJson(video.toJson().toString())
        assertNotNull(parsed)
        assertEquals(video.uri, parsed!!.uri)
        assertEquals(video.name, parsed.name)
        assertEquals(video.durationMs, parsed.durationMs)
        assertEquals(video.width, parsed.width)
        assertEquals(video.height, parsed.height)
        assertEquals(video.fps, parsed.fps)
        assertEquals(video.hasAudio, parsed.hasAudio)
        assertEquals(video.rotation, parsed.rotation)
        // Rotation metadata flips the display orientation.
        assertEquals(1080, parsed.displayWidth)
        assertEquals(1920, parsed.displayHeight)
    }

    @Test
    fun `transform round trips through json`() {
        val t = VideoTransform(
            scale = 1.6f, offsetX = 0.25f, offsetY = -0.3f,
            fitMode = FitMode.CUSTOM
        )
        val parsed = VideoTransform.fromJson(JSONObject(t.toJson().toString()))
        assertEquals(t.scale, parsed.scale, 1e-6f)
        assertEquals(t.offsetX, parsed.offsetX, 1e-6f)
        assertEquals(t.offsetY, parsed.offsetY, 1e-6f)
        assertEquals(FitMode.CUSTOM, parsed.fitMode)
    }

    @Test
    fun `blank and corrupt saved data degrade to null not crash`() {
        assertNull(SelectedVideo.fromJson(null))
        assertNull(SelectedVideo.fromJson(""))
        assertNull(SelectedVideo.fromJson("{}"))
        assertNull(SelectedVideo.fromJson("not json at all"))
    }

    @Test
    fun `info label carries the real detected fields`() {
        val video = SelectedVideo(
            uri = "content://x", name = "a.mp4",
            durationMs = 2_282_000, width = 1920, height = 1080,
            fps = 30, hasAudio = true
        )
        // 38:02 duration, 1920×1080, 30 FPS
        assertTrue(video.infoLabel().contains("1920×1080"))
        assertTrue(video.infoLabel().contains("30 FPS"))
        assertTrue(video.infoLabel().contains("38:02"))
    }
}
