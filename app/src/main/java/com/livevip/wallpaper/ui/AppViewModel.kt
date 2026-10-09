package com.livevip.wallpaper.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.livevip.wallpaper.project.AppPrefs
import com.livevip.wallpaper.project.EffectSettings
import com.livevip.wallpaper.project.ImportResult
import com.livevip.wallpaper.project.LoadedProject
import com.livevip.wallpaper.project.ManifestParser
import com.livevip.wallpaper.project.MotionSettings
import com.livevip.wallpaper.project.ProjectStore
import com.livevip.wallpaper.project.ProjectSummary
import com.livevip.wallpaper.project.ValidationReport
import com.livevip.wallpaper.project.ProjectTuning
import com.livevip.wallpaper.project.QualityMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the Import screen shows: whether a check is running, the last report, and the name if it was added. */
data class ImportState(
    val busy: Boolean = false,
    val report: ValidationReport? = null,
    val importedName: String? = null,
)

/** Holds library, editor and settings state. All disk work runs on Dispatchers.IO. */
class AppViewModel(application: Application) : AndroidViewModel(application) {
    val store = ProjectStore.forContext(application)
    val prefs = AppPrefs(application)

    private val _projects = MutableStateFlow<List<ProjectSummary>>(emptyList())
    val projects: StateFlow<List<ProjectSummary>> = _projects.asStateFlow()

    private val _current = MutableStateFlow<LoadedProject?>(null)
    val current: StateFlow<LoadedProject?> = _current.asStateFlow()

    private val _tuning = MutableStateFlow(ProjectTuning())
    val tuning: StateFlow<ProjectTuning> = _tuning.asStateFlow()

    private val _dirty = MutableStateFlow(false)
    val dirty: StateFlow<Boolean> = _dirty.asStateFlow()

    private var savedTuning = ProjectTuning()

    private val _quality = MutableStateFlow(prefs.quality)
    val quality: StateFlow<QualityMode> = _quality.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _importState = MutableStateFlow(ImportState())
    val importState: StateFlow<ImportState> = _importState.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            store.cleanupLeftovers()
            if (!prefs.sampleImported) {
                when (val sample = safely { store.importBundledSample(SAMPLE_ASSET) }) {
                    is ImportResult.Success -> {
                        prefs.sampleImported = true
                        if (prefs.activeProjectId == null) prefs.activeProjectId = sample.project.id
                    }
                    is ImportResult.Failure -> _message.value = "Bundled sample could not be imported: ${sample.message}"
                }
            }
            _projects.value = store.list()
        }
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) { _projects.value = store.list() }
    }

    /** Checks the picked file on a background thread and publishes the report. Nothing is uploaded. */
    fun importUri(uri: Uri) {
        _importState.value = ImportState(busy = true)
        viewModelScope.launch(Dispatchers.IO) {
            // The name is only used for messages. A provider that fails this query must not stop the import.
            val name = runCatching { displayName(uri) }.getOrNull()
            val resolver = getApplication<Application>().contentResolver
            publish(safely { store.importFromUri(name) { resolver.openInputStream(uri) } })
        }
    }

    fun clearImportState() {
        _importState.value = ImportState()
    }

    /** Checks and imports the bundled sample, using the same rules as a picked file. */
    fun importBundledSample() {
        _importState.value = ImportState(busy = true)
        viewModelScope.launch(Dispatchers.IO) {
            publish(safely { store.importBundledSample(SAMPLE_ASSET) })
        }
    }

    private fun publish(result: ImportResult) {
        when (result) {
            is ImportResult.Success -> {
                _projects.value = store.list()
                if (prefs.activeProjectId == null) {
                    prefs.activeProjectId = result.project.id
                    prefs.bumpRevision()
                }
                _importState.value = ImportState(report = result.report, importedName = result.project.name)
            }
            is ImportResult.Failure -> _importState.value = ImportState(report = result.report)
        }
    }

    /** Runs an import and turns any unexpected exception into a failed result, so the screen never crashes. */
    private inline fun safely(block: () -> ImportResult): ImportResult =
        try {
            block()
        } catch (e: Exception) {
            val text = "Import failed unexpectedly (${e.javaClass.simpleName}). Nothing was added to the library."
            ImportResult.Failure(text, ValidationReport.failure("selected file", text))
        }

    private fun displayName(uri: Uri): String? =
        getApplication<Application>().contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

    /** Loads a project from disk into the editor. Unsaved edits are discarded. */
    fun open(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val loaded = store.load(id)
                savedTuning = loaded.tuning
                _tuning.value = loaded.tuning
                _dirty.value = false
                _current.value = loaded
            } catch (e: Exception) {
                _current.value = null
                _message.value = "Could not open project: ${e.message ?: "unknown error"}"
            }
        }
    }

    fun updateMotion(change: (MotionSettings) -> MotionSettings) {
        val t = _tuning.value
        applyTuning(t.copy(motion = change(t.motion)))
    }

    fun updateEffects(change: (EffectSettings) -> EffectSettings) {
        val t = _tuning.value
        applyTuning(t.copy(effects = change(t.effects)))
    }

    private fun applyTuning(next: ProjectTuning) {
        val clean = ManifestParser.sanitize(next)
        _tuning.value = clean
        _dirty.value = clean != savedTuning
    }

    /** Resets the editor to the project's own defaults (from manifest.json). Not saved until [save]. */
    fun resetToProjectDefaults() {
        applyTuning(_current.value?.manifest?.defaults ?: ProjectTuning())
    }

    fun save() {
        val project = _current.value ?: return
        val tuning = _tuning.value
        viewModelScope.launch(Dispatchers.IO) {
            try {
                store.saveTuning(project.summary.id, tuning)
                savedTuning = tuning
                _dirty.value = false
                prefs.bumpRevision()
                _projects.value = store.list()
                _message.value = "Saved. Live wallpaper will pick up the changes."
            } catch (e: Exception) {
                _message.value = "Could not save: ${e.message ?: "unknown error"}"
            }
        }
    }

    fun rename(id: String, name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                store.rename(id, name)
                _projects.value = store.list()
            } catch (e: Exception) {
                _message.value = e.message ?: "Rename failed"
            }
        }
    }

    fun duplicate(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                store.duplicate(id)
                _projects.value = store.list()
            } catch (e: Exception) {
                _message.value = e.message ?: "Duplicate failed"
            }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            store.delete(id)
            if (prefs.activeProjectId == id) {
                prefs.activeProjectId = store.list().firstOrNull { it.id != id }?.id
            }
            if (_current.value?.summary?.id == id) _current.value = null
            prefs.bumpRevision()
            _projects.value = store.list()
        }
    }

    /** Makes [id] the project the live wallpaper shows. The system still has to confirm applying it. */
    fun setActive(id: String) {
        prefs.activeProjectId = id
        prefs.bumpRevision()
    }

    fun setQuality(mode: QualityMode) {
        prefs.quality = mode
        _quality.value = mode
        prefs.bumpRevision()
    }

    fun clearMessage() {
        _message.value = null
    }

    companion object {
        const val SAMPLE_ASSET = "neon_warrior.mwproj"
    }
}
