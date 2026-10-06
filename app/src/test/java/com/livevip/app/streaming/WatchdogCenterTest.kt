package com.livevip.app.streaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Watchdogs: targeted recovery ONLY — a stalled component must never trigger
 * a full engine restart, and recovery budgets must be bounded.
 */
class WatchdogCenterTest {

    private class FakeClock(var now: Long = 100_000L) {
        fun advance(ms: Long) { now += ms }
    }

    private fun center(clock: FakeClock) = WatchdogCenter(wallClock = { clock.now })

    @Test
    fun `healthy stream produces no alerts`() {
        val clock = FakeClock()
        val w = center(clock)
        var frames = 0L
        var decoderTime = 10.0
        w.probes = WatchdogCenter.Probes(
            isLive = { true },
            decoderTimeSec = { decoderTime },
            videoFramesSent = { frames },
            audioFramesSent = { frames }
        )
        repeat(30) {
            clock.advance(1_000)
            frames += 30
            decoderTime += 1.0
            assertTrue(w.tick().isEmpty())
        }
    }

    @Test
    fun `decoder stall asks for video source restart only`() {
        val clock = FakeClock()
        val w = center(clock)
        w.probes = WatchdogCenter.Probes(
            isLive = { true },
            decoderTimeSec = { 42.0 }, // frozen position
            videoFramesSent = { 1_000 } // encoder still producing
        )
        val all = mutableListOf<WatchdogCenter.Alert>()
        repeat(12) { clock.advance(1_000); all += w.tick() }
        // After DECODER_STALL_MS the watchdog fires RESTART_VIDEO_SOURCE.
        assertTrue(all.any {
            it.component == WatchdogCenter.Component.DECODER &&
                it.action == WatchdogCenter.Action.RESTART_VIDEO_SOURCE
        })
        // Bounded: never more than MAX_SOURCE_RESTARTS per component.
        repeat(60) { clock.advance(1_000); w.tick() }
        assertTrue(w.sourceRestartCount() <= 10) // 5 decoder + 5 audio max
    }

    @Test
    fun `encoder stall requests a keyframe not a restart`() {
        val clock = FakeClock()
        val w = center(clock)
        var frames = 10_000L
        w.probes = WatchdogCenter.Probes(
            isLive = { true },
            decoderTimeSec = { 5.0 },
            videoFramesSent = { frames }
        )
        repeat(6) { clock.advance(1_000); w.tick() } // liveTicks > 5
        frames = 10_000 // stop advancing
        repeat(11) { clock.advance(1_000) }
        val alerts = w.tick()
        assertTrue(alerts.any {
            it.component == WatchdogCenter.Component.ENCODER &&
                it.action == WatchdogCenter.Action.REQUEST_KEYFRAME
        })
    }

    @Test
    fun `network offline is a report not a stop`() {
        val clock = FakeClock()
        val w = center(clock)
        var frames = 0L
        w.probes = WatchdogCenter.Probes(
            isLive = { true },
            videoFramesSent = { frames },
            networkOnline = { false }
        )
        clock.advance(1_000)
        frames += 30
        val alerts = w.tick()
        val net = alerts.first { it.component == WatchdogCenter.Component.NETWORK }
        assertEquals(WatchdogCenter.Action.REPORT, net.action)
    }

    @Test
    fun `memory pressure and thermal are reports`() {
        val clock = FakeClock()
        val w = center(clock)
        var frames = 0L
        w.probes = WatchdogCenter.Probes(
            isLive = { true },
            videoFramesSent = { frames },
            availableMemoryFraction = { 0.04f },
            thermalStatus = { 5 }
        )
        clock.advance(1_000)
        frames += 30
        val alerts = w.tick()
        assertEquals(WatchdogCenter.Action.REPORT, alerts.first { it.component == WatchdogCenter.Component.MEMORY }.action)
        assertEquals(WatchdogCenter.Action.REPORT, alerts.first { it.component == WatchdogCenter.Component.THERMAL }.action)
    }

    @Test
    fun `not live resets state`() {
        val clock = FakeClock()
        val w = center(clock)
        w.probes = WatchdogCenter.Probes(isLive = { false })
        assertTrue(w.tick().isEmpty())
    }
}
