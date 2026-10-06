package com.livevip.app.core

import com.livevip.app.streaming.CapabilityDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Only two live formats exist (16:9 / 9:16). Every preset must be
 * H.264-encodable (even dimensions) and keep its aspect honest.
 */
class OutputFormatTest {

    @Test
    fun `exactly two formats exist`() {
        assertEquals(2, OutputFormat.entries.size)
    }

    @Test
    fun `landscape presets are 16by9 with even dimensions`() {
        val presets = OutputPresets.presetsFor(OutputFormat.LANDSCAPE_16_9)
        assertEquals(listOf("1080p", "720p", "480p"), presets.map { it.quality })
        presets.forEach { p ->
            assertEquals(16f / 9f, p.width.toFloat() / p.height, 0.001f)
            assertEquals(0, p.width % 2)
            assertEquals(0, p.height % 2)
        }
        assertEquals(1920, presets[0].width)
        assertEquals(854, presets[2].width)
    }

    @Test
    fun `vertical presets are 9by16 with even dimensions`() {
        val presets = OutputPresets.presetsFor(OutputFormat.VERTICAL_9_16)
        assertEquals(listOf("1080p", "720p", "480p"), presets.map { it.quality })
        presets.forEach { p ->
            assertEquals(9f / 16f, p.width.toFloat() / p.height, 0.001f)
            assertEquals(0, p.width % 2)
            assertEquals(0, p.height % 2)
        }
        assertEquals(1080, presets[0].width)
        assertEquals(1920, presets[0].height)
        assertEquals(480, presets[2].width)
    }

    @Test
    fun `presetFor maps every quality in both formats`() {
        assertEquals(1280, OutputPresets.presetFor(OutputFormat.LANDSCAPE_16_9, "720p").width)
        assertEquals(720, OutputPresets.presetFor(OutputFormat.LANDSCAPE_16_9, "720p").height)
        assertEquals(720, OutputPresets.presetFor(OutputFormat.VERTICAL_9_16, "720p").width)
        assertEquals(1280, OutputPresets.presetFor(OutputFormat.VERTICAL_9_16, "720p").height)
        assertEquals(854, OutputPresets.presetFor(OutputFormat.LANDSCAPE_16_9, "480p").width)
        assertEquals(480, OutputPresets.presetFor(OutputFormat.VERTICAL_9_16, "480p").height)
        // Unknown quality falls back to the 720p default.
        assertEquals(1280, OutputPresets.presetFor(OutputFormat.LANDSCAPE_16_9, "nope").width)
    }

    @Test
    fun `autoPreset defaults to 720p without evidence for 1080p`() {
        // No caps, no upload estimate → honest 720p default.
        val p = OutputPresets.autoPreset(OutputFormat.LANDSCAPE_16_9, null, null)
        assertEquals("720p", p.quality)
        assertEquals(1280, p.width)
        // Upload unknown → still 720p (unknown is NOT evidence for 1080p).
        assertEquals(
            "720p",
            OutputPresets.autoPreset(
                OutputFormat.LANDSCAPE_16_9, capsFor(1920, 1080), null
            ).quality
        )
    }

    @Test
    fun `autoPreset only picks 1080p when caps and upload clearly sustain it`() {
        val caps = capsFor(1920, 1080)
        // Enough upload headroom (required for 6000k video ≈ 9.6 Mbps; ×2 margin).
        val p = OutputPresets.autoPreset(OutputFormat.LANDSCAPE_16_9, caps, 16_000)
        assertEquals("1080p", p.quality)
        // Encoder can't sustain 1080p → 720p.
        val limited = capsFor(1280, 720)
        assertEquals(
            "720p",
            OutputPresets.autoPreset(OutputFormat.LANDSCAPE_16_9, limited, 16_000).quality
        )
        // Upload can't sustain 1080p → 720p.
        assertEquals(
            "720p",
            OutputPresets.autoPreset(OutputFormat.LANDSCAPE_16_9, caps, 6_000).quality
        )
    }

    private fun capsFor(maxW: Int, maxH: Int) = CapabilityDetector.VideoEncoderCaps(
        mime = "video/avc",
        isHardware = true,
        maxWidth = maxW,
        maxHeight = maxH,
        maxFps = 60,
        supportsConstantBitrate = true
    )

    @Test
    fun `format parsing is total`() {
        assertEquals(OutputFormat.VERTICAL_9_16, OutputFormat.from("VERTICAL_9_16"))
        assertEquals(OutputFormat.LANDSCAPE_16_9, OutputFormat.from("LANDSCAPE_16_9"))
        assertEquals(OutputFormat.LANDSCAPE_16_9, OutputFormat.from("garbage"))
        assertTrue(OutputFormat.from("VERTICAL_9_16").isVertical)
        assertTrue(!OutputFormat.from("LANDSCAPE_16_9").isVertical)
    }
}
