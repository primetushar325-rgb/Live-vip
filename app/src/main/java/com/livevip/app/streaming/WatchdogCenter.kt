package com.livevip.app.streaming

/**
 * STREAM WATCHDOGS — pure state machine, unit tested.
 *
 * Independent monitors for: decoder, encoder, audio, network, memory, thermal.
 * (The RTMP watchdog is per-destination and lives in LiveEngine,
 * next to each destination's own reconnect logic.)
 *
 * Each watchdog reports a targeted recovery action. The #1 rule: recover the
 * STALLED COMPONENT ONLY — never restart the encoder, never reconnect working
 * destinations, never restart the app unless everything is terminal.
 */
class WatchdogCenter(
    private val wallClock: () -> Long = System::currentTimeMillis
) {

    enum class Component { DECODER, ENCODER, AUDIO, NETWORK, MEMORY, THERMAL }

    enum class Action {
        /** Nothing wrong. */
        NONE,
        /** Restart the video decoder / swap the file source (encoder + RTMP untouched). */
        RESTART_VIDEO_SOURCE,
        /** Rebuild the audio source (video + RTMP untouched). */
        RESTART_AUDIO_SOURCE,
        /** Ask the encoder for an immediate keyframe. */
        REQUEST_KEYFRAME,
        /** Informational only (thermal headroom, memory pressure…). */
        REPORT
    }

    data class Alert(
        val component: Component,
        val action: Action,
        val message: String,
        val atWallMs: Long
    )

    /** Probes are injected — no Android dependencies here. */
    data class Probes(
        val isLive: () -> Boolean = { false },
        /** Decoder playback position in seconds; null in camera mode / not applicable. */
        val decoderTimeSec: () -> Double? = { null },
        val videoFramesSent: () -> Long = { 0L },
        val audioFramesSent: () -> Long = { 0L },
        val networkOnline: () -> Boolean = { true },
        /** Available memory fraction 0..1 (free/total). */
        val availableMemoryFraction: () -> Float = { 1f },
        /** Thermal status: 0 = none … 6 = emergency (PowerManager statuses). */
        val thermalStatus: () -> Int = { 0 }
    )

    private var lastDecoderTime: Double? = null
    private var lastDecoderChangeMs = 0L
    private var lastVideoFrames = 0L
    private var lastVideoFramesMs = 0L
    private var lastAudioFrames = 0L
    private var lastAudioFramesMs = 0L
    private var decoderRestarts = 0
    private var audioRestarts = 0
    private var liveTicks = 0

    var probes: Probes = Probes()
        set(value) {
            field = value
            reset()
        }

    fun reset() {
        lastDecoderTime = null
        lastDecoderChangeMs = wallClock()
        lastVideoFrames = 0L
        lastVideoFramesMs = wallClock()
        lastAudioFrames = 0L
        lastAudioFramesMs = wallClock()
        decoderRestarts = 0
        audioRestarts = 0
        liveTicks = 0
    }

    /**
     * Call ~once per second while the stream is running.
     * Returns the alerts (and their targeted recovery actions) for this tick.
     */
    fun tick(): List<Alert> {
        if (!probes.isLive()) {
            reset()
            return emptyList()
        }
        liveTicks++
        val now = wallClock()
        val alerts = mutableListOf<Alert>()

        // ---------------- Decoder watchdog (VIDEO mode) ----------------
        val decoderTime = probes.decoderTimeSec()
        if (decoderTime != null) {
            val last = lastDecoderTime
            if (last == null || decoderTime != last) {
                lastDecoderTime = decoderTime
                lastDecoderChangeMs = now
            } else if (now - lastDecoderChangeMs >= DECODER_STALL_MS &&
                decoderRestarts < MAX_SOURCE_RESTARTS
            ) {
                decoderRestarts++
                lastDecoderChangeMs = now
                alerts += Alert(
                    Component.DECODER, Action.RESTART_VIDEO_SOURCE,
                    "Decoder stalled for ${(now - lastDecoderChangeMs) / 1000}s — " +
                        "restarting video source only (stream continues)", now
                )
            }
        }

        // ---------------- Encoder watchdog ----------------
        val videoFrames = probes.videoFramesSent()
        if (videoFrames > lastVideoFrames) {
            lastVideoFrames = videoFrames
            lastVideoFramesMs = now
        } else if (now - lastVideoFramesMs >= ENCODER_STALL_MS && liveTicks > 5) {
            // Encoder produced nothing for a while. If the decoder is advancing
            // this is an encoder/GL stall; ask for a keyframe and report.
            lastVideoFramesMs = now
            alerts += Alert(
                Component.ENCODER, Action.REQUEST_KEYFRAME,
                "No encoded video frames for ${ENCODER_STALL_MS / 1000}s — requesting keyframe", now
            )
        }

        // ---------------- Audio watchdog ----------------
        val audioFrames = probes.audioFramesSent()
        if (audioFrames > lastAudioFrames) {
            lastAudioFrames = audioFrames
            lastAudioFramesMs = now
        } else if (now - lastAudioFramesMs >= AUDIO_STALL_MS &&
            audioRestarts < MAX_SOURCE_RESTARTS && liveTicks > 5
        ) {
            audioRestarts++
            lastAudioFramesMs = now
            alerts += Alert(
                Component.AUDIO, Action.RESTART_AUDIO_SOURCE,
                "Audio stalled for ${AUDIO_STALL_MS / 1000}s — rebuilding audio source", now
            )
        }

        // ---------------- Network watchdog ----------------
        if (!probes.networkOnline()) {
            alerts += Alert(
                Component.NETWORK, Action.REPORT,
                "Network offline — engine stays alive, destinations reconnect automatically", now
            )
        }

        // ---------------- Memory watchdog ----------------
        val memFree = probes.availableMemoryFraction()
        if (memFree < MEMORY_CRITICAL_FRACTION) {
            alerts += Alert(
                Component.MEMORY, Action.REPORT,
                "Memory low (${(memFree * 100).toInt()}% free) — long-run risk", now
            )
        }

        // ---------------- Thermal watchdog ----------------
        val thermal = probes.thermalStatus()
        if (thermal >= THERMAL_WARNING_STATUS) {
            alerts += Alert(
                Component.THERMAL, Action.REPORT,
                when {
                    thermal >= 5 -> "Device overheating — hardware encoders may throttle. " +
                        "Consider lowering resolution/bitrate."
                    else -> "Device warming up — monitor thermal state."
                }, now
            )
        }

        return alerts
    }

    fun sourceRestartCount(): Int = decoderRestarts + audioRestarts

    companion object {
        private const val DECODER_STALL_MS = 8_000L
        private const val ENCODER_STALL_MS = 10_000L
        private const val AUDIO_STALL_MS = 6_000L
        private const val MAX_SOURCE_RESTARTS = 5
        private const val MEMORY_CRITICAL_FRACTION = 0.10f
        private const val THERMAL_WARNING_STATUS = 3 // PowerManager.THERMAL_STATUS_MODERATE
    }
}
