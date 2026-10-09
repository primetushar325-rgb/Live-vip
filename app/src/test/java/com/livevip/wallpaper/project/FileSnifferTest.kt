package com.livevip.wallpaper.project

import com.livevip.wallpaper.TestFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class FileSnifferTest {

    @Test
    fun recognizesZipArchives() {
        assertEquals(DetectedKind.ZIP, FileSniffer.kindOf("PK\u0003\u0004rest".toByteArray(Charsets.ISO_8859_1)))
        assertEquals(DetectedKind.ZIP, FileSniffer.kindOf(byteArrayOf(0x50, 0x4B, 0x05, 0x06) + ByteArray(18)))
    }

    @Test
    fun recognizesCommonNonProjectTypes() {
        assertEquals(DetectedKind.PNG, FileSniffer.kindOf(TestFiles.pngBytes(4, 4)))
        assertEquals(DetectedKind.JPEG, FileSniffer.kindOf(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
        assertEquals(DetectedKind.PDF, FileSniffer.kindOf("%PDF-1.7".toByteArray()))
        assertEquals(DetectedKind.GZIP, FileSniffer.kindOf(byteArrayOf(0x1F, 0x8B.toByte(), 0x08)))
    }

    @Test
    fun recognizesPlainTextAndUnknownBinary() {
        assertEquals(DetectedKind.TEXT, FileSniffer.kindOf("{\"format\": \"mwproj\"}\n".toByteArray()))
        assertEquals(DetectedKind.UNKNOWN, FileSniffer.kindOf(byteArrayOf(0, 1, 2, 3, 4, 5)))
        assertEquals(DetectedKind.EMPTY, FileSniffer.kindOf(ByteArray(0)))
    }

    @Test
    fun readsTheFileHeaderFromDisk() {
        val f = File(TestFiles.tempDir("sniff"), "x.mwproj")
        TestFiles.zip(f, mapOf("manifest.json" to "{}".toByteArray()))
        assertEquals(DetectedKind.ZIP, FileSniffer.kindOf(f))
    }

    @Test
    fun missingFileIsAReadableError() {
        assertThrows(ProjectException::class.java) { FileSniffer.kindOf(File("does/not/exist.mwproj")) }
    }
}
