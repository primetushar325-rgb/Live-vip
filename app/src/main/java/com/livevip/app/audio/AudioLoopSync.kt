package com.livevip.app.audio

/**
 * AUDIO LOOP SYNCHRONIZATION decision (Part 4) — pure logic, unit tested.
 *
 * The video and audio decoders loop INDEPENDENTLY at their own end-of-file.
 * When a file's audio track length differs from its video track length (very
 * common), the two content positions drift apart by that difference after
 * every loop: video shows loop N while audio still plays the tail of loop
 * N-1. Left alone this is a permanent, visible lip-sync error.
 *
 * At every VIDEO loop boundary the audio decoder's file position is checked:
 * if it is not near the loop start, the audio decoder is restarted from 0 so
 * the content positions re-align (the composited timeline itself is never
 * touched — encoder, muxer and RTMP continue).
 */
object AudioLoopSync {

    /** Audio considered aligned with a fresh video loop within this window. */
    const val ALIGNED_WINDOW_SEC = 1.5

    /**
     * @param audioFileTimeSec the audio decoder's current position in the file.
     * @return true when the audio decoder should be restarted from 0 to match
     *         a video loop that just happened.
     */
    fun shouldResyncAtVideoLoop(audioFileTimeSec: Double): Boolean =
        audioFileTimeSec.isFinite() && audioFileTimeSec > ALIGNED_WINDOW_SEC
}
