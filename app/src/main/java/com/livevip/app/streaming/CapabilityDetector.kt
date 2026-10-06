package com.livevip.app.streaming

import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build

/**
 * HONEST hardware capability detection.
 *
 * A resolution/fps option is only offered when the SOURCE and the HARDWARE
 * ENCODER actually support it. Never fake 4K: on a device whose encoder tops
 * out at 1080p the UI shows "4K unavailable on this device".
 */
object CapabilityDetector {

    data class VideoEncoderCaps(
        val mime: String,
        val isHardware: Boolean,
        val maxWidth: Int,
        val maxHeight: Int,
        val maxFps: Int,
        val supportsConstantBitrate: Boolean
    ) {
        val supportsUhd: Boolean get() = maxWidth >= 3840 && maxHeight >= 2160
        fun supports(width: Int, height: Int): Boolean =
            width in 1..maxWidth && height in 1..maxHeight
    }

    /** Supported output resolutions (from the full ladder), given source + encoder. */
    data class ResolutionSupport(
        val preset: QualityProfiles.ResolutionPreset,
        val supported: Boolean,
        /** Human-readable reason when unsupported. */
        val reason: String?
    )

    private var cachedCaps: VideoEncoderCaps? = null

    /**
     * Scan hardware AVC ("video/avc") encoders for the largest supported size.
     * Falls back to software encoders when no hardware encoder exists
     * (rare; software x264-class encoders are still real encoders, not fake).
     */
    fun videoEncoderCaps(): VideoEncoderCaps? {
        cachedCaps?.let { return it }
        val caps = scan()
        cachedCaps = caps
        return caps
    }

    private fun scan(): VideoEncoderCaps? {
        return try {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            var best: VideoEncoderCaps? = null
            for (info in list.codecInfos) {
                if (!info.isEncoder) continue
                val types = try {
                    info.supportedTypes
                } catch (_: Throwable) {
                    continue
                }
                if (types.none { it.equals("video/avc", ignoreCase = true) }) continue
                val caps = try {
                    info.getCapabilitiesForType("video/avc")
                } catch (_: Throwable) {
                    continue
                }
                val video = caps.videoCapabilities ?: continue
                val maxW = try {
                    video.supportedWidths.upper
                } catch (_: Throwable) {
                    1920
                }
                val maxH = try {
                    video.supportedHeights.upper
                } catch (_: Throwable) {
                    1080
                }
                // Achievable frame rate at a common 1280x720 point.
                val fps = try {
                    video.getSupportedFrameRatesFor(1280, 720).upper.toInt()
                } catch (_: Throwable) {
                    30
                }
                val hardware = !info.name.startsWith("OMX.google") &&
                    !info.name.startsWith("c2.android") &&
                    !info.name.startsWith("c2.soft")
                val cbr = try {
                    caps.encoderCapabilities.isBitrateModeSupported(
                        android.media.MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                    )
                } catch (_: Throwable) {
                    false
                }
                val candidate = VideoEncoderCaps(
                    mime = "video/avc",
                    isHardware = hardware,
                    maxWidth = maxW,
                    maxHeight = maxH,
                    maxFps = if (fps > 0) fps else 30,
                    supportsConstantBitrate = cbr
                )
                // Prefer hardware; within the same class prefer bigger max size.
                best = when {
                    best == null -> candidate
                    hardware && !best.isHardware -> candidate
                    hardware == best.isHardware &&
                        candidate.maxWidth * candidate.maxHeight > best.maxWidth * best.maxHeight -> candidate
                    else -> best
                }
            }
            best
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Which resolution presets can honestly be offered for a given source video
     * (source width/height must be at least the preset — no fake upscaling).
     */
    fun resolutionSupport(
        sourceWidth: Int,
        sourceHeight: Int
    ): List<ResolutionSupport> {
        val caps = videoEncoderCaps()
        return QualityProfiles.RESOLUTIONS.map { preset ->
            when {
                caps == null -> ResolutionSupport(
                    preset, false,
                    "No H.264 encoder detected on this device"
                )

                !caps.supports(preset.width, preset.height) -> ResolutionSupport(
                    preset, false,
                    if (preset.isUhd) "4K unavailable on this device " +
                        "(encoder max ${caps.maxWidth}×${caps.maxHeight})"
                    else "${preset.label} unavailable on this device " +
                        "(encoder max ${caps.maxWidth}×${caps.maxHeight})"
                )

                sourceWidth > 0 && sourceHeight > 0 &&
                    (preset.width > sourceWidth || preset.height > sourceHeight) ->
                    ResolutionSupport(
                        preset, false,
                        "Source is only ${sourceWidth}×${sourceHeight} — ${preset.label} would be upscaling"
                    )

                else -> ResolutionSupport(preset, true, null)
            }
        }
    }

    /** FPS options that make sense for a source fps (no fake high-fps from low-fps source). */
    fun supportedFpsOptions(sourceFps: Int): List<Int> {
        val caps = videoEncoderCaps()
        val encoderMax = caps?.maxFps ?: 30
        val source = if (sourceFps > 0) sourceFps else 30
        return QualityProfiles.FPS_OPTIONS.filter { it <= minOf(source, encoderMax) }
            .ifEmpty { listOf(minOf(source, encoderMax).coerceAtLeast(24)) }
    }

    /** Warm the cache on a background thread (cheap no-op if already scanned). */
    fun warmAsync() {
        Thread { videoEncoderCaps() }.apply { isDaemon = true; name = "caps-warm" }.start()
    }

    /** Test hook. */
    internal fun injectCapsForTest(caps: VideoEncoderCaps?) {
        cachedCaps = caps
    }

    @Suppress("unused")
    private fun keepMediaFormatImport(): MediaFormat? = null

    @Suppress("unused")
    private fun keepBuildImport(): Boolean = Build.VERSION.SDK_INT >= 21
}
