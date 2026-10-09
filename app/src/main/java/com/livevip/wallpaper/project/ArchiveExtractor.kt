package com.livevip.wallpaper.project

import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Safely extracts a .mwproj (ZIP) into an empty directory.
 * Protections: archive size, entry count, per-entry size, total uncompressed size (checked while
 * streaming, not just from headers), compression-ratio sanity, unsafe names, duplicate names,
 * unexpected file types, and canonical-path containment (zip-slip).
 */
object ArchiveExtractor {

    fun extract(archive: File, destination: File) {
        if (!archive.isFile) throw ProjectException("Import file is missing")
        if (archive.length() > ProjectLimits.MAX_ARCHIVE_BYTES) {
            throw ProjectException("Project file is larger than ${ProjectLimits.MAX_ARCHIVE_BYTES / (1024 * 1024)} MB")
        }
        if (archive.length() < 22) throw ProjectException("File is too small to be a project archive")

        val zip = try {
            ZipFile(archive)
        } catch (e: IOException) {
            throw ProjectException("File is not a valid .mwproj (ZIP) archive")
        }

        zip.use { z ->
            val entries = ArrayList<ZipEntry>()
            val en = z.entries()
            while (en.hasMoreElements()) entries.add(en.nextElement())
            if (entries.size > ProjectLimits.MAX_ENTRY_COUNT) {
                throw ProjectException("Archive has too many entries (max ${ProjectLimits.MAX_ENTRY_COUNT})")
            }

            // Normalize every name first so unsafe entries are rejected before anything is written.
            val plan = LinkedHashMap<ZipEntry, String>()
            val seen = HashSet<String>()
            for (e in entries) {
                if (e.isDirectory) {
                    if (SafePath.normalize(e.name.trimEnd('/')) == null && e.name.trimEnd('/').isNotEmpty()) {
                        throw ProjectException("Unsafe folder name in archive: ${e.name}")
                    }
                    continue
                }
                val rel = SafePath.normalize(e.name) ?: throw ProjectException("Unsafe path in archive: ${e.name}")
                if (!seen.add(rel)) throw ProjectException("Duplicate file in archive: $rel")
                val ext = SafePath.extension(rel)
                if (ext !in ProjectLimits.ALLOWED_EXTENSIONS) {
                    throw ProjectException("Unsupported file type in archive: $rel (only .png and .json are allowed)")
                }
                if (e.size > ProjectLimits.MAX_ENTRY_BYTES) throw ProjectException("File too large in archive: $rel")
                val compressed = e.compressedSize
                if (e.size > 10L * 1024 * 1024 && compressed > 0 && e.size / compressed > 200) {
                    throw ProjectException("Suspicious compression ratio in $rel")
                }
                plan[e] = rel
            }

            // Support archives that wrap everything in one folder (e.g. when a folder was zipped).
            val prefix = detectWrapperPrefix(plan.values)
            if (!plan.values.any { it == "manifest.json" } && prefix == null) {
                throw ProjectException("manifest.json is missing from the project archive")
            }

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
                    throw ProjectException("Could not read $rel (archive may be corrupt or encrypted)")
                }
            }
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
