package com.livevip.app.stream

import com.livevip.app.model.StreamState

/** Explicit transition table; UI never infers LIVE from a boolean. */
class StreamStateMachine(initial: StreamState = StreamState.IDLE) {
    var current: StreamState = initial
        private set

    private val allowed = mapOf(
        StreamState.IDLE to setOf(StreamState.PREPARING),
        StreamState.PREPARING to setOf(StreamState.VIDEO_READY, StreamState.ERROR, StreamState.STOPPING),
        StreamState.VIDEO_READY to setOf(StreamState.AUDIO_READY, StreamState.ENCODER_READY, StreamState.ERROR, StreamState.STOPPING),
        StreamState.AUDIO_READY to setOf(StreamState.ENCODER_READY, StreamState.ERROR, StreamState.STOPPING),
        StreamState.ENCODER_READY to setOf(StreamState.CONNECTING, StreamState.ERROR, StreamState.STOPPING),
        StreamState.CONNECTING to setOf(StreamState.CONNECTED, StreamState.NETWORK_LOST, StreamState.ERROR, StreamState.STOPPING),
        StreamState.CONNECTED to setOf(StreamState.SENDING, StreamState.NETWORK_LOST, StreamState.ERROR, StreamState.STOPPING),
        StreamState.SENDING to setOf(StreamState.STREAMING, StreamState.NETWORK_LOST, StreamState.ERROR, StreamState.STOPPING),
        StreamState.STREAMING to setOf(StreamState.NETWORK_LOST, StreamState.STOPPING, StreamState.ERROR),
        StreamState.NETWORK_LOST to setOf(StreamState.RECONNECTING, StreamState.STOPPING, StreamState.ERROR),
        StreamState.RECONNECTING to setOf(StreamState.CONNECTING, StreamState.ERROR, StreamState.STOPPING),
        StreamState.STOPPING to setOf(StreamState.STOPPED, StreamState.ERROR),
        StreamState.STOPPED to setOf(StreamState.PREPARING, StreamState.IDLE),
        StreamState.ERROR to setOf(StreamState.STOPPING, StreamState.PREPARING, StreamState.IDLE)
    )

    fun transition(next: StreamState) {
        check(next in (allowed[current] ?: emptySet())) { "Illegal stream transition $current -> $next" }
        current = next
    }

    fun canTransition(next: StreamState): Boolean = next in (allowed[current] ?: emptySet())
}
