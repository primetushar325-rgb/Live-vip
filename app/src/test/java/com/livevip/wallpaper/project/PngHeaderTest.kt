package com.livevip.wallpaper.project

import com.livevip.wallpaper.TestFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class PngHeaderTest {
    @Test
    fun readsDimensionsFromIhdr() {
        val f = File(TestFiles.tempDir("png"), "a.png")
        TestFiles.writePng(f, 720, 1280)
        val size = PngHeader.read(f)
        assertEquals(720, size.width)
        assertEquals(1280, size.height)
    }

    @Test
    fun rejectsNonPngFiles() {
        val f = File(TestFiles.tempDir("png"), "fake.png")
        f.writeText("this is not an image at all, just text padding padding")
        assertThrows(ProjectException::class.java) { PngHeader.read(f) }
    }

    @Test
    fun rejectsTruncatedFiles() {
        val f = File(TestFiles.tempDir("png"), "short.png")
        f.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
        assertThrows(ProjectException::class.java) { PngHeader.read(f) }
    }
}
