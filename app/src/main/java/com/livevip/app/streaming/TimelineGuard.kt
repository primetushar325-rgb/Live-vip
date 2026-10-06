package com.livevip.app.streaming

/**
 * TIMESTAMP CONTINUITY GUARD — pure logic, unit tested.
 *
 * Validates the OUTPUT timeline of the live stream:
 *
 *   0s → 30s → 60s → 90s → 120s → … (monotonically increasing, forever)
 *
 * The historical failure mode this guards against:
 *
 *   50s → 60s → 50s → 60s   (output timestamps reset at a loop boundary)
 *
 * In this architecture the encoder is never restarted across loops, so output
 * PTS is monotonic by design — this guard continuously VERIFIES that design
 * at runtime and reports violations (regression, duplicate/stalled timestamps,
 * jumps ahead of the wall clock, A/V drift) so Live Health can react with a
 * targeted recovery instead of an engine restart.
 *
 * All inputs are injected, so the class is fully deterministic in tests.
 */
class TimelineGuard(
    private val wallClock: () -> Long = System::currentTimeMillis
) {

    enum class Severity { INFO, WARNING, CRITICAL }

    enum class EventKind {
        /** Output timeline moved backward — the classic loop bug. Never acceptable. */
        REGRESSION,

        /** Output timeline did not advance while the wall clock did (frozen frames). */
        STALLED,

        /** Output timeline advanced far faster than real time. */
        JUMP,

        /** A video → video boundary happened; timeline must continue across it. */
        BOUNDARY,

        /** Audio and video output timelines drifted apart. */
        AV_DRIFT,

        /** Timeline is progressing normally. */
        PROGRESS
    }

    data class Event(
        val kind: EventKind,
        val severity: Severity,
        val atWallMs: Long,
        val outputMs: Long,
        val detail: String
    )

    enum class Status { OK, WARNING, CRITICAL }

    data class Snapshot(
        val status: Status,
        val lastOutputMs: Long,
        val regressions: Int,
        val stalls: Int,
        val jumps: Int,
        val avDriftMs: Long,
        val boundariesValidated: Int,
        val recentEvents: List<Event>
    )

    // Configuration (tolerances tuned for live video)
    private var started = false
    private var startWallMs = 0L
    private var startOutputMs = 0L
    private var lastOutputMs = 0L
    private var lastOutputWallMs = 0L
    private var regressions = 0
    private var stalls = 0
    private var jumps = 0
    private var avDriftMs = 0L
    private var boundariesValidated = 0
    private var lastBoundaryOutputMs = -1L
    private var pendingBoundary = false

    // A/V pacing inputs
    private var videoFramesSent = 0L
    private var audioFramesSent = 0L
    private var configuredFps = 30
    private var audioFrameDurationMs = 1024.0 * 1000.0 / 44100.0 // ~23ms @44.1kHz

    private val recentEvents = ArrayDeque<Event>(MAX_EVENTS)

    /** Reset at stream start. [outputMs] = current output timeline position. */
    fun start(outputMs: Long = 0L) {
        started = true
        startWallMs = wallClock()
        startOutputMs = outputMs
        lastOutputMs = outputMs
        lastOutputWallMs = startWallMs
        regressions = 0; stalls = 0; jumps = 0
        avDriftMs = 0
        boundariesValidated = 0
        lastBoundaryOutputMs = -1L
        pendingBoundary = false
        recentEvents.clear()
    }

    fun stop() {
        started = false
    }

    /** Configure pacing estimates used for A/V drift detection. */
    fun configurePacing(fps: Int, audioSampleRate: Int) {
        if (fps > 0) configuredFps = fps
        if (audioSampleRate > 0) {
            audioFrameDurationMs = 1024.0 * 1000.0 / audioSampleRate
        }
    }

    /**
     * Feed the current OUTPUT timeline position (e.g. stream duration).
     * Returns events produced by this sample (may be empty).
     */
    fun onOutputProgress(outputMs: Long): List<Event> {
        if (!started) return emptyList()
        val now = wallClock()
        val events = mutableListOf<Event>()

        // Validate any pending loop boundary: the timeline must have moved forward.
        if (pendingBoundary) {
            pendingBoundary = false
            if (outputMs >= lastBoundaryOutputMs) {
                boundariesValidated++
                events += record(
                    EventKind.BOUNDARY, Severity.INFO, now, outputMs,
                    "boundary ok: ${lastBoundaryOutputMs}ms → ${outputMs}ms (continuous)"
                )
            } else {
                events += record(
                    EventKind.REGRESSION, Severity.CRITICAL, now, outputMs,
                    "timeline went backward across boundary: ${lastBoundaryOutputMs}ms → ${outputMs}ms"
                )
            }
        }

        when {
            outputMs < lastOutputMs -> {
                regressions++
                events += record(
                    EventKind.REGRESSION, Severity.CRITICAL, now, outputMs,
                    "output timeline decreased: ${lastOutputMs}ms → ${outputMs}ms"
                )
            }

            outputMs == lastOutputMs -> {
                val silentFor = now - lastOutputWallMs
                if (silentFor >= STALL_THRESHOLD_MS) {
                    stalls++
                    events += record(
                        EventKind.STALLED, Severity.WARNING, now, outputMs,
                        "no output progress for ${silentFor}ms"
                    )
                    lastOutputWallMs = now // re-arm stall detection window
                }
            }

            else -> {
                // Progressing. Detect jumps far beyond wall-clock advance.
                val wallDelta = now - lastOutputWallMs
                val outputDelta = outputMs - lastOutputMs
                if (outputDelta > wallDelta + JUMP_TOLERANCE_MS) {
                    jumps++
                    events += record(
                        EventKind.JUMP, Severity.WARNING, now, outputMs,
                        "output advanced ${outputDelta}ms in ${wallDelta}ms wall time"
                    )
                }
                lastOutputWallMs = now
            }
        }
        lastOutputMs = outputMs
        return events
    }

    /**
     * Register a loop / playlist boundary. The next progress sample must show a
     * continued (never reset) timeline.
     */
    fun onBoundary() {
        if (!started) return
        lastBoundaryOutputMs = lastOutputMs
        pendingBoundary = true
    }

    /**
     * Feed sent-frame counters (per-destination sums) to estimate A/V sync.
     * Returns an AV_DRIFT event when the estimate exceeds tolerance.
     */
    fun onFrameCounters(videoFrames: Long, audioFrames: Long): List<Event> {
        if (!started) return emptyList()
        videoFramesSent = videoFrames
        audioFramesSent = audioFrames
        val videoMs = videoFramesSent * 1000.0 / configuredFps
        val audioMs = audioFramesSent * audioFrameDurationMs
        val drift = (videoMs - audioMs).toLong()
        val previous = avDriftMs
        avDriftMs = drift
        return if (kotlin.math.abs(drift) > AV_DRIFT_TOLERANCE_MS &&
            kotlin.math.abs(drift) > kotlin.math.abs(previous)
        ) {
            listOf(
                record(
                    EventKind.AV_DRIFT, Severity.WARNING, wallClock(), lastOutputMs,
                    "A/V drift ≈ ${drift}ms"
                )
            )
        } else {
            emptyList()
        }
    }

    fun snapshot(): Snapshot = Snapshot(
        status = when {
            !started -> Status.OK
            regressions > 0 -> Status.CRITICAL
            stalls > 0 || jumps > 0 || kotlin.math.abs(avDriftMs) > AV_DRIFT_TOLERANCE_MS -> Status.WARNING
            else -> Status.OK
        },
        lastOutputMs = lastOutputMs,
        regressions = regressions,
        stalls = stalls,
        jumps = jumps,
        avDriftMs = avDriftMs,
        boundariesValidated = boundariesValidated,
        recentEvents = recentEvents.toList()
    )

    private fun record(
        kind: EventKind,
        severity: Severity,
        atWall: Long,
        outputMs: Long,
        detail: String
    ): Event {
        val event = Event(kind, severity, atWall, outputMs, detail)
        recentEvents.addLast(event)
        while (recentEvents.size > MAX_EVENTS) recentEvents.removeFirst()
        return event
    }

    companion object {
        private const val MAX_EVENTS = 40
        private const val STALL_THRESHOLD_MS = 5_000L
        private const val JUMP_TOLERANCE_MS = 2_000L
        private const val AV_DRIFT_TOLERANCE_MS = 800L
    }
}
