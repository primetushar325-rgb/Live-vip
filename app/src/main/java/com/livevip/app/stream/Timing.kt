package com.livevip.app.stream

/** Monotonic master output timeline. All encoders use this, including loop restarts. */
class MasterClock {
    private var lastVideoPts = -1L
    private var lastAudioPts = -1L
    var streamStartTimeUs: Long = 0
        private set
    var elapsedOutputTimeUs: Long = 0
        private set

    fun reset(nowUs: Long) {
        streamStartTimeUs = nowUs
        elapsedOutputTimeUs = 0
        lastVideoPts = -1L
        lastAudioPts = -1L
    }

    @Synchronized fun videoPts(candidateUs: Long): Long {
        val next = if (candidateUs <= lastVideoPts) lastVideoPts + 1 else candidateUs
        lastVideoPts = next
        elapsedOutputTimeUs = maxOf(elapsedOutputTimeUs, next)
        return next
    }

    @Synchronized fun audioPts(candidateUs: Long): Long {
        val next = if (candidateUs <= lastAudioPts) lastAudioPts + 1 else candidateUs
        lastAudioPts = next
        elapsedOutputTimeUs = maxOf(elapsedOutputTimeUs, next)
        return next
    }

    fun lastVideoPts(): Long = lastVideoPts
    fun lastAudioPts(): Long = lastAudioPts
}

class ReconnectBackoff {
    private val delays = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000)
    var attempts: Int = 0
        private set
    fun nextDelayMs(): Long? = delays.getOrNull(attempts++)
    fun reset() { attempts = 0 }
}
