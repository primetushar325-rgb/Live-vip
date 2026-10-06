package com.livevip.app.streaming

import com.livevip.app.streaming.TimelineGuard.EventKind
import com.livevip.app.streaming.TimelineGuard.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Output timeline validation — the "video jumps backward" / "loop resets
 * timestamps" bug class must be detected.
 */
class TimelineGuardTest {

    private class FakeClock(var now: Long = 1_000_000L) {
        fun advance(ms: Long) { now += ms }
    }

    private fun guard(clock: FakeClock) = TimelineGuard(wallClock = { clock.now })

    @Test
    fun `normal progress produces no critical events`() {
        val clock = FakeClock()
        val g = guard(clock)
        g.start(outputMs = 0)
        var output = 0L
        repeat(50) {
            clock.advance(1000)
            output += 1000
            g.onOutputProgress(output)
        }
        val snap = g.snapshot()
        assertEquals(TimelineGuard.Status.OK, snap.status)
        assertEquals(0, snap.regressions)
        assertEquals(0, snap.stalls)
        assertEquals(0, snap.jumps)
    }

    @Test
    fun `timeline moving backward is a critical regression`() {
        val clock = FakeClock()
        val g = guard(clock)
        g.start(outputMs = 60_000)
        clock.advance(1000)
        val events = g.onOutputProgress(3_000) // jumped back!
        assertTrue(events.any { it.kind == EventKind.REGRESSION && it.severity == Severity.CRITICAL })
        assertEquals(TimelineGuard.Status.CRITICAL, g.snapshot().status)
        assertEquals(1, g.snapshot().regressions)
    }

    @Test
    fun `loop boundary with continued timeline is validated as ok`() {
        val clock = FakeClock()
        val g = guard(clock)
        g.start(outputMs = 0)
        var output = 0L
        repeat(120) { // 2 minutes of real-time playback
            clock.advance(1000)
            output += 1000
            g.onOutputProgress(output)
        }
        clock.advance(50)
        g.onBoundary()
        clock.advance(50)
        val events = g.onOutputProgress(output + 33) // next video continues the SAME timeline
        assertTrue(events.any { it.kind == EventKind.BOUNDARY && it.severity == Severity.INFO })
        assertEquals(1, g.snapshot().boundariesValidated)
        assertEquals(TimelineGuard.Status.OK, g.snapshot().status)
    }

    @Test
    fun `loop boundary that resets the timeline is a critical regression`() {
        val clock = FakeClock()
        val g = guard(clock)
        g.start(outputMs = 0)
        var output = 0L
        repeat(120) {
            clock.advance(1000)
            output += 1000
            g.onOutputProgress(output)
        }
        clock.advance(50)
        g.onBoundary()
        clock.advance(50)
        val events = g.onOutputProgress(33) // timestamps restarted from ~0 — THE bug
        assertTrue(events.any { it.kind == EventKind.REGRESSION && it.severity == Severity.CRITICAL })
        assertEquals(TimelineGuard.Status.CRITICAL, g.snapshot().status)
    }

    @Test
    fun `frozen output is reported as a stall`() {
        val clock = FakeClock()
        val g = guard(clock)
        g.start(outputMs = 0)
        g.onOutputProgress(5_000)
        clock.advance(10_000) // > 5s with no progress
        val events = g.onOutputProgress(5_000)
        assertTrue(events.any { it.kind == EventKind.STALLED })
        assertEquals(1, g.snapshot().stalls)
    }

    @Test
    fun `output advancing much faster than wall clock is a jump`() {
        val clock = FakeClock()
        val g = guard(clock)
        g.start(outputMs = 0)
        g.onOutputProgress(1_000)
        clock.advance(1_000)
        val events = g.onOutputProgress(60_000) // 59s of media in 1s
        assertTrue(events.any { it.kind == EventKind.JUMP })
        assertEquals(1, g.snapshot().jumps)
    }

    @Test
    fun `a v drift beyond tolerance is reported`() {
        val clock = FakeClock()
        val g = guard(clock)
        g.configurePacing(fps = 30, audioSampleRate = 44_100)
        g.start(outputMs = 0)
        // 1000 video frames = 33.3s; 700 audio frames x 23.2ms = 16.2s -> big drift
        val events = g.onFrameCounters(videoFrames = 1_000, audioFrames = 700)
        val drift = g.snapshot().avDriftMs
        assertTrue(kotlin.math.abs(drift) > 800)
        assertTrue(events.any { it.kind == EventKind.AV_DRIFT })
    }

    @Test
    fun `in-sync counters produce no drift event`() {
        val clock = FakeClock()
        val g = guard(clock)
        g.configurePacing(fps = 30, audioSampleRate = 48_000)
        g.start(outputMs = 0)
        val events = g.onFrameCounters(
            videoFrames = 3_000,        // 100s of video
            audioFrames = (100_000.0 / (1024.0 / 48_000.0 * 1000.0)).toLong() // ~100s
        )
        assertTrue(events.none { it.kind == EventKind.AV_DRIFT })
    }
}
