package com.livevip.wallpaper.project

import java.io.File

/** A manifest whose referenced files were all found and checked. */
data class ValidatedProject(
    val manifest: ProjectManifest,
    val layers: List<LayerSpec>,   // with width/height filled from PNG headers
    val estimatedTextureBytes: Long,
)

/** Validates an extracted project directory. Reads PNG headers only; never decodes pixels here. */
object ProjectValidator {

    fun validate(dir: File): ValidatedProject {
        val manifestFile = File(dir, "manifest.json")
        if (!manifestFile.isFile) throw ProjectException("manifest.json is missing")
        if (manifestFile.length() > ProjectLimits.MAX_MANIFEST_BYTES) throw ProjectException("manifest.json is too large")
        val manifest = ManifestParser.parse(manifestFile.readText(Charsets.UTF_8))

        val canvasW = manifest.canvasWidth
        val canvasH = manifest.canvasHeight

        val bgSize = requirePng(dir, manifest.background, "background")
        if (bgSize.width != canvasW || bgSize.height != canvasH) {
            throw ProjectException("background.png must be exactly ${canvasW}x$canvasH (canvas size), found ${bgSize.width}x${bgSize.height}")
        }
        var bytes = bgSize.bytes()

        manifest.depth?.let { d ->
            val s = requirePng(dir, d, "background depth")
            if (s.width != canvasW || s.height != canvasH) {
                throw ProjectException("depth.png must match the canvas size ${canvasW}x$canvasH")
            }
            bytes += s.bytes()
        }

        // The preview is only a library thumbnail, so it is checked but not counted toward GPU memory.
        manifest.preview?.let { p -> requirePng(dir, p, "preview") }

        val layers = manifest.layers.map { layer ->
            val size = requirePng(dir, layer.file, "layer '${layer.id}'")
            if (layer.x >= canvasW || layer.y >= canvasH || layer.x + size.width <= 0 || layer.y + size.height <= 0) {
                throw ProjectException("layer '${layer.id}' is placed outside the canvas")
            }
            bytes += size.bytes()
            layer.mask?.let { m ->
                val ms = requirePng(dir, m, "mask of '${layer.id}'")
                if (ms.width != size.width || ms.height != size.height) {
                    throw ProjectException("mask for '${layer.id}' must be ${size.width}x${size.height} like its layer")
                }
                bytes += ms.bytes()
            }
            layer.glowMask?.let { g ->
                val gs = requirePng(dir, g, "glowMask of '${layer.id}'")
                if (gs.width != size.width || gs.height != size.height) {
                    throw ProjectException("glowMask for '${layer.id}' must be ${size.width}x${size.height} like its layer")
                }
                bytes += gs.bytes()
            }
            layer.copy(width = size.width, height = size.height)
        }

        if (bytes > ProjectLimits.MAX_TEXTURE_BYTES_TOTAL) {
            throw ProjectException("Images need about ${bytes / (1024 * 1024)} MB of GPU memory (limit ${ProjectLimits.MAX_TEXTURE_BYTES_TOTAL / (1024 * 1024)} MB). Crop layers to their bounding box.")
        }

        manifest.mesh?.let { m ->
            val f = File(dir, m)
            if (!f.isFile) throw ProjectException("mesh file is missing: $m")
            if (f.length() > ProjectLimits.MAX_MANIFEST_BYTES) throw ProjectException("mesh file is too large")
        }

        return ValidatedProject(manifest.copy(layers = layers), layers, bytes)
    }

    private fun requirePng(dir: File, relative: String, label: String): ImageDims {
        val safe = SafePath.normalize(relative) ?: throw ProjectException("Unsafe path for $label: $relative")
        val file = File(dir, safe).canonicalFile
        if (!file.path.startsWith(dir.canonicalPath + File.separator)) throw ProjectException("Unsafe path for $label")
        val size = try {
            PngHeader.read(file)
        } catch (e: ProjectException) {
            throw ProjectException("$label: ${e.message}")
        }
        if (size.width > ProjectLimits.MAX_IMAGE_SIDE || size.height > ProjectLimits.MAX_IMAGE_SIDE) {
            throw ProjectException("$label is ${size.width}x${size.height}; the maximum side is ${ProjectLimits.MAX_IMAGE_SIDE}px")
        }
        return ImageDims(size.width, size.height)
    }

    /** Pixel dimensions of an image; [bytes] is its RGBA8 texture footprint. */
    private class ImageDims(val width: Int, val height: Int) {
        fun bytes(): Long = width.toLong() * height * 4
    }
}
