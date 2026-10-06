package com.livevip.app.streaming

/**
 * How the playlist behaves at video boundaries.
 *
 * IMPORTANT: in every mode, a video boundary NEVER stops the live stream.
 */
enum class LoopMode(val label: String) {
    /** Current video repeats forever (handled inside the decoder — zero-cost loop). */
    LOOP_ONE("Loop One"),

    /** Playlist plays in order, then repeats from the start, forever. */
    LOOP_ALL("Loop All"),

    /** Playlist plays in random order forever (no immediate repeats). */
    SHUFFLE("Shuffle"),

    /** Plays the playlist once in order, then ends the live broadcast (explicit user choice). */
    PLAY_ONCE("Play Once");

    companion object {
        fun from(name: String?): LoopMode =
            entries.firstOrNull { it.name == name || it.label == name } ?: LOOP_ALL
    }
}

/**
 * How the encoded stream reaches the destinations.
 *
 * DIRECT      — the phone uploads to every destination itself (1 destination = classic RTMP).
 * SMART_RELAY — the phone uploads ONE stream to the Live VIP relay server which fans out,
 *               keeping phone upload at 1× regardless of destination count.
 */
enum class BroadcastMode(val label: String) {
    DIRECT("Direct RTMP"),
    SMART_RELAY("Smart Relay");
}
