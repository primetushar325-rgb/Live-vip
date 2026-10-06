package com.livevip.app.streaming

import com.livevip.app.overlay.CanvasAspect

/**
 * CANVAS PRESETS + OUTPUT VALIDATION (pure logic — unit tested).
 *
 * The user picks an output format; the presets list the real resolutions the
 * encoder will run at. Validation is HONEST: encoder caps, pixel-count
 * performance class and even-dimension rules are all checked; when a choice
 * is not supported the code says so and SUGGESTS the nearest working option
 * ("1080×1920 at 60 FPS is not supported — use 720×1280 at 30 FPS").
 */
object CanvasPresets {

    data class CanvasPreset(val label: String, val width: Int, val height: Int) {
        fun pixelCount(): Long = width.toLong() * height.toLong()
    }

    /** Real resolutions per aspect (highest first). */
    fun optionsFor(aspect: CanvasAspect): List<CanvasPreset> = when (aspect) {
        CanvasAspect.LANDSCAPE_16_9 -> listOf(
            CanvasPreset("1920×1080 • Full HD", 1920, 1080),
            CanvasPreset("1280×720 • HD", 1280, 720),
            CanvasPreset("854×480 • SD", 854, 480)
        )
        CanvasAspect.PORTRAIT_9_16 -> listOf(
            CanvasPreset("1080×1920 • Shorts HD", 1080, 1920),
            CanvasPreset("720×1280 • Shorts", 720, 1280),
            CanvasPreset("480×854 • Shorts SD", 480, 854)
        )
        CanvasAspect.SQUARE_1_1 -> listOf(
            CanvasPreset("1080×1080 • Square HD", 1080, 1080),
            CanvasPreset("720×720 • Square", 720, 720),
            CanvasPreset("480×480 • Square SD", 480, 480)
        )
        CanvasAspect.PORTRAIT_4_5 -> listOf(
            CanvasPreset("1080×1350 • 4:5 HD", 1080, 1350),
            CanvasPreset("864×1080 • 4:5", 864, 1080),
            CanvasPreset("540×675 • 4:5 SD", 540, 675)
        )
        CanvasAspect.CUSTOM -> listOf(
            CanvasPreset("1920×1080", 1920, 1080),
            CanvasPreset("1280×720", 1280, 720),
            CanvasPreset("1080×1920", 1080, 1920),
            CanvasPreset("720×1280", 720, 1280),
            CanvasPreset("1080×1080", 1080, 1080)
        )
    }

    /** All presets across aspects (for "suggest a working alternative"). */
    fun allPresets(): List<CanvasPreset> =
        CanvasAspect.entries.flatMap { optionsFor(it) }.distinctBy { "${it.width}x${it.height}" }

    data class ValidationResult(
        val supported: Boolean,
        /** Human explanation when unsupported, includes a suggestion. */
        val reason: String?,
        /** Nearest working preset+fps suggestion, when unsupported. */
        val suggestion: CanvasPreset?
    )

    /**
     * Validate a canvas resolution + fps against:
     *  - even dimensions (H.264 macroblocks),
     *  - hardware encoder caps (max size),
     *  - performance class (pixel count × fps — no fake 4K60 on mid devices),
     *  - the bitrate being in a sane range for the resolution.
     */
    fun validate(
        width: Int,
        height: Int,
        fps: Int,
        videoBitrateKbps: Int,
        caps: CapabilityDetector.VideoEncoderCaps?
    ): ValidationResult {
        if (width % 2 != 0 || height % 2 != 0) {
            return ValidationResult(
                false, "Resolution must be even (got ${width}×$height) — H.264 requires it.",
                nearestEven(width, height)
            )
        }
        if (width < 128 || height < 128) {
            return ValidationResult(
                false, "Resolution too small: ${width}×$height. Minimum is 128×128.",
                CanvasPreset("640×360", 640, 360)
            )
        }
        if (caps == null) {
            return ValidationResult(
                false, "No H.264 encoder detected on this device.", null
            )
        }
        if (!caps.supports(width, height)) {
            val alt = fallbackPreset(width, height, fps, caps)
            return ValidationResult(
                false,
                "${width}×$height is not supported by this device's encoder " +
                    "(max ${caps.maxWidth}×${caps.maxHeight})" +
                    (alt?.let { " — use ${it.width}×${it.height} at $fps FPS" } ?: ""),
                alt
            )
        }
        // Performance honesty: heavy combos need a capable encoder class.
        val pixels = width.toLong() * height.toLong()
        val heavy = pixels >= 1920L * 1080L
        if (heavy && fps > 30 && !caps.supportsUhd && caps.maxFps < fps) {
            val alt = fallbackPreset(width, height, 30, caps)
            return ValidationResult(
                false,
                "${width}×$height at $fps FPS is not supported by this device" +
                    (alt?.let { " — use ${it.width}×${it.height} at 30 FPS" } ?: ""),
                alt
            )
        }
        if (heavy && fps > 60) {
            val alt = fallbackPreset(width, height, 30, caps)
            return ValidationResult(
                false,
                "${width}×$height at $fps FPS is too heavy for stable live encoding" +
                    (alt?.let { " — use ${it.width}×${it.height} at 30 FPS" } ?: ""),
                alt
            )
        }
        val minBitrate = QualityProfiles.recommendedBitrateKbps(
            height.coerceAtLeast(width), 30
        ) / 3
        if (videoBitrateKbps < minBitrate / 2) {
            return ValidationResult(
                false,
                "Bitrate ${videoBitrateKbps} kbps is too low for ${width}×$height — " +
                    "use at least ${minBitrate / 2} kbps.",
                null
            )
        }
        return ValidationResult(true, null, null)
    }

    /**
     * Platform support hints (static, honest — full live platform capability
     * queries need official APIs, Part 2+/OAuth scope):
     * YouTube/Facebook/Twitch accept all presets here for RTMP ingest.
     */
    fun platformSupported(platform: StreamPlatform, width: Int, height: Int): Boolean =
        when (platform) {
            // All major platforms accept 16:9 and 9:16 RTMP at these sizes.
            StreamPlatform.YOUTUBE, StreamPlatform.FACEBOOK, StreamPlatform.TWITCH,
            StreamPlatform.CUSTOM_RTMP, StreamPlatform.CUSTOM_RTMPS -> true
        }

    private fun nearestEven(width: Int, height: Int): CanvasPreset? =
        CanvasPresets.allPresets().firstOrNull {
            it.width >= width && it.height >= height
        }

    /** Largest preset ≤ the request that the encoder supports, preferring fps first. */
    private fun fallbackPreset(
        width: Int, height: Int, fps: Int, caps: CapabilityDetector.VideoEncoderCaps?
    ): CanvasPreset? {
        val target = width.toLong() * height.toLong()
        return allPresets()
            .filter { caps == null || caps.supports(it.width, it.height) }
            .filter { it.pixelCount() <= target }
            .maxByOrNull { it.pixelCount() }
            ?: allPresets().filter { caps == null || caps.supports(it.width, it.height) }
                .minByOrNull { it.pixelCount() }
    }

    /** Default canvas for a dedicated SHORTS LIVE project (9:16). */
    fun shortsCanvas(caps: CapabilityDetector.VideoEncoderCaps?): com.livevip.app.overlay.CanvasConfig {
        val hd = optionsFor(CanvasAspect.PORTRAIT_9_16)[0]
        val fallback = optionsFor(CanvasAspect.PORTRAIT_9_16)[1]
        val chosen = if (caps == null || caps.supports(hd.width, hd.height)) hd else fallback
        return com.livevip.app.overlay.CanvasConfig(
            aspect = CanvasAspect.PORTRAIT_9_16,
            width = chosen.width, height = chosen.height
        )
    }

    /** Default canvas for normal landscape live (16:9). */
    fun landscapeCanvas(caps: CapabilityDetector.VideoEncoderCaps?): com.livevip.app.overlay.CanvasConfig {
        val hd = optionsFor(CanvasAspect.LANDSCAPE_16_9)[0]
        val fallback = optionsFor(CanvasAspect.LANDSCAPE_16_9)[1]
        val chosen = if (caps == null || caps.supports(hd.width, hd.height)) hd else fallback
        return com.livevip.app.overlay.CanvasConfig(
            aspect = CanvasAspect.LANDSCAPE_16_9,
            width = chosen.width, height = chosen.height
        )
    }
}
