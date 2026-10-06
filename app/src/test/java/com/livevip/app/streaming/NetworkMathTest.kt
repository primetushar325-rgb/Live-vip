package com.livevip.app.streaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pre-live network math: required upload = video + audio + overhead + margin.
 * One stream, one destination — the phone uploads exactly once.
 */
class NetworkMathTest {

    @Test
    fun `required upload includes overhead and safety margin`() {
        // 6000 kbps video + 128 kbps audio
        val required = NetworkMath.requiredUploadKbps(6_000, 128)
        // (6128 * 1.10) * 1.25 ≈ 8426
        assertTrue("got $required", required in 8_400..8_500)
    }

    @Test
    fun `required upload scales with bitrate`() {
        val low = NetworkMath.requiredUploadKbps(1_500, 128)
        val high = NetworkMath.requiredUploadKbps(6_000, 128)
        assertTrue(low < high)
    }

    @Test
    fun `excellent headroom status`() {
        val a = NetworkMath.assess(2_500, 128, 20_000)
        assertEquals(NetworkMath.Status.EXCELLENT, a.status)
    }

    @Test
    fun `tight upload is reported honestly`() {
        val required = NetworkMath.requiredUploadKbps(2_500, 128)
        val a = NetworkMath.assess(2_500, 128, required + 10L)
        assertEquals(NetworkMath.Status.TIGHT, a.status)
    }

    @Test
    fun `insufficient upload is reported honestly`() {
        val a = NetworkMath.assess(6_000, 128, 3_000)
        assertEquals(NetworkMath.Status.INSUFFICIENT, a.status)
        assertTrue(a.summary.contains("lower", ignoreCase = true))
    }

    @Test
    fun `unknown upload never invents a number`() {
        val a = NetworkMath.assess(2_500, 128, null)
        assertEquals(NetworkMath.Status.UNKNOWN, a.status)
        assertEquals(null, a.availableUploadKbps)
        assertEquals(null, a.marginKbps)
    }
}
