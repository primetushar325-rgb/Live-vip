package com.livevip.app.streaming

/**
 * Real-time state of the streaming engine.
 *
 * PUBLISHING (Part 4): the RTMP/RTMPS socket is connected and the handshake
 * succeeded, but media flow is NOT yet verified. The engine only enters LIVE
 * after video+audio packets have actually been sent and accepted for a
 * sustained verification window — "socket connected" is never treated as
 * "platform LIVE" (YouTube false-positive fix).
 */
enum class StreamState {
    OFFLINE,
    CONNECTING,
    PUBLISHING,
    LIVE,
    RECONNECTING,
    ERROR
}

/**
 * Live statistics surfaced to the UI while streaming.
 */
data class StreamStats(
    val bitrateKbps: Long = 0,
    val durationSec: Long = 0,
    val droppedFrames: Long = 0,
    val congestion: Boolean = false,
    val fps: Int = 0,
    val loopCount: Int = 0,
    val reconnects: Int = 0,
    /** True only after sustained verified media flow (ingest verification). */
    val mediaVerified: Boolean = false,
    /** Measured A/V timeline drift (video coverage - audio coverage, ms). */
    val avSyncMs: Long = 0,
    /** Actual H.264 frames handed to the RTMP sender (library counter). */
    val sentVideoFrames: Long = 0,
    /** Actual AAC frames handed to the RTMP sender (library counter). */
    val sentAudioFrames: Long = 0,
    /** Bytes sent, integrated from the library-measured socket bitrate. */
    val bytesSent: Long = 0,
    /** Last honest error (sanitized — no urls, no keys). */
    val lastError: String? = null
)
