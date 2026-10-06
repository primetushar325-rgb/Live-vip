package com.livevip.app.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject

/**
 * The SELECTED VIDEO for live — a single reference, never a library, never a
 * copy. The persistable content URI is stored (when the picker grants it) so
 * the Saved Live reopens after app restarts.
 */
data class SelectedVideo(
    val uri: String,
    val name: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val fps: Int,
    val hasAudio: Boolean,
    val rotation: Int = 0
) {
    fun uriParsed(): Uri = Uri.parse(uri)

    /** Display dimensions accounting for rotation metadata. */
    val displayWidth: Int get() = if (rotation == 90 || rotation == 270) height else width
    val displayHeight: Int get() = if (rotation == 90 || rotation == 270) width else height

    fun resolutionLabel(): String = "${displayWidth}×$displayHeight"

    fun durationLabel(): String {
        val totalSec = durationMs / 1000
        val hh = totalSec / 3600
        val mm = (totalSec % 3600) / 60
        val ss = totalSec % 60
        return if (hh > 0) String.format("%d:%02d:%02d", hh, mm, ss)
        else String.format("%02d:%02d", mm, ss)
    }

    fun infoLabel(): String =
        "${resolutionLabel()} • $fps FPS • ${durationLabel()}" +
            (if (!hasAudio) " • no audio" else "")

    fun toJson(): JSONObject = JSONObject()
        .put("uri", uri)
        .put("name", name)
        .put("durationMs", durationMs)
        .put("width", width)
        .put("height", height)
        .put("fps", fps)
        .put("hasAudio", hasAudio)
        .put("rotation", rotation)

    companion object {
        fun fromJson(raw: String?): SelectedVideo? {
            if (raw.isNullOrBlank()) return null
            return try {
                val o = JSONObject(raw)
                if (o.optString("uri").isBlank()) null
                else SelectedVideo(
                    uri = o.optString("uri"),
                    name = o.optString("name"),
                    durationMs = o.optLong("durationMs"),
                    width = o.optInt("width"),
                    height = o.optInt("height"),
                    fps = o.optInt("fps", 30),
                    hasAudio = o.optBoolean("hasAudio", true),
                    rotation = o.optInt("rotation")
                )
            } catch (_: Throwable) {
                null
            }
        }
    }
}

/**
 * Persists the selected video (Saved Live). Reference-based: the original
 * file is NEVER copied — only its content URI + metadata.
 */
object SelectedVideoStore {

    /**
     * Take persistable read permission (when granted) so the URI survives
     * restarts, then persist the selection.
     */
    fun select(context: Context, uri: Uri, info: MediaAnalyzer.VideoInfo): SelectedVideo {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {
            // Some pickers don't grant persistable permission — still usable
            // during this session.
        }
        val video = SelectedVideo(
            uri = uri.toString(),
            name = info.displayName,
            durationMs = info.durationMs,
            width = info.width,
            height = info.height,
            fps = info.fps,
            hasAudio = info.hasAudio,
            rotation = info.rotation
        )
        val prefs = context.getSharedPreferences("live_vip_selected_video", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("uri", video.uri)
            .putString("meta", video.toJson().toString())
            .apply()
        return video
    }

    /** The persisted selection, or null when nothing valid is saved. */
    fun current(context: Context): SelectedVideo? {
        val prefs = context.getSharedPreferences("live_vip_selected_video", Context.MODE_PRIVATE)
        val video = SelectedVideo.fromJson(prefs.getString("meta", null)) ?: return null
        // Sanity: the persisted URI must still be readable.
        return try {
            context.contentResolver.openFileDescriptor(video.uriParsed(), "r")?.use { video }
        } catch (_: Throwable) {
            null
        }
    }

    fun clear(context: Context) {
        val prefs = context.getSharedPreferences("live_vip_selected_video", Context.MODE_PRIVATE)
        prefs.edit().remove("uri").remove("meta").apply()
    }
}
