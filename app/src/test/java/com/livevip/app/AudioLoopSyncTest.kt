package com.livevip.app.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PART 4: audio loop synchronization decision — at every VIDEO loop boundary
 * the audio decoder must be near the file start, otherwise it is restarted
 * so audio content stays aligned with video content.
 */
class AudioLoopSyncTest {

    @Test
    fun `audio at loop start is already aligned`() {
        assertFalse(AudioLoopSync.shouldResyncAtVideoLoop(0.0))
        assertFalse(AudioLoopSync.shouldResyncAtVideoLoop(0.9))
        assertFalse(AudioLoopSync.shouldResyncAtVideoLoop(1.5))
    }

    @Test
    fun `audio mid file when video restarted needs resync`() {
        // Audio track is 3s longer than video: at the video loop the audio is
        // still playing the previous loop's tail.
        assertTrue(AudioLoopSync.shouldResyncAtVideoLoop(2.1))
        assertTrue(AudioLoopSync.shouldResyncAtVideoLoop(37.9))
        assertTrue(AudioLoopSync.shouldResyncAtVideoLoop(300.0))
    }

    @Test
    fun `negative or invalid time is treated as aligned (no restart)`() {
        assertFalse(AudioLoopSync.shouldResyncAtVideoLoop(-1.0))
        assertFalse(AudioLoopSync.shouldResyncAtVideoLoop(Double.NaN))
        assertFalse(AudioLoopSync.shouldResyncAtVideoLoop(Double.POSITIVE_INFINITY))
    }
}
