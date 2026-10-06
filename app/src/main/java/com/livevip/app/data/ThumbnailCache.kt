package com.livevip.app.data

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import com.livevip.app.media.MediaAnalyzer
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Two-layer thumbnail cache for the video library:
 *  - memory: LruCache keyed by video id
 *  - disk:   small JPEGs in filesDir/thumbs (regenerated only if missing)
 *
 * Decoding uses MediaMetadataRetriever (correct frame extraction) and always
 * happens OFF the main thread. Library items are references — videos are
 * never copied or re-encoded.
 */
class ThumbnailCache private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val memory = object : LruCache<String, Bitmap>(32) {}
    private val executor: ExecutorService = Executors.newFixedThreadPool(2) {
        Thread(it, "thumb-cache").apply { isDaemon = true }
    }

    /** Returns the cached thumbnail immediately (null when not cached). */
    fun cached(videoId: Long): Bitmap? = memory.get(key(videoId))

    /**
     * Load a thumbnail; invokes [into] on the main thread when ready.
     * Falls back to a fresh decode when no disk/memory copy exists.
     */
    fun load(videoId: Long, uri: String, into: (Bitmap?) -> Unit) {
        memory.get(key(videoId))?.let { bmp ->
            into(bmp)
            return
        }
        executor.execute {
            val bmp = decodeOrGenerate(videoId, uri)
            android.os.Handler(android.os.Looper.getMainLooper()).post { into(bmp) }
        }
    }

    private fun decodeOrGenerate(videoId: Long, uri: String): Bitmap? {
        val file = File(File(appContext.filesDir, "thumbs"), "$videoId.jpg")
        if (file.exists()) {
            decodeFile(file)?.let { bmp ->
                memory.put(key(videoId), bmp)
                return bmp
            }
        }
        // Generate from the source (correct via MediaMetadataRetriever).
        val full = MediaAnalyzer.thumbnail(appContext, android.net.Uri.parse(uri)) ?: return null
        val scaled = scaleDown(full, MAX_EDGE)
        full.recycle()
        // Persist to disk for next time.
        try {
            file.parentFile?.mkdirs()
            file.outputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)
            }
        } catch (_: Throwable) {
        }
        memory.put(key(videoId), scaled)
        return scaled
    }

    fun invalidate(videoId: Long) {
        memory.remove(key(videoId))
        val file = File(File(appContext.filesDir, "thumbs"), "$videoId.jpg")
        try {
            if (file.exists()) file.delete()
        } catch (_: Throwable) {
        }
    }

    private fun key(videoId: Long) = videoId.toString()

    private fun scaleDown(src: Bitmap, maxEdge: Int): Bitmap {
        val largest = maxOf(src.width, src.height)
        if (largest <= maxEdge) return src
        val ratio = maxEdge.toFloat() / largest
        return Bitmap.createScaledBitmap(
            src, (src.width * ratio).toInt().coerceAtLeast(1),
            (src.height * ratio).toInt().coerceAtLeast(1), true
        )
    }

    private fun decodeFile(file: File): Bitmap? = try {
        android.graphics.BitmapFactory.decodeFile(file.absolutePath)
    } catch (_: Throwable) {
        null
    }

    companion object {
        private const val MAX_EDGE = 320

        @Volatile
        private var instance: ThumbnailCache? = null

        fun get(context: Context): ThumbnailCache =
            instance ?: synchronized(this) {
                instance ?: ThumbnailCache(context.applicationContext)
                    .also { instance = it }
            }
    }
}
