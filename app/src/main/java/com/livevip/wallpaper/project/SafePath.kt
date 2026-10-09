package com.livevip.wallpaper.project

/** Rules for every path that comes from an archive or a manifest. */
object SafePath {
    /**
     * Returns a normalized relative path using '/' separators, or null if the name is unsafe.
     * Rejects absolute paths, drive letters, backslashes, NUL, empty / "." / ".." segments and
     * paths deeper than [ProjectLimits.MAX_PATH_DEPTH].
     */
    fun normalize(raw: String): String? {
        if (raw.isEmpty() || raw.length > 200) return null
        if (raw.startsWith("/") || raw.contains('\\') || raw.contains(':') || raw.contains('\u0000')) return null
        val parts = raw.split('/')
        if (parts.size > ProjectLimits.MAX_PATH_DEPTH) return null
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) return null
        if (parts.any { it.length > 80 || it.startsWith(".") }) return null
        return parts.joinToString("/")
    }

    fun extension(path: String): String = path.substringAfterLast('.', "").lowercase()
}
