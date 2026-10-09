package com.livevip.wallpaper.project

import java.io.File
import java.io.IOException

/** Reads PNG dimensions from the IHDR chunk without decoding pixels. */
data class PngSize(val width: Int, val height: Int)

object PngHeader {
    private val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    fun read(file: File): PngSize {
        if (!file.isFile) throw ProjectException("Missing file: ${file.name}")
        if (file.length() > ProjectLimits.MAX_ENTRY_BYTES) throw ProjectException("${file.name} is larger than 40 MB")
        val header = ByteArray(24)
        try {
            file.inputStream().use { input ->
                var read = 0
                while (read < header.size) {
                    val n = input.read(header, read, header.size - read)
                    if (n < 0) break
                    read += n
                }
                if (read < header.size) throw ProjectException("${file.name} is not a valid PNG")
            }
        } catch (e: IOException) {
            throw ProjectException("Cannot read ${file.name}")
        }
        for (i in SIGNATURE.indices) {
            if (header[i] != SIGNATURE[i]) throw ProjectException("${file.name} is not a PNG image")
        }
        if (String(header, 12, 4, Charsets.US_ASCII) != "IHDR") throw ProjectException("${file.name} has a corrupt PNG header")
        val w = readInt(header, 16)
        val h = readInt(header, 20)
        if (w <= 0 || h <= 0) throw ProjectException("${file.name} has invalid dimensions")
        return PngSize(w, h)
    }

    private fun readInt(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)
}
