package com.livevip.wallpaper.project

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** Where the checker reads project files from: an archive being inspected, or an extracted folder. */
interface ProjectSource {
    fun has(rel: String): Boolean
    fun size(rel: String): Long

    /** The first [count] bytes of a file, or fewer if the file is shorter. */
    fun head(rel: String, count: Int): ByteArray

    /** The whole file as UTF-8 text, or null when it is larger than [maxBytes]. */
    fun text(rel: String, maxBytes: Long): String?
    fun allFiles(): List<String>

    /** Paths that were refused for size reasons. They are already reported, so they are not "missing". */
    fun rejected(): Set<String> = emptySet()
}

/** Reads project files from an extracted folder. [ignoredTopLevel] files belong to the app, not the project. */
class DirSource(private val dir: File) : ProjectSource {
    private val files: Map<String, File> by lazy {
        dir.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(dir).path.replace(File.separatorChar, '/') to it }
            .filter { (rel, _) -> rel !in APP_FILES }
            .toMap()
    }

    override fun has(rel: String): Boolean = files.containsKey(rel)

    override fun size(rel: String): Long = files[rel]?.length() ?: 0L

    override fun head(rel: String, count: Int): ByteArray =
        files[rel]?.inputStream()?.use { readHead(it, count) } ?: ByteArray(0)

    override fun text(rel: String, maxBytes: Long): String? =
        files[rel]?.inputStream()?.use { readLimited(it, maxBytes) }?.toString(Charsets.UTF_8)

    override fun allFiles(): List<String> = files.keys.sorted()

    private companion object {
        /** Written by the app next to an extracted project. Not part of the archive contract. */
        val APP_FILES = setOf("meta.json", "effects.json")
    }
}

/** Reads project files from an open archive without unpacking them. */
class ZipSource(
    private val zip: ZipFile,
    private val entries: Map<String, ZipEntry>,
    private val rejectedNames: Set<String> = emptySet(),
) : ProjectSource {
    override fun has(rel: String): Boolean = entries.containsKey(rel)

    override fun rejected(): Set<String> = rejectedNames

    override fun size(rel: String): Long = entries[rel]?.size?.coerceAtLeast(0L) ?: 0L

    override fun head(rel: String, count: Int): ByteArray {
        val entry = entries[rel] ?: return ByteArray(0)
        return zip.getInputStream(entry).use { readHead(it, count) }
    }

    override fun text(rel: String, maxBytes: Long): String? {
        val entry = entries[rel] ?: return null
        return zip.getInputStream(entry).use { readLimited(it, maxBytes) }?.toString(Charsets.UTF_8)
    }

    override fun allFiles(): List<String> = entries.keys.sorted()
}

/** Pixel size of an image and its RGBA8 GPU footprint. */
data class ImageDims(val width: Int, val height: Int) {
    fun bytes(): Long = width.toLong() * height * 4
}

/** Result of a check: the report, plus the manifest and layers the renderer can use when there are no errors. */
class CheckOutcome(
    val report: ValidationReport,
    val manifest: ProjectManifest?,
    val layers: List<LayerSpec>,
    val textureBytes: Long,
)

internal fun readHead(input: InputStream, count: Int): ByteArray {
    val buf = ByteArray(count)
    var read = 0
    while (read < count) {
        val n = input.read(buf, read, count - read)
        if (n < 0) break
        read += n
    }
    return buf.copyOf(read)
}

/** Reads at most [maxBytes] bytes. Returns null when the stream holds more, without reading the rest. */
internal fun readLimited(input: InputStream, maxBytes: Long): ByteArray? {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(16 * 1024)
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        out.write(buf, 0, n)
        if (out.size() > maxBytes) return null
    }
    return out.toByteArray()
}

/**
 * Checks a .mwproj without importing it. It reads the archive in place and never writes to disk,
 * and it collects every problem rather than stopping at the first one. The import pipeline and the
 * library loader both rely on this, so they agree on what is valid.
 *
 * None of these functions throw for a bad project. Problems are recorded in the report.
 */
object ProjectChecker {

    /** Mutable results gathered during one check. */
    private class Collected {
        var manifest: ProjectManifest? = null
        var layers: List<LayerSpec> = emptyList()
        var texture: Long = 0L
    }

    /** Checks a picked file. [displayName] is used only in messages; it may be null. */
    fun inspectArchive(file: File, displayName: String?, limits: CheckLimits = CheckLimits.DEFAULT): CheckOutcome {
        val b = ReportBuilder()
        val c = Collected()
        val name = displayName?.takeIf { it.isNotBlank() } ?: "selected file"
        try {
            inspectArchiveInto(file, displayName, limits, b, c)
        } catch (e: ProjectException) {
            b.addError(Section.FILE, Code.CHECK_FAILED, null, e.message ?: "The file could not be checked.")
        } catch (e: Exception) {
            b.addError(Section.CHECKER, Code.CHECK_FAILED, null, "The checker stopped unexpectedly (${e.javaClass.simpleName}). Nothing was imported.")
        } catch (e: StackOverflowError) {
            b.addError(Section.CHECKER, Code.CHECK_FAILED, null, "The checker stopped because the file is too deeply nested. Nothing was imported.")
        }
        return CheckOutcome(b.build(name), c.manifest, c.layers, c.texture)
    }

    /** Checks an already extracted project folder. Used when a project is loaded from the library. */
    fun inspectDirectory(dir: File, limits: CheckLimits = CheckLimits.DEFAULT): CheckOutcome {
        val b = ReportBuilder()
        val c = Collected()
        try {
            checkProject(DirSource(dir), 0L, b, limits, c)
        } catch (e: Exception) {
            b.addError(Section.CHECKER, Code.CHECK_FAILED, null, "The checker stopped unexpectedly (${e.javaClass.simpleName}).")
        } catch (e: StackOverflowError) {
            b.addError(Section.CHECKER, Code.CHECK_FAILED, null, "The checker stopped because the project is too deeply nested.")
        }
        return CheckOutcome(b.build(dir.name), c.manifest, c.layers, c.texture)
    }

    private fun inspectArchiveInto(file: File, displayName: String?, limits: CheckLimits, b: ReportBuilder, c: Collected) {
        val who = displayName?.takeIf { it.isNotBlank() }?.let { "'$it'" } ?: "The selected file"
        if (!file.isFile) {
            b.addError(Section.FILE, Code.CHECK_FAILED, null, "$who could not be read.")
            return
        }
        val size = file.length()
        if (size == 0L) {
            b.addError(Section.FILE, Code.FILE_EMPTY, null, "$who is empty. Export the project again.")
            return
        }
        if (size > limits.maxArchiveBytes) {
            b.addError(
                Section.FILE, Code.FILE_TOO_LARGE, null,
                "$who is ${formatSize(size)}; the limit is ${formatSize(limits.maxArchiveBytes)}.",
                "Remove unused images or reduce their size.",
            )
            return
        }
        val kind = FileSniffer.kindOf(file)
        if (kind != DetectedKind.ZIP) {
            b.addError(
                Section.FILE, Code.FILE_NOT_ZIP, null,
                FileSniffer.describe(kind, displayName),
                "Export the project as a ZIP archive named .mwproj.",
            )
            return
        }
        val zip = try {
            ZipFile(file)
        } catch (e: Exception) {
            b.addError(
                Section.FILE, Code.ZIP_MALFORMED, null,
                "$who is not a valid .mwproj (ZIP) archive: its ZIP structure is damaged or incomplete.",
                "Export the project again. If you made the archive by hand, use a standard ZIP tool.",
            )
            return
        }
        zip.use { z ->
            val entries = ArrayList<ZipEntry>()
            val en = z.entries()
            while (en.hasMoreElements()) entries.add(en.nextElement())

            val plan = ArchiveRules.plan(entries, limits, b)
            val map = LinkedHashMap<String, ZipEntry>()
            for (p in plan.files) map[p.rel] = p.entry
            checkProject(ZipSource(z, map, plan.rejected), size, b, limits, c, plan.declaredBytes)
        }
    }

    /** Checks manifest.json and every file it references. Shared by archives and folders. */
    private fun checkProject(
        src: ProjectSource,
        archiveBytes: Long,
        b: ReportBuilder,
        limits: CheckLimits,
        c: Collected,
        declaredBytes: Long? = null,
    ) {
        val files = src.allFiles()
        if (!src.has("manifest.json")) {
            // A manifest refused for its size is already reported; do not add a second error for it.
            if ("manifest.json" !in src.rejected()) {
                if (files.isEmpty()) {
                    b.addError(
                        Section.MANIFEST, Code.MANIFEST_MISSING, "manifest.json",
                        "The archive contains no project files. manifest.json is missing.",
                        "Put manifest.json at the top level of the archive.",
                    )
                } else {
                    b.addError(
                        Section.MANIFEST, Code.MANIFEST_MISSING, "manifest.json",
                        "manifest.json is missing from the archive. It must be at the top level, or inside the one folder that holds all project files.",
                        "Add manifest.json. The checklist lists its required fields.",
                    )
                }
            }
            return
        }
        val text = src.text("manifest.json", limits.maxManifestBytes)
        if (text == null) {
            b.addError(
                Section.MANIFEST, Code.MANIFEST_TOO_LARGE, "manifest.json",
                "manifest.json is larger than ${formatSize(limits.maxManifestBytes)}.",
            )
            return
        }
        b.addPass(Section.MANIFEST, "manifest.json", "manifest.json was found and read")
        val manifest = ManifestParser.check(text, b, limits) ?: return

        val assets = checkAssets(src, manifest, b, limits)
        c.manifest = assets.manifest
        c.layers = assets.layers
        c.texture = assets.texture

        val unpacked = declaredBytes ?: files.sumOf { maxOf(src.size(it), 0L) }
        b.stats = ReportStats(
            archiveBytes = archiveBytes,
            fileCount = files.size,
            unpackedBytes = unpacked,
            canvasWidth = manifest.canvasWidth,
            canvasHeight = manifest.canvasHeight,
            layerCount = assets.layers.size,
            textureBytes = assets.texture,
        )
    }

    private class AssetResult(val manifest: ProjectManifest, val layers: List<LayerSpec>, val texture: Long)

    private fun checkAssets(src: ProjectSource, m: ProjectManifest, b: ReportBuilder, limits: CheckLimits): AssetResult {
        val cw = m.canvasWidth
        val ch = m.canvasHeight
        val referenced = HashSet<String>()
        referenced.add("manifest.json")
        var texture = 0L

        // Background is required and must match the canvas exactly.
        val bgDims = checkImage(src, b, limits, m.background, "background", required = true, optionalHint = "", referenced = referenced)
        if (bgDims != null) {
            if (bgDims.width != cw || bgDims.height != ch) {
                b.addError(
                    Section.ASSETS, Code.SIZE_MISMATCH, m.background,
                    "${m.background} must be exactly ${cw}x$ch (canvas size), found ${bgDims.width}x${bgDims.height}.",
                    "Export the background at ${cw}x$ch px.",
                )
            } else {
                texture += bgDims.bytes()
                b.addPass(Section.ASSETS, m.background, "${m.background} is ${cw}x$ch px, matching the canvas")
            }
        }

        // Optional images. A missing one is ignored, so it is cleared from the manifest the renderer uses.
        var depthKept: String? = null
        m.depth?.let { d ->
            val dd = checkImage(src, b, limits, d, "depth map", required = false, optionalHint = "The background uses a flat depth instead.", referenced = referenced)
            if (dd != null) {
                if (dd.width != cw || dd.height != ch) {
                    b.addError(
                        Section.ASSETS, Code.SIZE_MISMATCH, d,
                        "$d must match the canvas size ${cw}x$ch, found ${dd.width}x${dd.height}.",
                    )
                } else {
                    depthKept = d
                    texture += dd.bytes()
                    b.addPass(Section.ASSETS, d, "$d is the depth map (${cw}x$ch px)")
                }
            }
        }

        var previewKept: String? = null
        m.preview?.let { p ->
            val pd = checkImage(src, b, limits, p, "preview image", required = false, optionalHint = "The library thumbnail falls back to the background.", referenced = referenced)
            if (pd != null) {
                previewKept = p
                b.addPass(Section.ASSETS, p, "$p is the library preview (${pd.width}x${pd.height} px, not counted for GPU memory)")
            }
        }

        val layers = ArrayList<LayerSpec>()
        for (layer in m.layers) {
            val label = "layer '${layer.id}'"
            val d = checkImage(src, b, limits, layer.file, label, required = true, optionalHint = "", referenced = referenced)
            if (d == null) {
                layers.add(layer)
                continue
            }
            val outside = layer.x >= cw || layer.y >= ch || layer.x + d.width <= 0 || layer.y + d.height <= 0
            if (outside) {
                b.addError(
                    Section.ASSETS, Code.LAYER_OFFSCREEN, layer.file,
                    "$label is placed at (${layer.x}, ${layer.y}) and does not overlap the ${cw}x$ch canvas.",
                    "Move the layer so it overlaps the canvas, or change its x and y.",
                )
            } else {
                b.addPass(Section.ASSETS, layer.file, "$label: ${layer.file} is ${d.width}x${d.height} px at (${layer.x}, ${layer.y})")
            }
            texture += d.bytes()

            var maskKept: String? = null
            layer.mask?.let { mk ->
                val md = checkImage(src, b, limits, mk, "mask of $label", required = false, optionalHint = "The layer moves as one piece.", referenced = referenced)
                if (md != null) {
                    if (md.width != d.width || md.height != d.height) {
                        b.addError(
                            Section.ASSETS, Code.SIZE_MISMATCH, mk,
                            "mask for $label must be ${d.width}x${d.height} like its layer, found ${md.width}x${md.height}.",
                        )
                    } else {
                        maskKept = mk
                        texture += md.bytes()
                    }
                }
            }
            var glowKept: String? = null
            layer.glowMask?.let { gk ->
                val gd = checkImage(src, b, limits, gk, "glow mask of $label", required = false, optionalHint = "The glow effect is skipped for this layer.", referenced = referenced)
                if (gd != null) {
                    if (gd.width != d.width || gd.height != d.height) {
                        b.addError(
                            Section.ASSETS, Code.SIZE_MISMATCH, gk,
                            "glow mask for $label must be ${d.width}x${d.height} like its layer, found ${gd.width}x${gd.height}.",
                        )
                    } else {
                        glowKept = gk
                        texture += gd.bytes()
                    }
                }
            }
            layers.add(layer.copy(width = d.width, height = d.height, mask = maskKept, glowMask = glowKept))
        }

        // Mesh is reserved: it is checked, but not rendered in format version 1.
        var meshKept: String? = null
        m.mesh?.let { mesh ->
            referenced.add(mesh)
            if (!src.has(mesh)) {
                b.addWarning(
                    Section.ASSETS, Code.OPTIONAL_MISSING, mesh,
                    "mesh '$mesh' is not in the archive. It is optional, so it is ignored.",
                )
            } else if (src.size(mesh) > limits.maxManifestBytes) {
                b.addError(Section.ASSETS, Code.ENTRY_TOO_LARGE, mesh, "mesh file '$mesh' is larger than ${formatSize(limits.maxManifestBytes)}.")
            } else {
                meshKept = mesh
                b.addInfo(
                    Section.ASSETS, Code.RESERVED_FEATURE, mesh,
                    "mesh is reserved in format version 1: it is checked but not rendered yet.",
                )
            }
        }

        for (unused in src.allFiles().filter { it !in referenced }) {
            b.addInfo(
                Section.CONTENTS, Code.UNUSED_FILE, unused,
                "'$unused' is in the archive but no manifest.json field uses it. It is ignored.",
                "Remove it, or reference it from manifest.json.",
            )
        }

        if (texture > limits.maxTextureBytes) {
            b.addError(
                Section.LIMITS, Code.GPU_BUDGET, null,
                "Images need about ${formatSize(texture)} of GPU memory (limit ${formatSize(limits.maxTextureBytes)}).",
                "Crop layers to their content, or reduce the background resolution.",
            )
        } else {
            b.addPass(Section.LIMITS, null, "Images need about ${formatSize(texture)} of GPU memory (limit ${formatSize(limits.maxTextureBytes)})")
        }

        val kept = m.copy(depth = depthKept, preview = previewKept, mesh = meshKept, layers = layers)
        return AssetResult(kept, layers, texture)
    }

    /**
     * Checks one referenced PNG. Returns its size, or null when it cannot be used. A missing required
     * file is an error. A missing optional file is a warning.
     */
    private fun checkImage(
        src: ProjectSource,
        b: ReportBuilder,
        limits: CheckLimits,
        rel: String,
        label: String,
        required: Boolean,
        optionalHint: String,
        referenced: MutableSet<String>,
    ): ImageDims? {
        referenced.add(rel)
        if (!src.has(rel)) {
            val similar = src.allFiles().firstOrNull { it.equals(rel, ignoreCase = true) }
            val caseNote = if (similar != null) {
                " The archive contains '$similar', but file names are case-sensitive: change manifest.json or rename the file so they match."
            } else {
                ""
            }
            if (rel in src.rejected()) return null // already reported as too large or unsafe
            if (required) {
                b.addError(
                    Section.ASSETS, Code.MISSING_ASSET, rel,
                    "$label refers to '$rel', which is not in the archive.$caseNote",
                    "Add '$rel' to the archive with exactly this path, or correct the path in manifest.json.",
                )
            } else {
                b.addWarning(
                    Section.ASSETS, Code.OPTIONAL_MISSING, rel,
                    "$label refers to '$rel', which is not in the archive. It is optional, so it is ignored. " +
                        "$optionalHint$caseNote".trim(),
                )
            }
            return null
        }
        val size = src.size(rel)
        if (size > limits.maxEntryBytes) {
            b.addError(
                Section.ASSETS, Code.ENTRY_TOO_LARGE, rel,
                "$label file '$rel' is ${formatSize(size)}; the limit is ${formatSize(limits.maxEntryBytes)} per file.",
            )
            return null
        }
        val header = try {
            src.head(rel, 24)
        } catch (e: IOException) {
            b.addError(Section.ASSETS, Code.INVALID_IMAGE, rel, "$label file '$rel' could not be read (${e.javaClass.simpleName}).")
            return null
        }
        val png = try {
            PngHeader.parse(rel, header)
        } catch (e: ProjectException) {
            b.addError(
                Section.ASSETS, Code.INVALID_IMAGE, rel,
                "$label: ${e.message}",
                "Export '$rel' again as a PNG image.",
            )
            return null
        }
        if (png.width > limits.maxImageSide || png.height > limits.maxImageSide) {
            b.addError(
                Section.ASSETS, Code.IMAGE_TOO_LARGE, rel,
                "$label file '$rel' is ${png.width}x${png.height} px; the maximum side is ${limits.maxImageSide} px.",
                "Scale or crop the image.",
            )
            return null
        }
        return ImageDims(png.width, png.height)
    }
}
