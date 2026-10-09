package com.livevip.wallpaper

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Helpers that build tiny synthetic projects for tests. Pixel data is not needed: only headers are read. */
object TestFiles {
    fun tempDir(name: String): File = Files.createTempDirectory("livevip-$name").toFile()

    /** A minimal valid PNG header (signature + IHDR) of the given size; the rest is padding. */
    fun pngBytes(width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        out.write(byteArrayOf(0, 0, 0, 13))
        out.write("IHDR".toByteArray(Charsets.US_ASCII))
        out.write(intBytes(width))
        out.write(intBytes(height))
        out.write(byteArrayOf(8, 6, 0, 0, 0))
        out.write(byteArrayOf(0, 0, 0, 0)) // CRC (not verified by the app)
        out.write(ByteArray(32))
        return out.toByteArray()
    }

    private fun intBytes(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

    fun writePng(file: File, width: Int, height: Int) {
        file.parentFile?.mkdirs()
        file.writeBytes(pngBytes(width, height))
    }

    fun manifestJson(
        canvasW: Int = 720,
        canvasH: Int = 1280,
        layers: String = "",
        extra: String = "",
    ): String = """
        {
          "format": "mwproj",
          "formatVersion": 1,
          "name": "Test Project",
          "canvas": {"width": $canvasW, "height": $canvasH},
          "preview": "preview.png",
          "background": {"file": "background.png", "depth": "depth.png"},
          "layers": [$layers]
          $extra
        }
    """.trimIndent()

    /** Writes a complete valid project directory (background, depth, preview, one masked layer). */
    fun validProjectDir(root: File): File {
        root.mkdirs()
        File(root, "manifest.json").writeText(
            manifestJson(
                layers = """{"id":"hair","role":"hair","file":"layers/hair.png","x":100,"y":200,"depth":0.7,
                    "mask":"masks/hair.png","rig":{"mode":"sway","amplitude":0.01,"frequency":0.5,"pivot":[0.5,0],"direction":[1,0]}}""",
            ),
        )
        writePng(File(root, "background.png"), 720, 1280)
        writePng(File(root, "depth.png"), 720, 1280)
        writePng(File(root, "preview.png"), 360, 640)
        writePng(File(root, "layers/hair.png"), 300, 400)
        writePng(File(root, "masks/hair.png"), 300, 400)
        return root
    }

    /** The file map of a complete valid project, keyed by archive path (optionally under a folder prefix). */
    fun validProjectEntries(prefix: String = ""): LinkedHashMap<String, ByteArray> {
        val src = validProjectDir(tempDir("src"))
        val map = LinkedHashMap<String, ByteArray>()
        src.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { f ->
            map[prefix + f.relativeTo(src).path.replace(File.separatorChar, '/')] = f.readBytes()
        }
        return map
    }

    /** Builds a ZIP archive in memory from name -> bytes. */
    fun zipBytes(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            for ((name, bytes) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** Builds a ZIP archive from name -> bytes. */
    fun zip(file: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(file.outputStream()).use { zos ->
            for ((name, bytes) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
    }
}
