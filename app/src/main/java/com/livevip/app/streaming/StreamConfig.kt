package com.livevip.app.streaming

import com.livevip.app.data.SettingsRepository
import com.livevip.app.overlay.CanvasAspect

/** Immutable snapshot of everything needed to start one live session. */
data class StreamConfig(
    val url: String,
    val key: String,
    val videoWidth: Int,
    val videoHeight: Int,
    val fps: Int,
    val videoBitrateKbps: Int,
    val keyframeIntervalSec: Int,
    val audioBitrateKbps: Int,
    val sampleRate: Int,
    val stereo: Boolean,
    val echoCanceler: Boolean,
    val noiseSuppressor: Boolean,
    val autoReconnect: Boolean,
    val maxReconnectAttempts: Int
) {
    /**
     * Combine RTMP/RTMPS url + stream key correctly.
     * e.g. rtmp://a.rtmp.youtube.com/live2 + abcd-1234
     *   -> rtmp://a.rtmp.youtube.com/live2/abcd-1234
     */
    fun fullUrl(): String {
        val base = url.trim().trimEnd('/')
        val k = key.trim()
        return if (k.isEmpty()) base else "$base/$k"
    }

    fun isValidUrl(): Boolean {
        val u = url.trim().lowercase()
        return u.startsWith("rtmp://") || u.startsWith("rtmps://")
    }

    companion object {

        /**
         * Build the session config from Saved Live settings.
         *
         * Resolution comes from output format (16:9 / 9:16) + quality preset
         * ("auto" picks 1080p, falling back to 720p only via explicit
         * [CanvasPresets.validate] at start time — never silently). Bitrate
         * follows the resolution (YouTube-recommended, CBR-safe).
         */
        fun from(settings: SettingsRepository): StreamConfig {
            val aspect = CanvasAspect.from(settings.outputAspect)
            val quality = settings.videoQuality
            val preset = if (quality == "auto") {
                CanvasPresets.optionsFor(aspect)[0]
            } else {
                CanvasPresets.presetFor(aspect, quality)
            }
            return StreamConfig(
                url = settings.streamUrl,
                key = settings.streamKey,
                videoWidth = preset.width,
                videoHeight = preset.height,
                fps = settings.videoFps,
                videoBitrateKbps = QualityProfiles.recommendedBitrateKbps(
                    minOf(preset.width, preset.height), settings.videoFps
                ),
                keyframeIntervalSec = QualityProfiles.KEYFRAME_INTERVAL_SEC,
                audioBitrateKbps = settings.audioBitrateKbps,
                sampleRate = settings.audioSampleRate,
                stereo = settings.audioStereo,
                echoCanceler = settings.echoCanceler,
                noiseSuppressor = settings.noiseSuppressor,
                autoReconnect = settings.autoReconnect,
                maxReconnectAttempts = settings.maxReconnectAttempts
            )
        }
    }
}
