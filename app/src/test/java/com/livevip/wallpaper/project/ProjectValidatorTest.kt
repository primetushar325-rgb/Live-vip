package com.livevip.wallpaper.project

import com.livevip.wallpaper.TestFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class ProjectValidatorTest {

    @Test
    fun acceptsValidProject() {
        val dir = TestFiles.validProjectDir(TestFiles.tempDir("valid"))
        val v = ProjectValidator.validate(dir)
        assertEquals(1, v.layers.size)
        assertEquals(400, v.layers[0].height)
    }

    @Test
    fun rejectsBackgroundOfWrongSize() {
        val dir = TestFiles.validProjectDir(TestFiles.tempDir("bg"))
        TestFiles.writePng(File(dir, "background.png"), 700, 1280)
        val e = assertThrows(ProjectException::class.java) { ProjectValidator.validate(dir) }
        assertEquals(true, e.message!!.contains("background.png must be exactly"))
    }

    @Test
    fun rejectsMaskWithDifferentSize() {
        val dir = TestFiles.validProjectDir(TestFiles.tempDir("mask"))
        TestFiles.writePng(File(dir, "masks/hair.png"), 301, 400)
        assertThrows(ProjectException::class.java) { ProjectValidator.validate(dir) }
    }

    @Test
    fun rejectsLayerOutsideCanvas() {
        val dir = TestFiles.validProjectDir(TestFiles.tempDir("outside"))
        val text = File(dir, "manifest.json").readText().replace("\"x\":100,\"y\":200", "\"x\":5000,\"y\":200")
        File(dir, "manifest.json").writeText(text)
        assertThrows(ProjectException::class.java) { ProjectValidator.validate(dir) }
    }

    @Test
    fun rejectsMissingReferencedFile() {
        val dir = TestFiles.validProjectDir(TestFiles.tempDir("missing"))
        File(dir, "layers/hair.png").delete()
        assertThrows(ProjectException::class.java) { ProjectValidator.validate(dir) }
    }

    @Test
    fun rejectsProjectsThatExceedGpuMemoryBudget() {
        val dir = TestFiles.tempDir("huge")
        File(dir, "manifest.json").writeText(TestFiles.manifestJson(canvasW = 4096, canvasH = 4096))
        TestFiles.writePng(File(dir, "background.png"), 4096, 4096)
        TestFiles.writePng(File(dir, "depth.png"), 4096, 4096)
        val layers = (0 until 3).joinToString(",") { i ->
            """{"id":"l$i","file":"layers/l$i.png","x":0,"y":0,"depth":0.5}"""
        }
        File(dir, "manifest.json").writeText(TestFiles.manifestJson(canvasW = 4096, canvasH = 4096, layers = layers))
        for (i in 0 until 3) TestFiles.writePng(File(dir, "layers/l$i.png"), 4096, 4096)
        TestFiles.writePng(File(dir, "preview.png"), 16, 16)
        val e = assertThrows(ProjectException::class.java) { ProjectValidator.validate(dir) }
        assertEquals(true, e.message!!.contains("GPU memory"))
    }
}
