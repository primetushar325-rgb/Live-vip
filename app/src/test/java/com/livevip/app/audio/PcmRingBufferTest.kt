package com.livevip.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PcmRingBuffer — the mic/video audio mixing buffer. Overflow drops OLDEST
 * data (bounded latency), underrun fills with silence (never throws, never
 * returns garbage from freed memory).
 */
class PcmRingBufferTest {

    @Test
    fun `fifo order is preserved across wrap-around`() {
        val buf = PcmRingBuffer(16)
        val src = ByteArray(16) { (it + 1).toByte() } // 1..16
        buf.write(src, 0, 16)
        val out = ByteArray(16)
        buf.read(out, 16)
        assertTrue(out.contentEquals(src))
    }

    @Test
    fun `many small writes read back in order`() {
        val buf = PcmRingBuffer(4_096) // larger than the total written
        var next: Byte = 0
        repeat(100) {
            val chunk = ByteArray(37) { next++ }
            buf.write(chunk, 0, chunk.size)
        }
        val out = ByteArray(3_700)
        buf.read(out, 3_700)
        next = 0
        repeat(3_700) { idx ->
            assertEquals("at $idx", next++, out[idx])
        }
    }

    @Test
    fun `overflow drops oldest data not newest`() {
        val buf = PcmRingBuffer(8)
        buf.write(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), 0, 8)
        buf.write(byteArrayOf(9, 10), 0, 2) // overflows: 1,2 dropped
        assertEquals(8, buf.available())
        val out = ByteArray(8)
        buf.read(out, 8)
        assertTrue(out.contentEquals(byteArrayOf(3, 4, 5, 6, 7, 8, 9, 10)))
    }

    @Test
    fun `underrun reads as silence`() {
        val buf = PcmRingBuffer(16)
        buf.write(byteArrayOf(1, 2), 0, 2)
        val out = ByteArray(8)
        buf.read(out, 8)
        assertEquals(1, out[0])
        assertEquals(2, out[1])
        repeat(6) { assertEquals("silence at $it", 0.toByte(), out[2 + it]) }
        assertEquals(0, buf.available())
    }

    @Test
    fun `clear empties the buffer`() {
        val buf = PcmRingBuffer(8)
        buf.write(ByteArray(8), 0, 8)
        buf.clear()
        assertEquals(0, buf.available())
        val out = ByteArray(4)
        buf.read(out, 4)
        assertTrue(out.all { it == 0.toByte() })
    }

    @Test
    fun `write larger than capacity keeps only the newest tail`() {
        val buf = PcmRingBuffer(4)
        buf.write(byteArrayOf(1, 2, 3, 4, 5, 6), 0, 6) // only 3,4,5,6 kept
        assertEquals(4, buf.available())
        val out = ByteArray(4)
        buf.read(out, 4)
        assertTrue(out.contentEquals(byteArrayOf(3, 4, 5, 6)))
    }
}
