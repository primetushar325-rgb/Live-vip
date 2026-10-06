package com.livevip.app.streaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Playlist boundary behavior — the stream must NEVER end or reset because of
 * a boundary (except an explicit PLAY_ONCE natural end).
 */
class PlaylistEngineTest {

    private fun engine(vararg names: String, mode: LoopMode = LoopMode.LOOP_ALL) =
        PlaylistEngine(names.toList(), mode)

    @Test
    fun `loop all wraps around forever`() {
        val e = engine("a", "b", "c")
        assertEquals("a", e.current)
        assertEquals("b", e.onItemFinished())
        assertEquals("c", e.onItemFinished())
        assertEquals("a", e.onItemFinished()) // wrap
        assertEquals(1, e.passesCompleted)
        assertEquals(3, e.boundaryCount)
    }

    @Test
    fun `loop one returns same item on EOF (defensive)`() {
        val e = engine("a", "b", mode = LoopMode.LOOP_ONE)
        assertTrue(e.usesInternalLoop)
        assertEquals("a", e.onItemFinished())
        assertEquals("a", e.onItemFinished())
    }

    @Test
    fun `internal loop counts boundaries without changing item`() {
        val e = engine("a", "b", mode = LoopMode.LOOP_ONE)
        e.onInternalLoop()
        e.onInternalLoop()
        assertEquals("a", e.current)
        assertEquals(2, e.boundaryCount)
    }

    @Test
    fun `play once ends naturally only after full pass`() {
        val e = engine("a", "b", mode = LoopMode.PLAY_ONCE)
        assertTrue(e.canEndNaturally)
        assertEquals("b", e.onItemFinished())
        assertNull(e.onItemFinished()) // finished
        assertFalse(engine("a", mode = LoopMode.LOOP_ALL).canEndNaturally)
    }

    @Test
    fun `shuffle never immediately repeats across many seeds`() {
        (1..20).forEach { seed ->
            val e = PlaylistEngine(listOf("a", "b", "c", "d"), LoopMode.SHUFFLE, Random(seed))
            var last = e.current
            repeat(200) {
                val next = e.onItemFinished() ?: return@repeat
                assertTrue("seed $seed: immediate repeat $last -> $next", next != last)
                last = next
            }
        }
    }

    @Test
    fun `shuffle boundary still counts and keeps the stream alive`() {
        val e = PlaylistEngine(listOf("a", "b"), LoopMode.SHUFFLE, Random(3))
        repeat(100) { e.onItemFinished() }
        assertEquals(100, e.boundaryCount)
        assertTrue(e.hasMultipleItems)
    }

    @Test
    fun `manual skip never ends the broadcast`() {
        val once = engine("a", "b", "c", mode = LoopMode.PLAY_ONCE)
        repeat(10) { once.skipToNext() }
        // still playing something — user asked to keep going
        assertTrue(once.current.isNotEmpty())

        val loop = engine("a", "b", "c")
        loop.skipToNext()
        loop.skipToNext()
        assertEquals("c", loop.current)
        loop.skipToNext()
        assertEquals("a", loop.current)
    }

    @Test
    fun `skip previous wraps`() {
        val e = engine("a", "b", "c")
        e.skipToNext()
        e.skipToNext()
        assertEquals("c", e.current)
        assertEquals("b", e.skipToPrevious())
        assertEquals("a", e.skipToPrevious())
        assertEquals("c", e.skipToPrevious()) // wraps to the end
    }

    @Test
    fun `item error advances and exhausts after budget`() {
        val e = engine("a", "b", "c")
        assertEquals("b", e.onItemError(maxConsecutiveFailures = 2))
        assertNull(e.onItemError(maxConsecutiveFailures = 2)) // budget exhausted
    }

    @Test
    fun `successful boundary clears the failure budget`() {
        val e = engine("a", "b", "c")
        e.onItemError(3)
        e.onItemFinished()
        assertEquals(0, e.consecutiveFailures)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `empty playlist is rejected`() {
        PlaylistEngine(emptyList<String>(), LoopMode.LOOP_ALL)
    }

    @Test
    fun `single video uses internal loop in every non-once mode`() {
        assertTrue(engine("only", mode = LoopMode.LOOP_ALL).usesInternalLoop)
        assertTrue(engine("only", mode = LoopMode.LOOP_ONE).usesInternalLoop)
        assertFalse(engine("only", mode = LoopMode.PLAY_ONCE).usesInternalLoop)
    }
}
