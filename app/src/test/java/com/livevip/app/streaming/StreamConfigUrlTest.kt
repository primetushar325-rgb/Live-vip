package com.livevip.app.streaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** URL building/validation for RTMP destinations. */
class StreamConfigUrlTest {

    private fun config(url: String, key: String = "k") =
        StreamConfig(
            url = url, key = key,
            videoWidth = 1280, videoHeight = 720, fps = 30,
            videoBitrateKbps = 2500, keyframeIntervalSec = 2,
            audioBitrateKbps = 128, sampleRate = 44_100, stereo = true,
            echoCanceler = true, noiseSuppressor = true,
            autoReconnect = true, maxReconnectAttempts = 3
        )

    @Test
    fun `full url appends the stream key`() {
        assertEquals(
            "rtmp://a.rtmp.youtube.com/live2/my-key",
            config("rtmp://a.rtmp.youtube.com/live2", "my-key").fullUrl()
        )
    }

    @Test
    fun `trailing slash and whitespace are handled`() {
        assertEquals(
            "rtmp://host/live/key",
            config("rtmp://host/live/", "key").fullUrl()
        )
        assertEquals(
            "rtmp://host/live/key",
            config(" rtmp://host/live/ ", " key ").fullUrl()
        )
    }

    @Test
    fun `empty key yields the bare url`() {
        assertEquals("rtmp://host/live", config("rtmp://host/live", "").fullUrl())
    }

    @Test
    fun `rtmp and rtmps are valid, everything else is not`() {
        assertTrue(config("rtmp://host/live").isValidUrl())
        assertTrue(config("rtmps://host:443/live").isValidUrl())
        assertTrue(config("RTMP://host/live").isValidUrl())
        assertFalse(config("http://host/live").isValidUrl())
        assertFalse(config("https://host/live").isValidUrl())
        assertFalse(config("file:///video.mp4").isValidUrl())
        assertFalse(config("").isValidUrl())
    }
}
