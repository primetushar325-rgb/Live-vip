package com.livevip.app.rtmp

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class RtmpClientTest {
    @Test fun annexBIsConvertedToLengthPrefixedNalus() {
        val input = byteArrayOf(0, 0, 0, 1, 0x65, 1, 2, 0, 0, 1, 0x41, 3)
        val expected = byteArrayOf(0, 0, 0, 3, 0x65, 1, 2, 0, 0, 0, 2, 0x41, 3)
        assertArrayEquals(expected, RtmpClient.annexBToAvcc(input))
    }
}
