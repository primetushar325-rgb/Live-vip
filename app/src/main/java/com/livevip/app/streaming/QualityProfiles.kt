package com.livevip.app.streaming

/**
 * Broadcast quality profiles.
 *
 * 360p → 2160p / 4K. UHD entries are always present in the MODEL (UHD-ready
 * architecture) but are only *selectable* when the source, hardware decoder,
 * hardware encoder and device actually support them — see [CapabilityDetector].
 * Nothing here fakes 4K: if the device cannot encode 2160p, the UI must say
 * "4K unavailable on this device".
 */
object QualityProfiles {

    data class ResolutionPreset(
        val id: String,
        val label: String,
        val width: Int,
        val height: Int
    ) {
        val isUhd: Boolean get() = height >= 2000
        val pixelCount: Long get() = width.toLong() * height.toLong()
    }

    /** 16:9 ladder up to 4K. Order matters (lowest → highest). */
    val RESOLUTIONS = listOf(
        ResolutionPreset("360p", "360p", 640, 360),
        ResolutionPreset("480p", "480p", 854, 480),
        ResolutionPreset("720p", "720p • HD", 1280, 720),
        ResolutionPreset("1080p", "1080p • Full HD", 1920, 1080),
        ResolutionPreset("1440p", "1440p • QHD", 2560, 1440),
        ResolutionPreset("2160p", "2160p • 4K UHD", 3840, 2160)
    )

    val FPS_OPTIONS = listOf(24, 25, 30, 50, 60)

    /**
     * Bitrate presets in kbps (CBR preferred for live). Values follow common
     * platform recommendations (YouTube Live / general RTMP).
     */
    val BITRATE_PRESETS = listOf(800, 1200, 1800, 2500, 3500, 4500, 6000, 9000, 14000, 20000, 40000)

    /** Default keyframe interval for live streaming: ~2 seconds. */
    const val KEYFRAME_INTERVAL_SEC = 2

    /** Recommended video bitrate for a resolution/fps combination. */
    fun recommendedBitrateKbps(height: Int, fps: Int): Int = when {
        height >= 2000 -> if (fps > 30) 40000 else 25000   // 4K
        height >= 1400 -> if (fps > 30) 14000 else 9000    // 1440p
        height >= 1080 -> if (fps > 30) 9000 else 6000     // 1080p
        height >= 720 -> if (fps > 30) 5500 else 4500      // 720p (YouTube 4–6 Mbps)
        height >= 480 -> if (fps > 30) 2500 else 1800      // 480p
        else -> if (fps > 30) 1200 else 800                // 360p
    }

    /** Sensible bitrate options for a given resolution (preset list + recommendation first). */
    fun bitrateOptionsFor(height: Int): List<Int> {
        val recommended = recommendedBitrateKbps(height, 30)
        return (listOf(recommended) + BITRATE_PRESETS).distinct().sorted()
    }

    fun presetByHeight(height: Int): ResolutionPreset? =
        RESOLUTIONS.firstOrNull { it.height == height }

    /**
     * Highest preset that does not exceed any of the given caps.
     * Pure logic — unit tested.
     */
    fun highestSupported(maxWidth: Int, maxHeight: Int): ResolutionPreset =
        RESOLUTIONS.last { it.width <= maxWidth && it.height <= maxHeight }

    /** True when the output preset is supported given source + encoder capabilities. */
    fun isSelectable(
        preset: ResolutionPreset,
        sourceWidth: Int,
        sourceHeight: Int,
        encoderMaxWidth: Int,
        encoderMaxHeight: Int
    ): Boolean {
        if (preset.width > encoderMaxWidth || preset.height > encoderMaxHeight) return false
        if (sourceWidth <= 0 || sourceHeight <= 0) return true // unknown source: allow, encoder decides
        // Never offer output above source quality (no fake upscaling).
        return preset.width <= sourceWidth && preset.height <= sourceHeight
    }
}
