package com.livevip.wallpaper.project

import java.io.File
import java.io.IOException

/** What the first bytes of a file say it is. Used instead of trusting the file name. */
enum class DetectedKind { EMPTY, ZIP, PNG, JPEG, PDF, GZIP, TEXT, UNKNOWN }

/**
 * Detects file types by their signatures ("magic numbers"). A `.mwproj` is a ZIP archive, so the
 * importer relies on the bytes, not on the name the document provider reports. Providers may
 * return names without an extension, with a different extension, or with none at all.
 */
object FileSniffer {
    private const val HEADER_BYTES = 512

    fun kindOf(file: File): DetectedKind {
        if (!file.isFile) throw ProjectException("The selected file is missing")
        val header = try {
            file.inputStream().use { input ->
                val buf = ByteArray(HEADER_BYTES)
                var read = 0
                while (read < buf.size) {
                    val n = input.read(buf, read, buf.size - read)
                    if (n < 0) break
                    read += n
                }
                buf.copyOf(read)
            }
        } catch (e: IOException) {
            throw ProjectException("Could not read the selected file")
        }
        return kindOf(header)
    }

    fun kindOf(header: ByteArray): DetectedKind {
        if (header.isEmpty()) return DetectedKind.EMPTY
        fun starts(vararg sig: Int) = header.size >= sig.size && sig.indices.all { (header[it].toInt() and 0xFF) == sig[it] }
        return when {
            // Local file header, empty archive, or spanned archive marker.
            starts(0x50, 0x4B, 0x03, 0x04) || starts(0x50, 0x4B, 0x05, 0x06) || starts(0x50, 0x4B, 0x07, 0x08) -> DetectedKind.ZIP
            starts(0x89, 0x50, 0x4E, 0x47) -> DetectedKind.PNG
            starts(0xFF, 0xD8, 0xFF) -> DetectedKind.JPEG
            starts(0x25, 0x50, 0x44, 0x46) -> DetectedKind.PDF
            starts(0x1F, 0x8B) -> DetectedKind.GZIP
            looksLikeText(header) -> DetectedKind.TEXT
            else -> DetectedKind.UNKNOWN
        }
    }

    /** True when the sample has no NUL bytes and almost all bytes are printable ASCII or UTF-8. */
    private fun looksLikeText(sample: ByteArray): Boolean {
        if (sample.isEmpty()) return false
        var printable = 0
        for (b in sample) {
            val c = b.toInt() and 0xFF
            if (c == 0) return false
            if (c in 0x20..0x7E || c == 0x09 || c == 0x0A || c == 0x0D || c >= 0x80) printable++
        }
        return printable * 100 / sample.size >= 95
    }
}
