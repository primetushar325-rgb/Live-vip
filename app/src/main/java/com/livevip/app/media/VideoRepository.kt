package com.livevip.app.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** A video in the user's library — reference based, never duplicated. */
data class VideoItem(
    val id: Long,
    val uri: String,
    var name: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val fps: Int,
    val hasAudio: Boolean,
    val sampleRate: Int,
    val channels: Int,
    val sizeBytes: Long
) {
    fun uriParsed(): Uri = Uri.parse(uri)

    fun metaLabel(): String {
        val h = height.coerceAtLeast(1)
        val dur = durationLabel()
        val audio = if (hasAudio) "Audio ✓" else "No audio"
        return "${h}p • $fps FPS • $dur • $audio"
    }

    fun durationLabel(): String {
        val totalSec = durationMs / 1000
        val hh = totalSec / 3600
        val mm = (totalSec % 3600) / 60
        val ss = totalSec % 60
        return if (hh > 0) String.format("%d:%02d:%02d", hh, mm, ss)
        else String.format("%02d:%02d", mm, ss)
    }
}

/**
 * Library of imported videos. Only persistable content-URI references are
 * stored (no file copies — storage is never wasted with duplicates).
 */
class VideoRepository private constructor(private val context: Context) {

    private val prefs =
        context.getSharedPreferences("live_vip_videos", Context.MODE_PRIVATE)

    fun all(): List<VideoItem> {
        val raw = prefs.getString(KEY_VIDEOS, "[]") ?: "[]"
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                VideoItem(
                    id = o.optLong("id"),
                    uri = o.optString("uri"),
                    name = o.optString("name"),
                    durationMs = o.optLong("durationMs"),
                    width = o.optInt("width"),
                    height = o.optInt("height"),
                    fps = o.optInt("fps", 30),
                    hasAudio = o.optBoolean("hasAudio"),
                    sampleRate = o.optInt("sampleRate", 44100),
                    channels = o.optInt("channels", 2),
                    sizeBytes = o.optLong("sizeBytes")
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun byId(id: Long): VideoItem? = all().firstOrNull { it.id == id }

    fun add(uri: Uri, info: MediaAnalyzer.VideoInfo): VideoItem {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {
            // Some pickers don't grant persistable permission — still usable
            // during this session.
        }
        val item = VideoItem(
            id = System.currentTimeMillis(),
            uri = uri.toString(),
            name = info.displayName,
            durationMs = info.durationMs,
            width = info.width,
            height = info.height,
            fps = info.fps,
            hasAudio = info.hasAudio,
            sampleRate = info.sampleRate,
            channels = info.channels,
            sizeBytes = info.sizeBytes
        )
        val existing = all().filter { it.uri != item.uri }
        save(existing + item)
        return item
    }

    fun rename(id: Long, newName: String) {
        save(all().map { if (it.id == id) it.copy(name = newName) else it })
    }

    fun remove(id: Long) {
        save(all().filter { it.id != id })
    }

    private fun save(items: List<VideoItem>) {
        val array = JSONArray()
        items.forEach { v ->
            array.put(
                JSONObject()
                    .put("id", v.id)
                    .put("uri", v.uri)
                    .put("name", v.name)
                    .put("durationMs", v.durationMs)
                    .put("width", v.width)
                    .put("height", v.height)
                    .put("fps", v.fps)
                    .put("hasAudio", v.hasAudio)
                    .put("sampleRate", v.sampleRate)
                    .put("channels", v.channels)
                    .put("sizeBytes", v.sizeBytes)
            )
        }
        prefs.edit().putString(KEY_VIDEOS, array.toString()).apply()
    }

    companion object {
        private const val KEY_VIDEOS = "videos"

        @Volatile
        private var instance: VideoRepository? = null

        fun get(context: Context): VideoRepository =
            instance ?: synchronized(this) {
                instance ?: VideoRepository(context.applicationContext)
                    .also { instance = it }
            }
    }
}
