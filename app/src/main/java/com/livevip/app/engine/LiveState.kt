package com.livevip.app.engine

/**
 * THE REAL STATE MACHINE (spec):
 *
 *   OFFLINE → CONNECTING → CONNECTED → SENDING → STREAMING
 *                                        ↕            ↓
 *                                   RECONNECTING ← network loss
 *
 *   terminal: ERROR · STOPPED
 *
 * A socket connection does NOT mean STREAMING. STREAMING is only claimed
 * after media packets are actually being encoded and sent, sustained
 * through the ingest-verification window ([com.livevip.app.streaming.
 * IngestVerifier] — 6 s of verified video+audio flow).
 */
enum class LiveState {
    OFFLINE,
    CONNECTING,
    CONNECTED,
    SENDING,
    STREAMING,
    RECONNECTING,
    ERROR,
    STOPPED
}

/**
 * A snapshot of REAL pipeline metrics. Every value comes from the engine or
 * the protocol library — nothing is invented. Unmeasured values stay at
 * their defaults and the UI shows "N/A" for them.
 */
data class LiveSnapshot(
    val state: LiveState = LiveState.OFFLINE,
    val durationSec: Long = 0,
    /** 0 = not measured yet → UI shows N/A. */
    val bitrateKbps: Long = 0,
    val fps: Int = 0,
    val droppedFrames: Long = 0,
    val congestion: Boolean = false,
    val loopCount: Int = 0,
    val reconnects: Int = 0,
    val sentVideoFrames: Long = 0,
    val sentAudioFrames: Long = 0,
    val bytesSent: Long = 0,
    /** null = not measured yet → UI shows N/A. */
    val avSyncMs: Long? = null,
    val lastError: String? = null,
    val components: Components = Components()
)

/** Per-component pipeline health (real probes, 1 Hz while live). */
data class Components(
    val decoder: String = "—",
    val encoder: String = "—",
    val muxer: String = "—",
    val rtmp: String = "—",
    val ingest: String = "—",
    /** True when the preview surface is bound and the decoder is advancing. */
    val preview: String = "—"
)
