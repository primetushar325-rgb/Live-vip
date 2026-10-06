package com.livevip.app.streaming

/** Real-time state of the streaming engine. */
enum class StreamState {
    OFFLINE,
    CONNECTING,
    LIVE,
    RECONNECTING,
    ERROR
}

/** Live statistics surfaced to the UI while streaming. */
data class StreamStats(
    val bitrateKbps: Long = 0,
    val durationSec: Long = 0,
    val droppedFrames: Long = 0,
    val congestion: Boolean = false,
    val fps: Int = 0,
    val loopCount: Int = 0,
    val reconnects: Int = 0
)
