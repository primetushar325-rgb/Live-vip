package com.livevip.app.store

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SAVED LIVES persistence contract: full JSON round trip for the saved-live
 * record (the stream KEY itself lives only in encrypted storage, never in
 * this JSON) and for library entries.
 */
class SavedLiveJsonTest {

    @Test
    fun `saved live round trip preserves every field`() {
        val live = SavedLive(
            id = 7L,
            name = "YouTube Evening",
            videoUri = "content://media/external/video/media/42",
            videoName = "promo.mp4",
            outputFormat = "VERTICAL_9_16",
            quality = "720p",
            fps = 30,
            compositionJson = """{"outputWidth":720}""",
            serverUrl = "rtmp://a.example.tv/live",
            videoAudio = true,
            mic = false
        )
        val parsed = SavedLive.fromJson(JSONObject(live.toJson().toString()))
        assertEquals(live, parsed)
    }

    @Test
    fun `saved live json contains no key material`() {
        val live = SavedLive(
            id = 1L, name = "x", videoUri = "", videoName = "",
            outputFormat = "LANDSCAPE_16_9", quality = "720p", fps = 30,
            compositionJson = "", serverUrl = "rtmp://a.example.tv/live",
            videoAudio = true, mic = false
        )
        val json = live.toJson().toString()
        assertTrue("no key field in json", !json.contains("\"key\""))
        assertTrue("no secret value in json", !json.contains("abcd-1234-secret"))
    }

    @Test
    fun `library video round trip preserves metadata`() {
        val video = LibraryVideo(
            id = 3L,
            uri = "content://media/external/video/media/9",
            name = "promo.mp4",
            durationMs = 65_000,
            width = 1920,
            height = 1080,
            rotation = 0,
            fps = 30,
            hasAudio = true,
            thumbPath = "/data/user/0/com.livevip.app/files/thumbs/9.jpg"
        )
        val parsed = LibraryVideo.fromJson(JSONObject(video.toJson().toString()))
        assertEquals(video, parsed)
        assertEquals("1:05", video.durationLabel())
        assertEquals("1920×1080", video.resolutionLabel())
        assertTrue(video.infoLabel().contains("1920×1080"))
        assertTrue(video.infoLabel().contains("1:05"))
        // hasAudio=true adds no note; the label is resolution • fps • duration.
        assertTrue(!video.infoLabel().contains("no audio"))
        // A silent video is flagged honestly.
        assertTrue(video.copy(hasAudio = false).infoLabel().contains("no audio"))
    }
}
