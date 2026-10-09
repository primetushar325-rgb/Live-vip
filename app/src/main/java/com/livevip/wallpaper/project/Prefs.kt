package com.livevip.wallpaper.project

import android.content.Context
import android.content.SharedPreferences

/** Render quality presets. Renderer scale, frame cap, particle density and glow sample count. */
enum class QualityMode(val label: String, val renderScale: Float, val maxFps: Int, val particleScale: Float, val glowSamples: Int, val description: String) {
    LOW("Low", 0.5f, 30, 0.35f, 4, "Half-resolution render, 30 FPS, fewer particles, lighter glow. Best for battery saving."),
    BALANCED("Balanced", 0.75f, 60, 0.7f, 8, "75% render resolution, up to 60 FPS, medium particle count and glow."),
    HIGH("High Quality", 1f, 60, 1f, 12, "Native resolution, up to 60 FPS, full particles and glow. Needs a capable GPU."),
}

/** Small app-wide settings store (no analytics, no network). */
class AppPrefs(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("livevip_prefs", Context.MODE_PRIVATE)

    var quality: QualityMode
        get() = runCatching { QualityMode.valueOf(prefs.getString(KEY_QUALITY, null) ?: "BALANCED") }
            .getOrDefault(QualityMode.BALANCED)
        set(value) = prefs.edit().putString(KEY_QUALITY, value.name).apply()

    var activeProjectId: String?
        get() = prefs.getString(KEY_ACTIVE, null)
        set(value) = prefs.edit().putString(KEY_ACTIVE, value).apply()

    var sampleImported: Boolean
        get() = prefs.getBoolean(KEY_SAMPLE, false)
        set(value) = prefs.edit().putBoolean(KEY_SAMPLE, value).apply()

    /** Bumped whenever a project's tuning or the active project changes, so the live wallpaper reloads. */
    fun bumpRevision() {
        prefs.edit().putLong(KEY_REVISION, System.currentTimeMillis()).apply()
    }

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(listener)

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(listener)

    companion object {
        const val KEY_QUALITY = "quality"
        const val KEY_ACTIVE = "active_project"
        const val KEY_SAMPLE = "sample_imported"
        const val KEY_REVISION = "revision"
    }
}
