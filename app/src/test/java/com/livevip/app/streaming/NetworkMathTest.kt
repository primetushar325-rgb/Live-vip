package com.livevip.app.streaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pre-live network math: required upload = video + audio + overhead + margin.
 * Smart Relay keeps the phone at ONE upstream regardless of destination count.
 */
class NetworkMathTest {

    @Test
    fun `required per stream includes overhead and safety margin`() {
        // 6000 kbps video + 128 kbps audio
        val required = NetworkMath.requiredPerStreamKbps(6_000, 128)
        // (6128 * 1.10) * 1.25 ≈ 8426
        assertTrue("got $required", required in 8_400..8_500)
    }

    @Test
    fun `direct mode multiplies phone upload by destination count`() {
        val one = NetworkMath.requiredPhoneUploadKbps(2_500, 128, 1, BroadcastMode.DIRECT)
        val three = NetworkMath.requiredPhoneUploadKbps(2_500, 128, 3, BroadcastMode.DIRECT)
        assertEquals(one * 3, three)
    }

    @Test
    fun `relay mode keeps phone upload at one stream regardless of destinations`() {
        val one = NetworkMath.requiredPhoneUploadKbps(4_000, 128, 1, BroadcastMode.SMART_RELAY)
        val ten = NetworkMath.requiredPhoneUploadKbps(4_000, 128, 10, BroadcastMode.SMART_RELAY)
        assertEquals(one, ten)
    }

    @Test
    fun `excellent headroom status`() {
        val a = NetworkMath.assess(2_500, 128, 1, BroadcastMode.DIRECT, 20_000)
        assertEquals(NetworkMath.Status.EXCELLENT, a.status)
        assertEquals(BroadcastMode.DIRECT, a.recommendedMode)
    }

    @Test
    fun `tight upload for multiple destinations recommends relay`() {
        val perStream = NetworkMath.requiredPerStreamKbps(2_500, 128)
        // Barely enough for 3x direct — relay should be recommended.
        val a = NetworkMath.assess(2_500, 128, 3, BroadcastMode.DIRECT, perStream * 3 + 500)
        assertEquals(BroadcastMode.SMART_RELAY, a.recommendedMode)
        assertEquals(NetworkMath.Status.TIGHT, a.status)
    }

    @Test
    fun `insufficient upload is reported honestly`() {
        val a = NetworkMath.assess(6_000, 128, 1, BroadcastMode.DIRECT, 3_000)
        assertEquals(NetworkMath.Status.INSUFFICIENT, a.status)
        assertTrue(a.summary.contains("lower", ignoreCase = true))
    }

    @Test
    fun `unknown upload never invents a number`() {
        val a = NetworkMath.assess(2_500, 128, 2, BroadcastMode.DIRECT, null)
        assertEquals(NetworkMath.Status.UNKNOWN, a.status)
        assertEquals(null, a.marginKbps)
        assertEquals(BroadcastMode.SMART_RELAY, a.recommendedMode)
    }

    @Test
    fun `single destination always direct`() {
        val a = NetworkMath.assess(2_500, 128, 1, BroadcastMode.SMART_RELAY, 5_000)
        assertEquals(BroadcastMode.DIRECT, a.recommendedMode)
    }
}
