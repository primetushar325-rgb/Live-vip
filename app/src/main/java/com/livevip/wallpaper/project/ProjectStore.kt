package com.livevip.wallpaper.project

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/** Summary shown in the library. */
data class ProjectSummary(
    val id: String,
    val name: String,
    val dir: File,
    val canvasWidth: Int,
    val canvasHeight: Int,
    val layerCount: Int,
    val updatedAt: Long,
) {
    val thumbnail: File? get() = listOf(File(dir, "preview.png"), File(dir, "background.png")).firstOrNull { it.isFile }
}

/** A validated project ready for editing or rendering. */
data class LoadedProject(
    val summary: ProjectSummary,
    val manifest: ProjectManifest,
    val tuning: ProjectTuning,
)

/** Outcome of an import. Both variants carry the validation report, so the screen can show every finding. */
sealed class ImportResult {
    abstract val report: ValidationReport

    data class Success(val project: ProjectSummary, override val report: ValidationReport) : ImportResult()
    data class Failure(val message: String, override val report: ValidationReport) : ImportResult()
}

/**
 * File-based project library under app-private storage:
 *   <root>/<id>/     extracted + validated project, plus meta.json and optional effects.json
 *   <staging>/       temporary extraction area (never read by the renderer)
 *   <cache>/         temporary copies of files chosen in the picker
 *
 * The constructor takes plain directories so the import pipeline can be unit tested on the JVM.
 * [openAsset] is only needed for the bundled sample.
 */
class ProjectStore(
    private val root: File,
    private val staging: File,
    private val cache: File,
    private val openAsset: ((String) -> InputStream)? = null,
) {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    init {
        root.mkdirs()
        staging.mkdirs()
        cache.mkdirs()
    }

    companion object {
        /** Production store under the app's private files and cache directories. */
        fun forContext(context: Context): ProjectStore {
            val app = context.applicationContext
            return ProjectStore(
                root = File(app.filesDir, "projects"),
                staging = File(app.filesDir, "staging"),
                cache = File(app.cacheDir, "imports"),
                openAsset = { name -> app.assets.open(name) },
            )
        }
    }

    /** Removes leftovers from interrupted imports. Call only at app start, before any import runs. */
    fun cleanupLeftovers() {
        staging.listFiles()?.forEach { it.deleteRecursively() }
        cache.listFiles()?.forEach { it.delete() }
    }

    fun projectDir(id: String): File {
        require(id.matches(Regex("^[0-9a-f-]{36}$"))) { "invalid project id" }
        return File(root, id)
    }

    fun list(): List<ProjectSummary> {
        return (root.listFiles() ?: emptyArray())
            .filter { it.isDirectory }
            .mapNotNull { dir -> runCatching { summaryOf(dir) }.getOrNull() }
            .sortedByDescending { it.updatedAt }
    }

    fun load(id: String): LoadedProject {
        val dir = projectDir(id)
        val validated = ProjectValidator.validate(dir)
        val tuning = loadTuning(dir, validated.manifest.defaults)
        return LoadedProject(summaryOf(dir), validated.manifest, tuning)
    }

    /**
     * Imports a file chosen with the system document picker.
     *
     * [displayName] is only used in messages. Acceptance depends on the contents: the file must be a
     * ZIP archive that passes [ProjectChecker]. [open] opens the picked URI's stream and is called once.
     * Nothing here throws: every outcome is an [ImportResult].
     */
    fun importFromUri(displayName: String?, open: () -> InputStream?): ImportResult {
        val temp = File(cache, "${UUID.randomUUID()}.part")
        return try {
            val opened = try {
                open()
            } catch (e: SecurityException) {
                throw ProjectException("Android did not allow reading the selected file. Choose it again from the file picker.")
            }
            val input = opened ?: throw ProjectException("Could not open the selected file")
            input.use { copyLimited(it, temp) }
            importTemp(temp, displayName)
        } catch (e: ProjectException) {
            failure(displayName, e.message ?: "Import failed")
        } catch (e: IOException) {
            failure(displayName, "Could not read the selected file (${e.javaClass.simpleName})")
        } catch (e: SecurityException) {
            failure(displayName, "Android did not allow reading the selected file. Choose it again from the file picker.")
        } catch (e: RuntimeException) {
            failure(displayName, "Import failed unexpectedly (${e.javaClass.simpleName}). Nothing was added to the library.")
        } finally {
            temp.delete()
        }
    }

    /** Checks a local copy and installs it only if the check passes. Every failure path returns a report. */
    private fun importTemp(temp: File, displayName: String?): ImportResult {
        val report = ProjectChecker.inspectArchive(temp, displayName).report
        if (!report.isImportable) {
            val message = summaryMessage(report)
            return ImportResult.Failure(message, report)
        }
        return try {
            ImportResult.Success(importArchive(temp), report)
        } catch (e: ProjectException) {
            val message = e.message ?: "Import failed"
            ImportResult.Failure(message, report.withFinding(Finding(Severity.ERROR, Section.CHECKER, Code.CHECK_FAILED, null, message)))
        }
    }

    /** One line for the first problem, with a count of the rest. The full list is in the report. */
    private fun summaryMessage(report: ValidationReport): String {
        val errors = report.errors
        val first = errors.firstOrNull()?.message ?: "This file cannot be imported."
        val more = errors.size - 1
        return if (more <= 0) first else "$first ($more more problem${if (more == 1) "" else "s"} listed in the report.)"
    }

    private fun failure(displayName: String?, message: String): ImportResult.Failure =
        ImportResult.Failure(message, ValidationReport.failure(displayName?.takeIf { it.isNotBlank() } ?: "selected file", message))

    private fun copyLimited(input: InputStream, dest: File) {
        dest.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                if (total > ProjectLimits.MAX_ARCHIVE_BYTES) {
                    throw ProjectException("Project file is larger than ${ProjectLimits.MAX_ARCHIVE_BYTES / (1024 * 1024)} MB")
                }
                output.write(buffer, 0, n)
            }
        }
    }

    /** Validates and installs an archive on disk. Throws [ProjectException] with a readable message. */
    fun importArchive(archive: File): ProjectSummary {
        val id = UUID.randomUUID().toString()
        val work = File(staging, id)
        work.mkdirs()
        try {
            ArchiveExtractor.extract(archive, work)
            val validated = ProjectValidator.validate(work)
            val target = File(root, id)
            val now = System.currentTimeMillis()
            File(work, "meta.json").writeText(
                gson.toJson(ProjectMeta(id = id, name = validated.manifest.name, createdAt = now, updatedAt = now)),
            )
            // Read the summary before the rename so a failure here leaves nothing in the library.
            val summary = summaryOf(work).copy(dir = target)
            if (!work.renameTo(target)) throw ProjectException("Could not save the project")
            return summary
        } catch (e: ProjectException) {
            throw e
        } catch (e: Exception) {
            throw ProjectException("Import failed: ${e.javaClass.simpleName}")
        } finally {
            work.deleteRecursively()
        }
    }

    /** Imports a bundled sample through the same checks as a picked file. */
    fun importBundledSample(name: String): ImportResult {
        val open = openAsset ?: return failure(name, "Bundled samples are not available in this build.")
        val temp = File(cache, "${UUID.randomUUID()}.mwproj")
        return try {
            open("samples/$name").use { input -> copyLimited(input, temp) }
            importTemp(temp, name)
        } catch (e: ProjectException) {
            failure(name, e.message ?: "Sample import failed")
        } catch (e: IOException) {
            failure(name, "Could not read the bundled sample (${e.javaClass.simpleName})")
        } finally {
            temp.delete()
        }
    }

    fun rename(id: String, newName: String): ProjectSummary {
        val clean = newName.trim()
        if (clean.isEmpty() || clean.length > 80) throw ProjectException("Name must be 1-80 characters")
        val dir = projectDir(id)
        val meta = readMeta(dir)
        File(dir, "meta.json").writeText(gson.toJson(meta.copy(name = clean, updatedAt = System.currentTimeMillis())))
        return summaryOf(dir)
    }

    fun duplicate(id: String): ProjectSummary {
        val src = projectDir(id)
        val newId = UUID.randomUUID().toString()
        val dst = File(root, newId)
        val tmp = File(staging, newId)
        try {
            src.copyRecursively(tmp, overwrite = true)
            val meta = readMeta(src)
            val now = System.currentTimeMillis()
            File(tmp, "meta.json").writeText(
                gson.toJson(meta.copy(id = newId, name = "${meta.name} (copy)".take(80), createdAt = now, updatedAt = now)),
            )
            if (!tmp.renameTo(dst)) throw ProjectException("Could not duplicate the project")
            return summaryOf(dst)
        } finally {
            tmp.deleteRecursively()
        }
    }

    fun delete(id: String) {
        projectDir(id).deleteRecursively()
    }

    fun saveTuning(id: String, tuning: ProjectTuning) {
        val dir = projectDir(id)
        val clean = ManifestParser.sanitize(tuning)
        File(dir, "effects.json").writeText(gson.toJson(ProjectTuningSave(motion = clean.motion, effects = clean.effects)))
        val meta = readMeta(dir)
        File(dir, "meta.json").writeText(gson.toJson(meta.copy(updatedAt = System.currentTimeMillis())))
    }

    fun resetTuning(id: String) {
        File(projectDir(id), "effects.json").delete()
    }

    private fun loadTuning(dir: File, defaults: ProjectTuning?): ProjectTuning {
        val file = File(dir, "effects.json")
        if (file.isFile) {
            val saved = runCatching { gson.fromJson(file.readText(), ProjectTuningSave::class.java) }.getOrNull()
            if (saved != null) {
                return ManifestParser.sanitize(ProjectTuning(saved.motion ?: MotionSettings(), saved.effects ?: EffectSettings()))
            }
        }
        return defaults ?: ProjectTuning()
    }

    private fun summaryOf(dir: File): ProjectSummary {
        val meta = readMeta(dir)
        val manifest = ManifestParser.parse(File(dir, "manifest.json").readText(Charsets.UTF_8))
        return ProjectSummary(
            id = dir.name,
            name = meta.name.ifEmpty { manifest.name },
            dir = dir,
            canvasWidth = manifest.canvasWidth,
            canvasHeight = manifest.canvasHeight,
            layerCount = manifest.layers.size,
            updatedAt = if (meta.updatedAt > 0) meta.updatedAt else dir.lastModified(),
        )
    }

    private fun readMeta(dir: File): ProjectMeta {
        val f = File(dir, "meta.json")
        return if (f.isFile) runCatching { gson.fromJson(f.readText(), ProjectMeta::class.java) }.getOrNull() ?: ProjectMeta(dir.name)
        else ProjectMeta(id = dir.name, name = "")
    }

    /** Persisted shape of effects.json (all fields optional on read). */
    data class ProjectTuningSave(
        val motion: MotionSettings? = null,
        val effects: EffectSettings? = null,
    )
}
