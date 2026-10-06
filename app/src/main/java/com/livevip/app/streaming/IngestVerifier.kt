package com.livevip.app.streaming

/**
 * INGEST VERIFICATION (Part 4) — pure decision logic, unit tested.
 *
 * A connected RTMP/RTMPS socket is NOT proof that the platform is receiving
 * the stream. LIVE may only be claimed after:
 *  1. the connection + handshake succeeded,
 *  2. video packets are flowing,
 *  3. audio packets are flowing (when audio is enabled),
 *  4. that flow was sustained for a verification window,
 *  5. no fatal condition appeared.
 *
 * Until then the honest state is PUBLISHING ("STREAMING TO SERVER").
 */
object IngestVerifier {

    /** One observation of the sent-packet counters. */
    data class Sample(
        val elapsedSec: Float,
        val videoFrames: Long,
        val audioFrames: Long,
        val audioEnabled: Boolean
    )

    sealed interface Result {
        /** Keep verifying — media flow not yet conclusive. */
        data object Pending : Result
        /** Sustained verified media flow → the engine may go LIVE. */
        data object Verified : Result
        /** Verification window exhausted without valid media → honest error. */
        data class Failed(val reason: String) : Result
    }

    const val SUSTAIN_SEC = 6f
    const val TIMEOUT_SEC = 25f
    const val NO_MEDIA_TIMEOUT_SEC = 20f

    /**
     * @param history ordered samples (at least the last SUSTAIN_SEC window).
     */
    fun evaluate(history: List<Sample>): Result {
        if (history.isEmpty()) return Result.Pending
        val latest = history.last()
        if (latest.elapsedSec >= NO_MEDIA_TIMEOUT_SEC && latest.videoFrames <= 0L) {
            return Result.Failed(
                "Connected to RTMPS, but video packets are not being accepted. " +
                    "Check the stream key and that the platform is expecting this exact stream."
            )
        }
        if (latest.elapsedSec >= NO_MEDIA_TIMEOUT_SEC && latest.audioEnabled && latest.audioFrames <= 0L) {
            return Result.Failed(
                "Publishing started, but no valid audio was detected by the server."
            )
        }
        if (latest.elapsedSec >= TIMEOUT_SEC) {
            // Media exists but never stayed consistent through a window.
            return Result.Failed(
                "Ingest could not be verified — media flow was inconsistent."
            )
        }

        // The sustained-flow window: all samples in the last SUSTAIN_SEC must
        // show forward progress on every enabled track.
        val window = history.filter { latest.elapsedSec - it.elapsedSec <= SUSTAIN_SEC }
        val first = window.firstOrNull() ?: return Result.Pending
        val videoDelta = latest.videoFrames - first.videoFrames
        val audioDelta = latest.audioFrames - first.audioFrames
        val windowSpan = latest.elapsedSec - first.elapsedSec
        val sustained = windowSpan >= SUSTAIN_SEC * 0.8f &&
            videoDelta > 0 &&
            (!latest.audioEnabled || audioDelta > 0)
        return if (sustained) Result.Verified else Result.Pending
    }
}
