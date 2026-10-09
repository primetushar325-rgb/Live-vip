package com.livevip.wallpaper.project

import com.livevip.wallpaper.TestFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ArchiveExtractorTest {

    private fun validEntries(prefix: String = ""): Map<String, ByteArray> {
        val src = TestFiles.validProjectDir(TestFiles.tempDir("src"))
        val map = LinkedHashMap<String, ByteArray>()
        src.walkTopDown().filter { it.isFile }.forEach { f ->
            map[prefix + f.relativeTo(src).path.replace(File.separatorChar, '/')] = f.readBytes()
        }
        return map
    }

    @Test
    fun extractsValidArchiveAndValidates() {
        val zip = File(TestFiles.tempDir("zip"), "ok.mwproj")
        TestFiles.zip(zip, validEntries())
        val out = TestFiles.tempDir("out")
        ArchiveExtractor.extract(zip, out)
        val validated = ProjectValidator.validate(out)
        assertEquals("Test Project", validated.manifest.name)
        assertEquals(300, validated.layers[0].width)
    }

    @Test
    fun unwrapsSingleFolderArchives() {
        val zip = File(TestFiles.tempDir("zip"), "wrapped.mwproj")
        TestFiles.zip(zip, validEntries(prefix = "my-project/"))
        val out = TestFiles.tempDir("out")
        ArchiveExtractor.extract(zip, out)
        assertTrue(File(out, "manifest.json").isFile)
    }

    @Test
    fun rejectsZipSlipEntries() {
        val zip = File(TestFiles.tempDir("zip"), "evil.mwproj")
        val entries = validEntries().toMutableMap()
        entries["../escape.json"] = "{}".toByteArray()
        TestFiles.zip(zip, entries)
        assertThrows(ProjectException::class.java) { ArchiveExtractor.extract(zip, TestFiles.tempDir("out")) }
    }

    @Test
    fun rejectsDisallowedFileTypes() {
        val zip = File(TestFiles.tempDir("zip"), "exe.mwproj")
        val entries = validEntries().toMutableMap()
        entries["payload.exe"] = ByteArray(16)
        TestFiles.zip(zip, entries)
        assertThrows(ProjectException::class.java) { ArchiveExtractor.extract(zip, TestFiles.tempDir("out")) }
    }

    @Test
    fun rejectsArchiveWithoutManifest() {
        val zip = File(TestFiles.tempDir("zip"), "nomanifest.mwproj")
        TestFiles.zip(zip, mapOf("background.png" to TestFiles.pngBytes(10, 10)))
        assertThrows(ProjectException::class.java) { ArchiveExtractor.extract(zip, TestFiles.tempDir("out")) }
    }

    @Test
    fun rejectsNonZipFiles() {
        val f = File(TestFiles.tempDir("zip"), "text.mwproj")
        f.writeText("hello this is not a zip archive at all, not even close to one")
        assertThrows(ProjectException::class.java) { ArchiveExtractor.extract(f, TestFiles.tempDir("out")) }
    }
}
