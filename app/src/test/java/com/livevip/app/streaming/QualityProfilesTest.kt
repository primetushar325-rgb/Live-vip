package com.livevip.app.streaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Quality ladder honesty: no fake 4K, no upscaling, ~2s keyframes, CBR
 * presets. Loop mode labels stable (persisted by name).
 */
class QualityProfilesTest {

    @Test
    fun `resolution ladder goes up to 4k uhd`() {
        assertEquals(6, QualityProfiles.RESOLUTIONS.size)
        assertEquals(2160, QualityProfiles.RESOLUTIONS.last().height)
        assertTrue(QualityProfiles.RESOLUTIONS.last().isUhd)
        assertFalse(QualityProfiles.RESOLUTIONS.first().isUhd)
    }

    @Test
    fun `keyframe interval is about two seconds`() {
        assertEquals(2, QualityProfiles.KEYFRAME_INTERVAL_SEC)
    }

    @Test
    fun `4k is not selectable when the encoder maxes at 1080p`() {
        val uhd = QualityProfiles.presetByHeight(2160)!!
        assertFalse(
            QualityProfiles.isSelectable(uhd, 3840, 2160, 1920, 1080)
        )
        val fhd = QualityProfiles.presetByHeight(1080)!!
        assertTrue(
            QualityProfiles.isSelectable(fhd, 1920, 1080, 1920, 1080)
        )
    }

    @Test
    fun `no preset above source quality (no fake upscaling)`() {
        val fhd = QualityProfiles.presetByHeight(1080)!!
        assertFalse(QualityProfiles.isSelectable(fhd, 1280, 720, 3840, 2160))
        val hd = QualityProfiles.presetByHeight(720)!!
        assertTrue(QualityProfiles.isSelectable(hd, 1280, 720, 3840, 2160))
    }

    @Test
    fun `unknown source defers to the encoder cap`() {
        val uhd = QualityProfiles.presetByHeight(2160)!!
        assertTrue(QualityProfiles.isSelectable(uhd, 0, 0, 3840, 2160))
    }

    @Test
    fun `highest supported respects caps`() {
        assertEquals(1080, QualityProfiles.highestSupported(1920, 1080).height)
        assertEquals(2160, QualityProfiles.highestSupported(4096, 2160).height)
        assertEquals(360, QualityProfiles.highestSupported(640, 360).height)
    }

    @Test
    fun `recommended bitrate grows with resolution and fps`() {
        val hd30 = QualityProfiles.recommendedBitrateKbps(720, 30)
        val hd60 = QualityProfiles.recommendedBitrateKbps(720, 60)
        val fhd30 = QualityProfiles.recommendedBitrateKbps(1080, 30)
        val uhd30 = QualityProfiles.recommendedBitrateKbps(2160, 30)
        assertTrue(hd30 < hd60)
        assertTrue(hd60 < fhd30)
        assertTrue(fhd30 < uhd30)
    }

    @Test
    fun `bitrate options are sorted and include the recommendation`() {
        val options = QualityProfiles.bitrateOptionsFor(720)
        assertEquals(options, options.sorted())
        assertTrue(QualityProfiles.recommendedBitrateKbps(720, 30) in options)
    }

    @Test
    fun `loop modes persist by name`() {
        assertEquals(LoopMode.LOOP_ONE, LoopMode.from("LOOP_ONE"))
        assertEquals(LoopMode.SHUFFLE, LoopMode.from("Shuffle"))
        assertEquals(LoopMode.LOOP_ALL, LoopMode.from("garbage")) // safe default
        assertEquals(LoopMode.LOOP_ALL, LoopMode.from(null))
    }

    @Test
    fun `broadcast mode labels are user readable`() {
        assertEquals("Direct RTMP", BroadcastMode.DIRECT.label)
        assertEquals("Smart Relay", BroadcastMode.SMART_RELAY.label)
    }
}
