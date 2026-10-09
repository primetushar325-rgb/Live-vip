package com.livevip.wallpaper.project

import java.util.zip.ZipEntry

/** Size and count limits used by the checker. Defaults come from [ProjectLimits]; tests pass smaller values. */
data class CheckLimits(
    val maxArchiveBytes: Long = ProjectLimits.MAX_ARCHIVE_BYTES,
    val maxUnpackedBytes: Long = ProjectLimits.MAX_UNCOMPRESSED_BYTES,
    val maxEntryBytes: Long = ProjectLimits.MAX_ENTRY_BYTES,
    val maxEntries: Int = ProjectLimits.MAX_ENTRY_COUNT,
    val maxManifestBytes: Long = ProjectLimits.MAX_MANIFEST_BYTES.toLong(),
    val maxImageSide: Int = ProjectLimits.MAX_IMAGE_SIDE,
    val maxTextureBytes: Long = ProjectLimits.MAX_TEXTURE_BYTES_TOTAL,
    val maxJsonDepth: Int = 64,
) {
    companion object {
        val DEFAULT = CheckLimits()
    }
}

/**
 * The archive rules shared by [ArchiveExtractor] and [ProjectChecker], so the checker reports
 * exactly what the importer would reject. Nothing here touches the disk.
 */
object ArchiveRules {
    private val IGNORED_FILE_NAMES = setOf("thumbs.db", "desktop.ini")

    /** An archive entry that passed the name and type rules. [rel] is its path inside the project. */
    class Planned(val rawName: String, val rel: String, val entry: ZipEntry)

    /** The accepted entries plus what was skipped. */
    /**
     * [rejected] holds paths that were refused for size or compression reasons. The checker uses it to
     * avoid a second, misleading "not in the archive" error for the same file.
     */
    class Plan(
        val files: List<Planned>,
        val prefix: String?,
        val ignored: List<String>,
        val declaredBytes: Long,
        val rejected: Set<String>,
    )

    /**
     * Checks every entry name and type and records each problem in [report]. Entries that fail are
     * left out of the plan, so later checks run on the rest of the project.
     */
    fun plan(entries: List<ZipEntry>, limits: CheckLimits, report: ReportBuilder): Plan {
        val accepted = LinkedHashMap<ZipEntry, String>()
        val firstNameOf = HashMap<String, String>()
        val ignored = ArrayList<String>()
        val rejected = HashSet<String>()

        for (e in entries) {
            val normalized = normalizeName(e.name).trimEnd('/')
            if (normalized.isEmpty() && e.isDirectory) continue // archive root entry such as "./"
            val segments = normalized.split('/')

            // Names are checked before anything is skipped, so unsafe names are never ignored silently.
            val unsafe = unsafeReason(e.name, segments)
            if (unsafe != null) {
                report.addError(
                    Section.CONTENTS, Code.UNSAFE_PATH, e.name,
                    "Unsafe path in archive: '${e.name}' ($unsafe). Paths must be relative, must not contain '..', and must not start with '/'.",
                    "Re-export the project so every file has a relative path.",
                )
                continue
            }
            if (isIgnorable(segments)) {
                ignored.add(e.name)
                continue
            }
            if (e.isDirectory) continue

            val rel = SafePath.normalize(normalized)
            if (rel == null) {
                report.addError(
                    Section.CONTENTS, Code.UNSAFE_PATH, e.name,
                    "Invalid path in archive: '${e.name}'. Names must be 1-80 characters, at most ${ProjectLimits.MAX_PATH_DEPTH} folder levels deep, and must not start with a dot.",
                )
                continue
            }

            val ext = SafePath.extension(rel)
            if (ext !in ProjectLimits.ALLOWED_EXTENSIONS) {
                val where = folderOf(rel)
                report.addError(
                    Section.CONTENTS, Code.UNSUPPORTED_FILE, rel,
                    "Unsupported file '${rel.substringAfterLast('/')}' ($where). Only .png and .json files are allowed.",
                    "Remove this file from the project, or convert it to PNG if it is an image.",
                )
                continue
            }

            val firstName = firstNameOf[rel]
            if (firstName != null) {
                report.addError(
                    Section.CONTENTS, Code.DUPLICATE_PATH, rel,
                    "Duplicate path '$rel': the archive contains it more than once (as '$firstName' and '${e.name}').",
                    "Keep one copy of each file.",
                )
                continue
            }
            firstNameOf[rel] = e.name

            if (e.size > limits.maxEntryBytes) {
                report.addError(
                    Section.LIMITS, Code.ENTRY_TOO_LARGE, rel,
                    "'$rel' is ${formatSize(e.size)}; the limit is ${formatSize(limits.maxEntryBytes)} per file.",
                    "Scale or crop the file.",
                )
                rejected.add(rel)
                continue
            }
            val compressed = e.compressedSize
            if (e.size > 10L * 1024 * 1024 && compressed > 0 && e.size / compressed > 200) {
                report.addError(
                    Section.LIMITS, Code.SUSPICIOUS_COMPRESSION, rel,
                    "'$rel' expands about ${e.size / compressed} times when unpacked. It is refused as a possible zip bomb.",
                )
                rejected.add(rel)
                continue
            }
            accepted[e] = rel
        }

        val declared = accepted.keys.sumOf { maxOf(it.size, 0L) }
        if (declared > limits.maxUnpackedBytes) {
            report.addError(
                Section.LIMITS, Code.UNPACKED_TOO_LARGE, null,
                "The project unpacks to ${formatSize(declared)}; the limit is ${formatSize(limits.maxUnpackedBytes)}.",
                "Remove unused files or reduce image sizes.",
            )
        }
        if (accepted.size > limits.maxEntries) {
            report.addError(
                Section.LIMITS, Code.TOO_MANY_FILES, null,
                "The archive has ${accepted.size} project files; the limit is ${limits.maxEntries}.",
                "Remove unused files.",
            )
        }
        if (ignored.isNotEmpty()) {
            report.addInfo(
                Section.CONTENTS, Code.IGNORED_METADATA, null,
                "Skipped ${ignored.size} archiver metadata entr${if (ignored.size == 1) "y" else "ies"} " +
                    "(for example ${ignored.take(3).joinToString()}). They are not project content.",
            )
        }

        val prefix = detectWrapperPrefix(accepted.values.toList())
        if (prefix != null) {
            report.addInfo(
                Section.CONTENTS, Code.WRAPPER_FOLDER, prefix,
                "All project files are inside the folder '$prefix/'. The app uses that folder as the project root.",
            )
        }
        val files = accepted.map { (e, rel) ->
            Planned(e.name, if (prefix != null) rel.removePrefix("$prefix/") else rel, e)
        }
        return Plan(files, prefix, ignored, declared, rejected)
    }

    /** Converts Windows separators and removes leading "./" segments. Other rules are checked afterwards. */
    fun normalizeName(raw: String): String {
        var name = raw.replace('\\', '/')
        while (name.startsWith("./")) name = name.substring(2)
        return name
    }

    private fun unsafeReason(original: String, segments: List<String>): String? {
        return when {
            original.isEmpty() -> "empty name"
            original.startsWith("/") || original.startsWith("\\") -> "absolute path"
            original.contains(':') -> "contains ':'"
            original.contains('\u0000') -> "contains a NUL character"
            segments.any { it == ".." } -> "contains '..'"
            segments.any { it.isEmpty() } -> "empty folder name"
            else -> null
        }
    }

    /** macOS and desktop metadata, and dot-prefixed names, are skipped instead of extracted. */
    private fun isIgnorable(segments: List<String>): Boolean =
        segments.any { seg ->
            seg == "__MACOSX" || seg.startsWith(".") || seg.lowercase() in IGNORED_FILE_NAMES
        }

    /** Returns the wrapper folder if every entry lives under one folder that holds manifest.json. */
    private fun detectWrapperPrefix(names: Collection<String>): String? {
        if (names.isEmpty()) return null
        val first = names.first().substringBefore('/', missingDelimiterValue = "")
        if (first.isEmpty()) return null
        if (names.any { !it.startsWith("$first/") }) return null
        return if (names.contains("$first/manifest.json")) first else null
    }

    private fun folderOf(rel: String): String {
        val folder = rel.substringBeforeLast('/', "")
        return if (folder.isEmpty()) "top level" else "folder '$folder'"
    }
}
