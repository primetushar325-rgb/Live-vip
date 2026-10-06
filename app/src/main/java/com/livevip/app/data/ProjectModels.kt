package com.livevip.app.data

import com.livevip.app.overlay.OverlayConfig
import com.livevip.app.overlay.SceneConfig
import com.livevip.app.streaming.BroadcastMode
import com.livevip.app.streaming.LoopMode
import com.livevip.app.streaming.StreamPlatform

/**
 * PROJECT SYSTEM — domain models.
 *
 * A Project stores the complete live configuration. STREAM KEYS ARE NEVER
 * STORED HERE — they live only in the Keystore-backed [SecretsVault]
 * (keyed by destination id) and are injected at broadcast start.
 */

data class Destination(
    val id: Long = 0,
    val projectId: Long = 0,
    val name: String,
    val platform: StreamPlatform = StreamPlatform.CUSTOM_RTMP,
    val url: String,
    val enabled: Boolean = true,
    val title: String = "",
    val description: String = "",
    val sortOrder: Int = 0
)

data class PlaylistItem(
    val id: Long = 0,
    val projectId: Long = 0,
    /** References the video library item id. */
    val videoId: Long,
    val sortOrder: Int = 0
)

data class Project(
    val id: Long = 0,
    val name: String,
    /** VIDEO or CAMERA source mode. */
    val mode: String = "VIDEO",
    val loopMode: LoopMode = LoopMode.LOOP_ALL,
    val broadcastMode: BroadcastMode = BroadcastMode.DIRECT,
    val title: String = "",
    val description: String = "",
    val width: Int = 1280,
    val height: Int = 720,
    val fps: Int = 30,
    val videoBitrateKbps: Int = 2500,
    val audioBitrateKbps: Int = 128,
    val sampleRate: Int = 44100,
    val stereo: Boolean = true,
    val videoVolume: Float = 1f,
    val micVolume: Float = 0.6f,
    val micEnabledByDefault: Boolean = false,
    val echoCanceler: Boolean = true,
    val noiseSuppressor: Boolean = true,
    val overlays: List<OverlayConfig> = emptyList(),
    val scenes: List<SceneConfig> = emptyList(),
    val lastStreamedAt: Long = 0,
    val lastDurationSec: Long = 0,
    val lastLoopCount: Int = 0,
    val totalStreamCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
) {
    val isVideoMode: Boolean get() = mode == "VIDEO"
}

/** A completed or ongoing broadcast record (PROJECT HISTORY). */
data class StreamSession(
    val id: Long = 0,
    val projectId: Long = 0,
    val projectName: String = "",
    val startedAt: Long = System.currentTimeMillis(),
    val endedAt: Long = 0,
    val durationSec: Long = 0,
    val loopCount: Int = 0,
    val destinationsCount: Int = 1,
    val broadcastMode: BroadcastMode = BroadcastMode.DIRECT,
    val status: String = "LIVE", // LIVE | COMPLETED | ERROR
    val notes: String = ""
)

/** Full project aggregate used by the editor and broadcast start. */
data class ProjectBundle(
    val project: Project,
    val destinations: List<Destination>,
    val playlist: List<PlaylistItem>
)
