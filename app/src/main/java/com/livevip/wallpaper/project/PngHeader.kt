package com.livevip.wallpaper.project

import java.io.File
import java.io.IOException

/** Pixel size of a PNG, read from its IHDR chunk. */
data class PngSize(val width: Int, val height: Int)

/** Reads PNG dimensions from the IHDR chunk without decoding pixels. */
object PngHeader {
    private val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private const val HEADER_BYTES = 24

    fun read(file: File): PngSize {
        if (!file.isFile) throw ProjectException("Missing file: ${file.name}")
        if (file.length() > ProjectLimits.MAX_ENTRY_BYTES) throw ProjectException("${file.name} is larger than 40 MB")
        val header = try {
            file.inputStream().use { readHead(it, HEADER_BYTES) }
        } catch (e: IOException) {
            throw ProjectException("Cannot read ${file.name}")
        }
        return parse(file.name, header)
    }

    /** Parses the first 24 bytes of a PNG. [name] is only used in messages. */
    fun parse(name: String, header: ByteArray): PngSize {
        if (header.size < HEADER_BYTES) throw ProjectException("$name is not a valid PNG (the file is too short)")
        for (i in SIGNATURE.indices) {
            if (header[i] != SIGNATURE[i]) throw ProjectException("$name is not a PNG image")
        }
        if (String(header, 12, 4, Charsets.US_ASCII) != "IHDR") throw ProjectException("$name has a corrupt PNG header")
        val w = readInt(header, 16)
        val h = readInt(header, 20)
        if (w <= 0 || h <= 0) throw ProjectException("$name has invalid dimensions")
        return PngSize(w, h)
    }

    private fun readInt(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)
}
