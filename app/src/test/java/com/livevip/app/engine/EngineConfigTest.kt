package com.livevip.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stream URL handling — key material is data, never a log message. */
class EngineConfigTest {

    private fun config(url: String, key: String = "app-123") = EngineConfig(
        url = url,
        key = key,
        videoWidth = 1280,
        videoHeight = 720,
        fps = 30,
        videoBitrateKbps = 4500,
        keyframeIntervalSec = 2,
        audioBitrateKbps = 128,
        sampleRate = 44_100,
        stereo = true,
        echoCanceler = true,
        noiseSuppressor = true,
        autoReconnect = true,
        maxReconnectAttempts = 5
    )

    @Test
    fun `fullUrl joins url and key with exactly one slash`() {
        assertEquals(
            "rtmp://a.example.tv/live/app-123",
            config("rtmp://a.example.tv/live").fullUrl()
        )
        assertEquals(
            "rtmp://a.example.tv/live/app-123",
            config("rtmp://a.example.tv/live/").fullUrl()
        )
        assertEquals(
            "rtmp://a.example.tv/live/app-123",
            config("rtmp://a.example.tv/live//").fullUrl()
        )
    }

    @Test
    fun `fullUrl trims whitespace`() {
        assertEquals(
            "rtmps://s.example.tv/app/app-123",
            config("  rtmps://s.example.tv/app ").fullUrl()
        )
    }

    @Test
    fun `url validation accepts only rtmp and rtmps`() {
        assertTrue(config("rtmp://x.y/live").isValidUrl())
        assertTrue(config("rtmps://x.y/live").isValidUrl())
        assertTrue(config("RTMP://x.y/live").isValidUrl())
        assertFalse(config("http://x.y/live").isValidUrl())
        assertFalse(config("https://x.y/live").isValidUrl())
        assertFalse(config("file://x").isValidUrl())
        assertFalse(config("").isValidUrl())
        assertFalse(config("no-scheme").isValidUrl())
    }

    @Test
    fun `empty key is reported invalid`() {
        assertFalse(config("rtmp://x.y/live", key = "").isValidUrl())
    }

    @Test
    fun `sanitize masks credentials in surfaced errors`() {
        assertEquals(
            "rtmps://***",
            EngineConfig.sanitize("rtmp://secret.example.tv/live")
        )
        assertEquals(
            "rtmps://***",
            EngineConfig.sanitize("rtmps://secret.example.tv/live/app-123")
        )
        assertEquals("Decoder failed", EngineConfig.sanitize("Decoder failed"))
    }
}
