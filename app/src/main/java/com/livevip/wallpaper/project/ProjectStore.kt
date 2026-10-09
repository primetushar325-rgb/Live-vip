package com.livevip.wallpaper.project

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.io.IOException
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

sealed class ImportResult {
    data class Success(val project: ProjectSummary) : ImportResult()
    data class Failure(val message: String) : ImportResult()
}

/**
 * File-based project library under app-private storage:
 *   filesDir/projects/<id>/  extracted + validated project, plus meta.json and optional effects.json
 *   filesDir/staging/        temporary extraction area (never read by the renderer)
 */
class ProjectStore(context: Context) {
    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "projects")
    private val staging = File(appContext.filesDir, "staging")
    private val cache = File(appContext.cacheDir, "imports")
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    init {
        root.mkdirs()
        staging.mkdirs()
        cache.mkdirs()
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

    /** Imports a document picked with the system file picker. Copies first so the URI is not needed later. */
    fun importFromUri(context: Context, uri: Uri, displayName: String?): ImportResult {
        val temp = File(cache, "${UUID.randomUUID()}.mwproj")
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output ->
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
            } ?: throw ProjectException("Could not open the selected file")
            if (displayName != null && !displayName.lowercase().endsWith(".mwproj") && !displayName.lowercase().endsWith(".zip")) {
                throw ProjectException("Please select a .mwproj project file")
            }
            ImportResult.Success(importArchive(temp))
        } catch (e: ProjectException) {
            ImportResult.Failure(e.message ?: "Import failed")
        } catch (e: IOException) {
            ImportResult.Failure("Could not read the selected file")
        } finally {
            temp.delete()
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
            if (!work.renameTo(target)) throw ProjectException("Could not save the project")
            return summaryOf(target)
        } catch (e: ProjectException) {
            throw e
        } catch (e: Exception) {
            throw ProjectException("Import failed: ${e.javaClass.simpleName}")
        } finally {
            work.deleteRecursively()
        }
    }

    fun importBundledSample(name: String): ProjectSummary {
        val temp = File(cache, "${UUID.randomUUID()}.mwproj")
        try {
            appContext.assets.open("samples/$name").use { input ->
                temp.outputStream().use { input.copyTo(it) }
            }
            return importArchive(temp)
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
