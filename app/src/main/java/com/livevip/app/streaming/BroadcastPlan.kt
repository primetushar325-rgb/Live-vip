package com.livevip.app.streaming

import android.net.Uri

/**
 * Everything needed to run ONE live broadcast. Built from a Project (or from
 * legacy quick-live settings) before start. Secrets (stream keys) are injected
 * at start-time from the encrypted SecretsVault and are never persisted here.
 */

/** One stream destination (YouTube channel, Facebook page, custom RTMP…). */
data class DestinationConfig(
    val id: Long,
    val name: String,
    val platform: StreamPlatform,
    val url: String,
    /** Injected at runtime from SecretsVault — never logged, never persisted in the DB. */
    val streamKey: String,
    val enabled: Boolean = true
) {
    fun fullUrl(): String {
        val base = url.trim().trimEnd('/')
        val k = streamKey.trim()
        return if (k.isEmpty()) base else "$base/$k"
    }

    fun isValid(): Boolean {
        val u = url.trim().lowercase()
        return u.startsWith("rtmp://") || u.startsWith("rtmps://")
    }
}

/** One playlist item resolved to a playable Uri with its analyzed format. */
data class PlaylistMedia(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val durationMs: Long,
    val hasAudio: Boolean,
    val sampleRate: Int,
    val isStereo: Boolean
) {
    /**
     * Audio format must match the AAC encoder configuration — the encoder is
     * prepared ONCE and never restarted, so every item must feed the same
     * sample rate / channel count.
     */
    fun audioFormatMatches(sampleRate: Int, stereo: Boolean): Boolean =
        !hasAudio || (this.sampleRate == sampleRate && this.isStereo == stereo)
}

/** Quality block of a plan. */
data class VideoQuality(
    val width: Int,
    val height: Int,
    val fps: Int,
    val videoBitrateKbps: Int,
    val keyframeIntervalSec: Int = QualityProfiles.KEYFRAME_INTERVAL_SEC
)

/** Audio block of a plan. */
data class AudioConfig(
    val sampleRate: Int,
    val stereo: Boolean,
    val audioBitrateKbps: Int,
    val videoVolume: Float = 1f,
    val micVolume: Float = 1f,
    val micEnabled: Boolean = false,
    val echoCanceler: Boolean = true,
    val noiseSuppressor: Boolean = true
)

/** Relay server endpoint configuration (user-deployed Live VIP relay). */
data class RelayEndpoint(
    /** API base URL, e.g. https://relay.example.com */
    val apiUrl: String,
    /** Bearer token — stored encrypted, never logged. */
    val token: String
) {
    fun isValid(): Boolean = apiUrl.startsWith("https://") && token.isNotBlank()
}

/**
 * The complete broadcast plan.
 *
 * @param broadcastMode DIRECT (phone → each destination) or SMART_RELAY
 *        (phone → ONE upstream → relay → fan-out).
 * @param loopMode playlist boundary behavior — boundaries NEVER stop the stream.
 * @param overlays the project's overlay definitions (composited into the
 *        encoded stream; see com.livevip.app.overlay).
 */
data class BroadcastPlan(
    val projectId: Long,
    val projectName: String,
    val mode: com.livevip.app.streaming.LiveStreamingManager.Mode,
    val playlist: List<PlaylistMedia>,
    val loopMode: LoopMode,
    val destinations: List<DestinationConfig>,
    val broadcastMode: BroadcastMode,
    val quality: VideoQuality,
    val audio: AudioConfig,
    val relay: RelayEndpoint? = null,
    val metadata: LiveMetadata = LiveMetadata(),
    /** All overlay definitions of the project. */
    val overlays: List<com.livevip.app.overlay.OverlayConfig> = emptyList(),
    /** Initial overlay set (ids into [overlays]; empty = all enabled). */
    val initialSceneOverlayIds: List<Long> = emptyList()
) {
    val activeDestinations: List<DestinationConfig>
        get() = destinations.filter { it.enabled && it.isValid() }

    fun validate(): String? {
        if (mode == com.livevip.app.streaming.LiveStreamingManager.Mode.VIDEO &&
            playlist.isEmpty()
        ) return "Add at least one video to the playlist"
        if (mode == com.livevip.app.streaming.LiveStreamingManager.Mode.VIDEO &&
            loopMode != LoopMode.LOOP_ONE && playlist.size == 1
        ) {
            // single video + any loop mode is fine (internal loop)
        }
        val active = activeDestinations
        if (active.isEmpty()) return "Add at least one destination with a valid RTMP/RTMPS URL"
        if (broadcastMode == BroadcastMode.SMART_RELAY) {
            val relay = relay ?: return "Smart Relay requires a relay server (Settings → Relay)"
            if (!relay.isValid()) return "Relay server must use HTTPS and a valid token"
        }
        // Audio-format homogeneity: the AAC encoder is configured once.
        val first = playlist.first()
        val mismatch = playlist.firstOrNull { !it.audioFormatMatches(first.sampleRate, first.isStereo) }
        if (mismatch != null) {
            return "All playlist videos must share the same audio format — " +
                "'${mismatch.displayName}' differs from '${first.displayName}'"
        }
        return null
    }
}

/** User-facing live metadata (title/description shown where the platform allows). */
data class LiveMetadata(
    val title: String = "",
    val description: String = "",
    val category: String = "",
    val tags: List<String> = emptyList(),
    val visibility: String = ""
)
