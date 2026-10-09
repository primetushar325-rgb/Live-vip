package com.livevip.wallpaper.project

import java.util.Locale

/** How serious a finding is. Only [ERROR] blocks an import. */
enum class Severity(val label: String) {
    ERROR("Error"),
    WARNING("Warning"),
    INFO("Note"),
    PASS("OK"),
}

/** Where a finding is shown in the report. */
enum class Section(val title: String) {
    FILE("Archive file"),
    CONTENTS("Archive contents"),
    MANIFEST("manifest.json"),
    ASSETS("Images and referenced files"),
    LIMITS("Size and resource limits"),
    CHECKER("Checker"),
}

/** Stable finding codes. The text can change; these do not. */
object Code {
    const val OK = "OK"

    // Archive file and contents
    const val FILE_EMPTY = "FILE_EMPTY"
    const val FILE_TOO_LARGE = "FILE_TOO_LARGE"
    const val FILE_NOT_ZIP = "FILE_NOT_ZIP"
    const val ZIP_MALFORMED = "ZIP_MALFORMED"
    const val UNSAFE_PATH = "UNSAFE_PATH"
    const val DUPLICATE_PATH = "DUPLICATE_PATH"
    const val UNSUPPORTED_FILE = "UNSUPPORTED_FILE"
    const val IGNORED_METADATA = "IGNORED_METADATA"
    const val WRAPPER_FOLDER = "WRAPPER_FOLDER"
    const val UNUSED_FILE = "UNUSED_FILE"

    // Limits
    const val TOO_MANY_FILES = "TOO_MANY_FILES"
    const val ENTRY_TOO_LARGE = "ENTRY_TOO_LARGE"
    const val UNPACKED_TOO_LARGE = "UNPACKED_TOO_LARGE"
    const val SUSPICIOUS_COMPRESSION = "SUSPICIOUS_COMPRESSION"
    const val GPU_BUDGET = "GPU_BUDGET"

    // manifest.json
    const val MANIFEST_MISSING = "MANIFEST_MISSING"
    const val MANIFEST_EMPTY = "MANIFEST_EMPTY"
    const val MANIFEST_TOO_LARGE = "MANIFEST_TOO_LARGE"
    const val INVALID_JSON = "INVALID_JSON"
    const val JSON_TOO_DEEP = "JSON_TOO_DEEP"
    const val NOT_AN_OBJECT = "NOT_AN_OBJECT"
    const val MISSING_FIELD = "MISSING_FIELD"
    const val INVALID_FIELD = "INVALID_FIELD"
    const val UNKNOWN_FIELD = "UNKNOWN_FIELD"
    const val UNSUPPORTED_FORMAT = "UNSUPPORTED_FORMAT"
    const val UNSUPPORTED_VERSION = "UNSUPPORTED_VERSION"
    const val UNSUPPORTED_EXTENSION = "UNSUPPORTED_EXTENSION"
    const val NO_LAYERS = "NO_LAYERS"
    const val RESERVED_FEATURE = "RESERVED_FEATURE"

    // Referenced images and files
    const val MISSING_ASSET = "MISSING_ASSET"
    const val OPTIONAL_MISSING = "OPTIONAL_MISSING"
    const val INVALID_IMAGE = "INVALID_IMAGE"
    const val IMAGE_TOO_LARGE = "IMAGE_TOO_LARGE"
    const val SIZE_MISMATCH = "SIZE_MISMATCH"
    const val LAYER_OFFSCREEN = "LAYER_OFFSCREEN"

    const val CHECK_FAILED = "CHECK_FAILED"

    /** Codes for required items that are absent. These form the "missing items" list. */
    val MISSING = setOf(MANIFEST_MISSING, MISSING_FIELD, MISSING_ASSET)
}

/** One thing the checker found, with the exact path or field it refers to. */
data class Finding(
    val severity: Severity,
    val section: Section,
    val code: String,
    val path: String?,
    val message: String,
    val fix: String? = null,
)

/** Numbers describing the project that was checked. */
data class ReportStats(
    val archiveBytes: Long,
    val fileCount: Int,
    val unpackedBytes: Long,
    val canvasWidth: Int,
    val canvasHeight: Int,
    val layerCount: Int,
    val textureBytes: Long,
)

/** The complete result of checking one file. Immutable once built. */
data class ValidationReport(
    val fileName: String,
    val findings: List<Finding>,
    val stats: ReportStats? = null,
) {
    val errors: List<Finding> get() = findings.filter { it.severity == Severity.ERROR }
    val warnings: List<Finding> get() = findings.filter { it.severity == Severity.WARNING }
    val passed: List<Finding> get() = findings.filter { it.severity == Severity.PASS }

    /** True when nothing blocks the import. Warnings do not block it. */
    val isImportable: Boolean get() = errors.isEmpty()

    fun requiredMissing(): List<Finding> = errors.filter { it.code in Code.MISSING }

    fun optionalMissing(): List<Finding> = warnings.filter { it.code == Code.OPTIONAL_MISSING }

    fun unsupportedFiles(): List<Finding> =
        errors.filter { it.code == Code.UNSUPPORTED_FILE || it.code == Code.UNSUPPORTED_EXTENSION }

    fun otherErrors(): List<Finding> {
        val missing = requiredMissing().toSet()
        val unsupported = unsupportedFiles().toSet()
        return errors.filter { it !in missing && it !in unsupported }
    }

    /** Warnings other than missing optional files, plus informational notes. */
    fun notes(): List<Finding> =
        findings.filter { it.severity == Severity.INFO || (it.severity == Severity.WARNING && it.code != Code.OPTIONAL_MISSING) }

    fun withFinding(finding: Finding): ValidationReport = copy(findings = findings + finding)

    companion object {
        /** A report for a failure that happened before the file could be checked. */
        fun failure(fileName: String, message: String, code: String = Code.CHECK_FAILED): ValidationReport =
            ValidationReport(fileName, listOf(Finding(Severity.ERROR, Section.FILE, code, null, message)))
    }
}

/** Collects findings while a check runs. Never throws for a bad project; it records the problem instead. */
class ReportBuilder {
    private val items = ArrayList<Finding>()
    var stats: ReportStats? = null

    fun add(finding: Finding) {
        items.add(finding)
    }

    fun addError(section: Section, code: String, path: String?, message: String, fix: String? = null) {
        items.add(Finding(Severity.ERROR, section, code, path, message, fix))
    }

    fun addWarning(section: Section, code: String, path: String?, message: String, fix: String? = null) {
        items.add(Finding(Severity.WARNING, section, code, path, message, fix))
    }

    fun addInfo(section: Section, code: String, path: String?, message: String, fix: String? = null) {
        items.add(Finding(Severity.INFO, section, code, path, message, fix))
    }

    fun addPass(section: Section, path: String?, message: String) {
        items.add(Finding(Severity.PASS, section, Code.OK, path, message))
    }

    fun firstError(): Finding? = items.firstOrNull { it.severity == Severity.ERROR }

    fun hasErrors(): Boolean = firstError() != null

    fun build(fileName: String): ValidationReport = ValidationReport(fileName, items.toList(), stats)
}

/** Human-readable sizes for messages and reports. */
fun formatSize(bytes: Long): String = when {
    bytes < 1024L * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
}

/** Text the user can copy: the whole report, or only what is missing. */
object ReportText {

    fun headline(report: ValidationReport): String = when {
        !report.isImportable -> "NOT IMPORTED: ${report.errors.size} problem(s) must be fixed first."
        report.warnings.isNotEmpty() -> "VALID WITH WARNINGS: ready to import (${report.warnings.size} warning(s), nothing blocks the import)."
        else -> "VALID: ready to import."
    }

    fun statsLine(stats: ReportStats): String =
        "${stats.fileCount} files, archive ${formatSize(stats.archiveBytes)}, unpacked ${formatSize(stats.unpackedBytes)}, " +
            "canvas ${stats.canvasWidth}x${stats.canvasHeight}, ${stats.layerCount} layer(s), " +
            "image memory about ${formatSize(stats.textureBytes)}"

    /** The list to fix: required items that are missing, optional files not included, and unsupported files. */
    fun missingItems(report: ValidationReport): String = buildString {
        appendLine("Missing items for ${report.fileName}")
        appendLine()
        val required = report.requiredMissing()
        appendLine("Required and missing (${required.size}):")
        if (required.isEmpty()) appendLine("  none") else required.forEach { appendLine("  - ${label(it)}: ${it.message}") }
        val optional = report.optionalMissing()
        if (optional.isNotEmpty()) {
            appendLine()
            appendLine("Optional, not included (ignored, ${optional.size}):")
            optional.forEach { appendLine("  - ${label(it)}") }
        }
        val unsupported = report.unsupportedFiles()
        if (unsupported.isNotEmpty()) {
            appendLine()
            appendLine("Unsupported files to remove or convert (${unsupported.size}):")
            unsupported.forEach { appendLine("  - ${label(it)}: ${it.message}") }
        }
    }

    /** Everything the checker found, grouped by section, with fixes. */
    fun fullReport(report: ValidationReport): String = buildString {
        appendLine("Live VIP project check")
        appendLine("File: ${report.fileName}")
        appendLine("Result: ${headline(report)}")
        report.stats?.let { appendLine("Summary: ${statsLine(it)}") }
        appendLine("Nothing was uploaded. The check ran on this device.")
        for (section in Section.values()) {
            val items = report.findings.filter { it.section == section }
            if (items.isEmpty()) continue
            appendLine()
            appendLine("== ${section.title} ==")
            for (f in items) {
                appendLine("[${f.severity.label}] ${f.code}${f.path?.let { " ($it)" } ?: ""}")
                appendLine("    ${f.message}")
                f.fix?.let { appendLine("    Fix: $it") }
            }
        }
    }

    private fun label(f: Finding): String = f.path ?: f.section.title
}
