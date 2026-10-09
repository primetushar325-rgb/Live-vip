package com.livevip.wallpaper.project

import java.io.File

/** A manifest whose referenced files were all found and checked. */
data class ValidatedProject(
    val manifest: ProjectManifest,
    val layers: List<LayerSpec>,   // with width/height filled from PNG headers
    val estimatedTextureBytes: Long,
)

/**
 * Validates an extracted project directory. It delegates to [ProjectChecker], so the rules match the
 * import report. Throws [ProjectException] with the first error. Warnings (such as a missing optional
 * file) do not throw.
 */
object ProjectValidator {

    fun validate(dir: File): ValidatedProject {
        val outcome = ProjectChecker.inspectDirectory(dir)
        outcome.report.errors.firstOrNull()?.let { throw ProjectException(it.message) }
        val manifest = outcome.manifest ?: throw ProjectException("manifest.json could not be read")
        return ValidatedProject(manifest.copy(layers = outcome.layers), outcome.layers, outcome.textureBytes)
    }
}
