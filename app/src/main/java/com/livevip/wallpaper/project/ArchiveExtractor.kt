package com.livevip.wallpaper.project

import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Safely extracts a .mwproj (ZIP) into an empty directory.
 *
 * The name, type and size rules come from [ArchiveRules.plan], which the checker also uses, so the
 * extractor accepts exactly what the report calls valid. While streaming, the total uncompressed size
 * is checked again, because headers can lie. Canonical-path containment is a second guard against
 * zip-slip.
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
        } catch (e: Exception) {
            throw ProjectException("File is not a valid .mwproj (ZIP) archive. Export the project again as a ZIP-based .mwproj.")
        }

        zip.use { z ->
            val entries = ArrayList<ZipEntry>()
            val en = z.entries()
            while (en.hasMoreElements()) entries.add(en.nextElement())

            // Pass 1: check every name and type before anything is written.
            val report = ReportBuilder()
            val plan = ArchiveRules.plan(entries, CheckLimits.DEFAULT, report)
            report.firstError()?.let { throw ProjectException(it.message) }
            if (plan.files.isEmpty()) {
                throw ProjectException("The archive contains no project files. manifest.json is missing.")
            }
            if (plan.files.none { it.rel == "manifest.json" }) {
                throw ProjectException("manifest.json is missing from the archive. It must be at the top level, or inside the one folder that holds all project files.")
            }

            // Pass 2: write files, streaming and enforcing the total size limit.
            val destCanonical = destination.canonicalPath + File.separator
            var total = 0L
            val buffer = ByteArray(64 * 1024)
            for (p in plan.files) {
                val out = File(destination, p.rel).canonicalFile
                if (!out.path.startsWith(destCanonical)) throw ProjectException("Blocked unsafe path: ${p.rel}")
                out.parentFile?.mkdirs()
                try {
                    z.getInputStream(p.entry).use { input ->
                        out.outputStream().use { output ->
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                total += n
                                if (total > ProjectLimits.MAX_UNCOMPRESSED_BYTES) {
                                    throw ProjectException(
                                        "Project is too large when unpacked (limit ${ProjectLimits.MAX_UNCOMPRESSED_BYTES / (1024 * 1024)} MB)",
                                    )
                                }
                                output.write(buffer, 0, n)
                            }
                        }
                    }
                } catch (e: ProjectException) {
                    throw e
                } catch (e: IOException) {
                    throw ProjectException("Could not read ${p.rel} (the archive may be corrupt or encrypted)")
                }
            }
        }
    }
}
