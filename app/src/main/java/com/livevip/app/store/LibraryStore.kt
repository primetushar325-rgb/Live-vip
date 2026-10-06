package com.livevip.app.store

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.livevip.app.media.MediaAnalyzer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * VIDEO LIBRARY — reference-based list of picked videos. The original file
 * is NEVER copied and NEVER deleted; removing an entry removes only the
 * reference (and our tiny cached thumbnail).
 *
 * Thumbnails are extracted ONCE at import (small, ~128px JPEG on disk) —
 * no per-frame bitmap decoding, no large in-memory bitmaps.
 */
data class LibraryVideo(
    val id: Long,
    val uri: String,
    val name: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val fps: Int,
    val hasAudio: Boolean,
    val rotation: Int,
    val thumbPath: String? = null
) {
    fun uriParsed(): Uri = Uri.parse(uri)

    /** Display dimensions accounting for rotation metadata. */
    val displayWidth: Int get() = if (rotation == 90 || rotation == 270) height else width
    val displayHeight: Int get() = if (rotation == 90 || rotation == 270) width else height

    fun resolutionLabel(): String = "${displayWidth}×$displayHeight"

    fun durationLabel(): String {
        val totalSec = durationMs / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%02d:%02d", m, s)
    }

    fun infoLabel(): String =
        "${resolutionLabel()} • $fps FPS • ${durationLabel()}" +
            (if (!hasAudio) " • no audio" else "")

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("uri", uri)
        .put("name", name)
        .put("durationMs", durationMs)
        .put("width", width)
        .put("height", height)
        .put("fps", fps)
        .put("hasAudio", hasAudio)
        .put("rotation", rotation)
        .put("thumb", thumbPath ?: "")

    companion object {
        fun fromJson(o: JSONObject): LibraryVideo? {
            val uri = o.optString("uri")
            if (uri.isBlank()) return null
            return LibraryVideo(
                id = o.optLong("id"),
                uri = uri,
                name = o.optString("name", "video"),
                durationMs = o.optLong("durationMs"),
                width = o.optInt("width"),
                height = o.optInt("height"),
                fps = o.optInt("fps", 30),
                hasAudio = o.optBoolean("hasAudio", true),
                rotation = o.optInt("rotation"),
                thumbPath = o.optString("thumb").ifBlank { null }
            )
        }
    }
}

object LibraryStore {

    private const val PREFS = "live_vip_library"
    private const val KEY_VIDEOS = "videos"

    fun all(context: Context): List<LibraryVideo> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_VIDEOS, "[]") ?: "[]"
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                LibraryVideo.fromJson(o)
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun byUri(context: Context, uri: Uri): LibraryVideo? =
        all(context).firstOrNull { it.uri == uri.toString() }

    /**
     * Add (or refresh) a picked video: takes the persistable URI permission
     * so the reference survives restarts, extracts a small thumbnail once.
     */
    fun add(context: Context, uri: Uri, info: MediaAnalyzer.VideoInfo): LibraryVideo {
        val appContext = context.applicationContext
        try {
            appContext.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {
            // Some pickers don't grant persistable permission — still usable
            // during this session.
        }
        val existing = byUri(appContext, uri)
        val thumb = existing?.thumbPath
            ?: generateThumbnail(appContext, uri, existing?.id ?: System.currentTimeMillis())
        val video = LibraryVideo(
            id = existing?.id ?: System.currentTimeMillis(),
            uri = uri.toString(),
            name = info.displayName,
            durationMs = info.durationMs,
            width = info.width,
            height = info.height,
            fps = info.fps,
            hasAudio = info.hasAudio,
            rotation = info.rotation,
            thumbPath = thumb
        )
        saveAll(appContext, all(appContext).filter { it.uri != video.uri } + video)
        return video
    }

    /** Removes the REFERENCE (and our thumbnail file). The user's media file is untouched. */
    fun remove(context: Context, id: Long) {
        val appContext = context.applicationContext
        val list = all(appContext)
        list.firstOrNull { it.id == id }?.thumbPath?.let { File(it).delete() }
        saveAll(appContext, list.filter { it.id != id })
    }

    private fun saveAll(context: Context, videos: List<LibraryVideo>) {
        val array = JSONArray()
        videos.forEach { array.put(it.toJson()) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_VIDEOS, array.toString()).apply()
    }

    /**
     * One small thumbnail per video (128px wide JPEG on disk). Runs on the
     * caller's background thread; failures degrade to no thumbnail.
     */
    private fun generateThumbnail(context: Context, uri: Uri, id: Long): String? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return null
            val width = 128
            val height = (frame.height * (width.toFloat() / frame.width)).toInt().coerceAtLeast(1)
            val small = Bitmap.createScaledBitmap(frame, width, height, true)
            if (small !== frame) frame.recycle()
            val dir = File(context.filesDir, "thumbs").apply { mkdirs() }
            val file = File(dir, "thumb_$id.jpg")
            FileOutputStream(file).use { small.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            small.recycle()
            file.absolutePath
        } catch (_: Throwable) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Throwable) {
            }
        }
    }
}
