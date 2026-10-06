package com.livevip.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bitrate ladder aligned with YouTube ingest guidance:
 * 720p ≈ 3–6 Mbps, 1080p ≈ 5–10 Mbps — never blindly forced above
 * what the connection can sustain.
 */
class BitratePolicyTest {

    @Test
    fun `ladder follows youtube guidance`() {
        assertEquals(1800, BitratePolicy.recommendedKbps(854, 480, 30))
        assertEquals(4500, BitratePolicy.recommendedKbps(1280, 720, 30)) // 3–6 Mbps
        assertEquals(5500, BitratePolicy.recommendedKbps(1280, 720, 60))
        assertEquals(6000, BitratePolicy.recommendedKbps(1920, 1080, 30)) // 5–10 Mbps
        assertEquals(9000, BitratePolicy.recommendedKbps(1920, 1080, 60))
    }

    @Test
    fun `unknown resolution falls back conservatively`() {
        // Exact-width match fails → nearest known tier logic still yields a
        // sane, never-insane bitrate.
        val b = BitratePolicy.recommendedKbps(640, 360, 30)
        assertTrue("fallback in range 1200..4500: $b", b in 1200..4500)
    }

    @Test
    fun `clamp steps down to a sustainable tier when upload is weak`() {
        val (b, note) = BitratePolicy.clampToNetwork(4500, 2500)
        assertTrue("stepped below recommendation: $b", b < 4500)
        assertTrue(b >= 1200)
        assertTrue(note != null && note.lowercase().contains("network"))
    }

    @Test
    fun `clamp keeps the recommendation when upload is unknown or ample`() {
        assertEquals(4500, BitratePolicy.clampToNetwork(4500, null).first)
        assertEquals(null, BitratePolicy.clampToNetwork(4500, null).second)
        assertEquals(9000, BitratePolicy.clampToNetwork(9000, 50_000).first)
    }

    @Test
    fun `clamp never drops below the floor`() {
        val (b, _) = BitratePolicy.clampToNetwork(9000, 100)
        assertEquals(1200, b)
    }
}
