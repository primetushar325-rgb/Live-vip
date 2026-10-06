package com.livevip.app.core

import com.livevip.app.streaming.CapabilityDetector

/**
 * OUTPUT FORMATS — exactly two (spec): LANDSCAPE 16:9 and VERTICAL 9:16.
 * The encoded video itself carries the aspect; the source is never
 * stretched and the UI is never "rotated" to fake it.
 */
enum class OutputFormat(val label: String) {
    LANDSCAPE_16_9("LANDSCAPE 16:9"),
    VERTICAL_9_16("VERTICAL 9:16");

    val isVertical: Boolean get() = this == VERTICAL_9_16

    companion object {
        fun from(name: String?): OutputFormat =
            entries.firstOrNull { it.name == name } ?: LANDSCAPE_16_9
    }
}

/**
 * Real resolutions per format (highest first) + honest AUTO selection.
 * Pure logic — unit tested.
 */
object OutputPresets {

    data class Preset(val width: Int, val height: Int) {
        /** Quality label by short side ("1080p" / "720p" / "480p"). */
        val quality: String get() = "${minOf(width, height)}p"
        fun label(): String = "${width}×$height"
    }

    /** 16:9 → 1920×1080 / 1280×720 / 854×480 · 9:16 → 1080×1920 / 720×1280 / 480×854 */
    fun presetsFor(format: OutputFormat): List<Preset> = when (format) {
        OutputFormat.VERTICAL_9_16 -> listOf(
            Preset(1080, 1920), Preset(720, 1280), Preset(480, 854)
        )
        else -> listOf(
            Preset(1920, 1080), Preset(1280, 720), Preset(854, 480)
        )
    }

    /** Preset for a format + quality label ("1080p" / "720p" / "480p"). */
    fun presetFor(format: OutputFormat, quality: String): Preset {
        val options = presetsFor(format)
        val shortSide = quality.removeSuffix("p").toIntOrNull() ?: 720
        return options.firstOrNull {
            minOf(it.width, it.height) == shortSide
        } ?: options[1]
    }

    /**
     * HONEST AUTO (safe first-test default = 720p): 1080p only when the
     * hardware encoder supports it AND the measured upload clearly sustains
     * it; never blindly force a resolution the connection cannot carry.
     *
     * @param caps        device encoder caps (null = unknown → stay at 720p)
     * @param uploadKbps  honest upload estimate (null = unknown → 720p)
     */
    fun autoPreset(
        format: OutputFormat,
        caps: CapabilityDetector.VideoEncoderCaps?,
        uploadKbps: Long?
    ): Preset {
        val options = presetsFor(format)
        val hd = options[0]   // 1080p class
        val sd = options[1]   // 720p class — the stable default
        // Unknown evidence is NOT evidence: anything unmeasured stays 720p.
        val capsOk = caps?.supports(hd.width, hd.height) == true
        val uploadOk = uploadKbps != null &&
            uploadKbps >= com.livevip.app.streaming.NetworkMath.requiredUploadKbps(6_000, 128) * 2
        return if (capsOk && uploadOk) hd else sd
    }
}
