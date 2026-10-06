package com.livevip.app.store

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * SAVED LIVES — named streaming configurations (complete reusable setups).
 *
 * A Saved Live contains: name, selected video reference, output format,
 * composition (transform), server URL, quality, FPS and audio settings.
 * The STREAM KEY is not serialized here — it lives only in [SecureStore]
 * under the saved-live id.
 */
data class SavedLive(
    val id: Long,
    val name: String,
    val videoUri: String,
    val videoName: String,
    val outputFormat: String,
    val quality: String,
    val fps: Int,
    /** LiveCompositionState JSON — the exact transform. */
    val compositionJson: String,
    val serverUrl: String,
    val videoAudio: Boolean,
    val mic: Boolean
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("videoUri", videoUri)
        .put("videoName", videoName)
        .put("format", outputFormat)
        .put("quality", quality)
        .put("fps", fps)
        .put("composition", compositionJson)
        .put("url", serverUrl)
        .put("videoAudio", videoAudio)
        .put("mic", mic)

    companion object {
        fun fromJson(o: JSONObject): SavedLive? {
            if (o.optString("name").isBlank()) return null
            return SavedLive(
                id = o.optLong("id"),
                name = o.optString("name"),
                videoUri = o.optString("videoUri"),
                videoName = o.optString("videoName"),
                outputFormat = o.optString("format", "LANDSCAPE_16_9"),
                quality = o.optString("quality", "auto"),
                fps = o.optInt("fps", 30),
                compositionJson = o.optString("composition", ""),
                serverUrl = o.optString("url", ""),
                videoAudio = o.optBoolean("videoAudio", true),
                mic = o.optBoolean("mic", false)
            )
        }
    }
}

object SavedLiveStore {

    private const val PREFS = "live_vip_saved_lives"
    private const val KEY_LIST = "saved_lives"

    fun all(context: Context): List<SavedLive> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_LIST, "[]") ?: "[]"
        return try {
            val array = JSONArray(raw)
            (0 until array.length())
                .mapNotNull { array.optJSONObject(it) }
                .mapNotNull { SavedLive.fromJson(it) }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * Create or update (same name ⇒ overwrite — that is "edit"). The stream
     * key goes to encrypted storage, never to the JSON list.
     */
    fun save(context: Context, live: SavedLive, streamKey: String): SavedLive {
        val appContext = context.applicationContext
        val existing = all(appContext)
        val withId = if (live.id != 0L) live else live.copy(id = System.currentTimeMillis())
        val replaced = existing.filter { it.id != withId.id && it.name != withId.name }
        saveAll(appContext, replaced + withId)
        SecureStore.setSavedKey(appContext, withId.id, streamKey)
        return withId
    }

    /** The stored stream key for a Saved Live (encrypted). */
    fun keyFor(context: Context, live: SavedLive): String =
        SecureStore.savedKey(context, live.id)

    fun delete(context: Context, id: Long) {
        val appContext = context.applicationContext
        saveAll(appContext, all(appContext).filter { it.id != id })
        SecureStore.deleteSavedKey(appContext, id)
    }

    private fun saveAll(context: Context, lives: List<SavedLive>) {
        val array = JSONArray()
        lives.forEach { array.put(it.toJson()) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LIST, array.toString()).apply()
    }
}
