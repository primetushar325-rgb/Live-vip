package com.livevip.wallpaper.project

import com.livevip.wallpaper.TestFiles
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The documented example in docs/examples/full-example-manifest.json must stay a valid project.
 * If the format changes and the docs fall behind, this test fails.
 */
class FullExampleTest {

    @Test
    fun documentedFullExampleIsImportable() {
        val manifest = File("../docs/examples/full-example-manifest.json").readText(Charsets.UTF_8)

        // Images the example refers to, with the sizes the example's layers need (masks match their layers).
        val images = linkedMapOf(
            "background.png" to (720 to 1280),
            "depth.png" to (720 to 1280),
            "preview.png" to (360 to 640),
            "layers/cape.png" to (260 to 420),
            "masks/cape.png" to (260 to 420),
            "layers/body.png" to (300 to 520),
            "masks/body.png" to (300 to 520),
            "layers/hair.png" to (180 to 200),
            "masks/hair.png" to (180 to 200),
        )
        val entries = LinkedHashMap<String, ByteArray>()
        entries["manifest.json"] = manifest.toByteArray(Charsets.UTF_8)
        for ((path, size) in images) entries[path] = TestFiles.pngBytes(size.first, size.second)

        val archive = File(TestFiles.tempDir("example"), "example.mwproj")
        archive.writeBytes(TestFiles.zipBytes(entries))
        val report = ProjectChecker.inspectArchive(archive, "example.mwproj").report

        assertTrue(report.errors.joinToString { "${it.path}: ${it.message}" }, report.isImportable)
        assertTrue(report.passed.any { it.path == "layers/body.png" })
    }
}
