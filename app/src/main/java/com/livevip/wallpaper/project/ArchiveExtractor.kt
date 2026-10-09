package com.livevip.wallpaper.project

import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Safely extracts a .mwproj (ZIP) into an empty directory.
 *
 * Protections: archive size, entry count, per-entry size, total uncompressed size (checked while
 * streaming, not just from headers), compression-ratio sanity, unsafe names (absolute paths, drive
 * letters, `..`), duplicate names, disallowed file types, and canonical-path containment (zip-slip).
 *
 * Harmless metadata that archivers add is skipped rather than rejected: `__MACOSX/` folders and
 * `._*` AppleDouble files (macOS Finder), `.DS_Store`, `Thumbs.db`, `desktop.ini`, and other
 * dot-prefixed names. Backslash separators and a leading `./` are normalized to `/`.
 */
object ArchiveExtractor {

    private val IGNORED_FILE_NAMES = setOf("thumbs.db", "desktop.ini")

    fun extract(archive: File, destination: File) {
        if (!archive.isFile) throw ProjectException("Import file is missing")
        if (archive.length() > ProjectLimits.MAX_ARCHIVE_BYTES) {
            throw ProjectException("Project file is larger than ${ProjectLimits.MAX_ARCHIVE_BYTES / (1024 * 1024)} MB")
        }
        if (archive.length() < 22) throw ProjectException("File is too small to be a project archive")

        val zip = try {
            ZipFile(archive)
        } catch (e: Exception) {
            throw ProjectException("File is not a valid .mwproj (ZIP) archive. Export the project again as a ZIP-based .mwproj.")
        }

        zip.use { z ->
            val entries = ArrayList<ZipEntry>()
            val en = z.entries()
            while (en.hasMoreElements()) entries.add(en.nextElement())

            // Pass 1: check every name before anything is written.
            val plan = LinkedHashMap<ZipEntry, String>()
            val seen = HashSet<String>()
            for (e in entries) {
                val normalized = normalizeName(e.name).trimEnd('/')
                if (normalized.isEmpty() && e.isDirectory) continue // archive root entry such as "./"
                val segments = normalized.split('/')
                checkNameIsSafe(e.name, segments) // every entry, including metadata, must have a safe name
                if (isIgnorable(segments) || e.isDirectory) continue
                val rel = SafePath.normalize(normalizeName(e.name).trimEnd('/'))
                    ?: throw ProjectException("Unsafe path in archive: ${e.name}")
                if (!seen.add(rel)) throw ProjectException("Duplicate file in archive: $rel")
                val ext = SafePath.extension(rel)
                if (ext !in ProjectLimits.ALLOWED_EXTENSIONS) {
                    throw ProjectException("Unsupported file in archive: $rel. Only .png and .json files are allowed, so remove it from the project.")
                }
                if (e.size > ProjectLimits.MAX_ENTRY_BYTES) throw ProjectException("File too large in archive: $rel")
                val compressed = e.compressedSize
                if (e.size > 10L * 1024 * 1024 && compressed > 0 && e.size / compressed > 200) {
                    throw ProjectException("Suspicious compression ratio in $rel")
                }
                plan[e] = rel
            }
            if (plan.isEmpty()) throw ProjectException("The archive contains no project files. manifest.json must be at the top level or in one folder.")
            if (plan.size > ProjectLimits.MAX_ENTRY_COUNT) {
                throw ProjectException("Archive has too many files (max ${ProjectLimits.MAX_ENTRY_COUNT})")
            }

            // Support archives that wrap everything in one folder (e.g. when a folder was zipped).
            val prefix = detectWrapperPrefix(plan.values)
            if (!plan.values.any { it == "manifest.json" } && prefix == null) {
                throw ProjectException("manifest.json is missing from the project archive. It must be at the top level or inside one folder.")
            }

            // Pass 2: write files, streaming and enforcing the total size limit.
            val destCanonical = destination.canonicalPath + File.separator
            var total = 0L
            val buffer = ByteArray(64 * 1024)
            for ((entry, rawRel) in plan) {
                val rel = if (prefix != null) rawRel.removePrefix("$prefix/") else rawRel
                val out = File(destination, rel).canonicalFile
                if (!out.path.startsWith(destCanonical)) throw ProjectException("Blocked unsafe path: $rel")
                out.parentFile?.mkdirs()
                try {
                    z.getInputStream(entry).use { input ->
                        out.outputStream().use { output ->
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                total += n
                                if (total > ProjectLimits.MAX_UNCOMPRESSED_BYTES) {
                                    throw ProjectException("Project is too large when unpacked (limit ${ProjectLimits.MAX_UNCOMPRESSED_BYTES / (1024 * 1024)} MB)")
                                }
                                output.write(buffer, 0, n)
                            }
                        }
                    }
                } catch (e: ProjectException) {
                    throw e
                } catch (e: IOException) {
                    throw ProjectException("Could not read $rel (the archive may be corrupt or encrypted)")
                }
            }
        }
    }

    /** Converts Windows separators and removes leading "./" segments. Other rules are checked afterwards. */
    internal fun normalizeName(raw: String): String {
        var name = raw.replace('\\', '/')
        while (name.startsWith("./")) name = name.substring(2)
        return name
    }

    /** Rejects names that could escape the destination. Runs on every entry, before any entry is skipped. */
    private fun checkNameIsSafe(original: String, segments: List<String>) {
        val unsafe = original.isEmpty() ||
            original.startsWith("/") ||
            original.contains(':') ||
            original.contains('\u0000') ||
            segments.any { it == ".." || it.isEmpty() }
        if (unsafe) throw ProjectException("Unsafe path in archive: $original")
    }

    /** macOS and desktop metadata, and dot-prefixed names, are skipped instead of extracted. */
    private fun isIgnorable(segments: List<String>): Boolean {
        return segments.any { seg ->
            seg == "__MACOSX" || (seg.startsWith(".") && seg != "..") || seg.lowercase() in IGNORED_FILE_NAMES
        }
    }

    /** Returns the wrapper folder name if every entry lives under one folder that holds manifest.json. */
    private fun detectWrapperPrefix(names: Collection<String>): String? {
        if (names.isEmpty()) return null
        val first = names.first().substringBefore('/', missingDelimiterValue = "")
        if (first.isEmpty()) return null
        if (names.any { !it.startsWith("$first/") }) return null
        return if (names.contains("$first/manifest.json")) first else null
    }
}
