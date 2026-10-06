package com.livevip.app.stream

import com.livevip.app.model.StreamState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StreamEnginePrimitivesTest {
    @Test fun stateMachineRejectsFakeLiveJump() {
        val machine = StreamStateMachine()
        assertThrows(IllegalStateException::class.java) { machine.transition(StreamState.STREAMING) }
    }

    @Test fun timestampIsStrictlyMonotonic() {
        val clock = MasterClock()
        assertEquals(100L, clock.videoPts(100))
        assertEquals(101L, clock.videoPts(100))
        assertEquals(102L, clock.audioPts(50))
        assertEquals(103L, clock.audioPts(50))
    }

    @Test fun reconnectBackoffIsBounded() {
        val backoff = ReconnectBackoff()
        assertEquals(1000L, backoff.nextDelayMs())
        assertEquals(2000L, backoff.nextDelayMs())
        assertEquals(4000L, backoff.nextDelayMs())
        assertEquals(8000L, backoff.nextDelayMs())
        assertEquals(15000L, backoff.nextDelayMs())
        assertEquals(null, backoff.nextDelayMs())
    }
}
