package com.livevip.app.streaming

import com.livevip.app.data.SettingsRepository

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
        fun from(settings: SettingsRepository): StreamConfig = StreamConfig(
            url = settings.streamUrl,
            key = settings.streamKey,
            videoWidth = settings.videoWidth,
            videoHeight = settings.videoHeight,
            fps = settings.videoFps,
            videoBitrateKbps = settings.videoBitrateKbps,
            keyframeIntervalSec = settings.keyframeIntervalSec,
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

/** Platform presets. Base URLs only — the user supplies the key. */
enum class StreamPlatform(val label: String, val baseUrl: String) {
    CUSTOM_RTMP("Custom RTMP", ""),
    CUSTOM_RTMPS("Custom RTMPS", ""),
    YOUTUBE("YouTube", "rtmp://a.rtmp.youtube.com/live2"),
    FACEBOOK("Facebook", "rtmps://live-api-s.facebook.com:443/rtmp"),
    TWITCH("Twitch", "rtmp://live.twitch.tv/app")
}
