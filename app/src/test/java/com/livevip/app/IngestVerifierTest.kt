package com.livevip.app.streaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PART 4: ingest verification — "socket connected" must never be reported as
 * LIVE. LIVE is claimed only after sustained, verified media flow.
 */
class IngestVerifierTest {

    private fun sample(
        elapsed: Float, video: Long, audio: Long, audioEnabled: Boolean = true
    ) = IngestVerifier.Sample(elapsed, video, audio, audioEnabled)

    @Test
    fun `empty history stays pending`() {
        assertEquals(IngestVerifier.Result.Pending, IngestVerifier.evaluate(emptyList()))
    }

    @Test
    fun `sustained video and audio flow verifies`() {
        // 7 one-second samples with steady frame growth.
        val history = (0..7).map { i ->
            sample(i.toFloat(), video = 30L * i, audio = 43L * i)
        }
        assertEquals(IngestVerifier.Result.Verified, IngestVerifier.evaluate(history))
    }

    @Test
    fun `short window is not enough`() {
        val history = listOf(sample(0f, 0, 0), sample(2f, 60, 86))
        assertEquals(IngestVerifier.Result.Pending, IngestVerifier.evaluate(history))
    }

    @Test
    fun `zero video frames fails with honest reason`() {
        val history = (0..25).map { i -> sample(i.toFloat(), 0, 0) }
        val result = IngestVerifier.evaluate(history)
        assertTrue(result is IngestVerifier.Result.Failed)
        assertTrue(
            (result as IngestVerifier.Result.Failed).reason.contains("video packets")
        )
    }

    @Test
    fun `video flowing but audio missing fails honestly`() {
        val history = (0..25).map { i -> sample(i.toFloat(), 30L * i, 0) }
        val result = IngestVerifier.evaluate(history)
        assertTrue(result is IngestVerifier.Result.Failed)
        assertTrue((result as IngestVerifier.Result.Failed).reason.contains("audio"))
    }

    @Test
    fun `audio requirement waived when audio disabled`() {
        val history = (0..7).map { i ->
            sample(i.toFloat(), 30L * i, 0, audioEnabled = false)
        }
        assertEquals(IngestVerifier.Result.Verified, IngestVerifier.evaluate(history))
    }

    @Test
    fun `inconsistent flow times out`() {
        // Video froze at 60 frames after t=2; never a sustained window.
        val history = (0..26).map { i ->
            sample(i.toFloat(), if (i <= 2) 30L * i else 60L, 43L * i)
        }
        val result = IngestVerifier.evaluate(history)
        assertTrue(result is IngestVerifier.Result.Failed)
    }

    @Test
    fun `failure happens before the generic timeout when media never moved`() {
        // No-media failure (20s) must trigger earlier than the 25s timeout.
        val history = (0..19).map { i -> sample(i.toFloat(), 0, 0) }
        // At exactly 19s: still pending.
        assertEquals(IngestVerifier.Result.Pending, IngestVerifier.evaluate(history))
        val at20 = (0..20).map { i -> sample(i.toFloat(), 0, 0) }
        assertTrue(IngestVerifier.evaluate(at20) is IngestVerifier.Result.Failed)
    }

    @Test
    fun `verification window only counts recent samples`() {
        // Frames flowed during 0..3s, then froze. After 25s total the window
        // (last 6s) has no progress → Failed, not Verified.
        val history = (0..25).map { i ->
            val v = if (i <= 3) 30L * i else 90L
            val a = if (i <= 3) 43L * i else 129L
            sample(i.toFloat(), v, a)
        }
        assertTrue(IngestVerifier.evaluate(history) is IngestVerifier.Result.Failed)
    }
}
