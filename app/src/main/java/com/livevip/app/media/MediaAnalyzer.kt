package com.livevip.app.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns

/**
 * Smart video compatibility analyzer.
 * Extracts codec / resolution / fps / duration / audio info from any
 * selected video before it is used for streaming, so unsupported files
 * produce a readable message instead of a crash.
 */
object MediaAnalyzer {

    data class VideoInfo(
        val displayName: String,
        val sizeBytes: Long,
        val durationMs: Long,
        val width: Int,
        val height: Int,
        val rotation: Int,
        val fps: Int,
        val videoMime: String,
        val hasAudio: Boolean,
        val audioMime: String,
        val sampleRate: Int,
        val channels: Int
    ) {
        val isStereo: Boolean get() = channels >= 2

        fun resolutionLabel(): String {
            val h = if (rotation == 90 || rotation == 270) width else height
            return "${h}p"
        }

        fun durationLabel(): String {
            val totalSec = durationMs / 1000
            val h = totalSec / 3600
            val m = (totalSec % 3600) / 60
            val s = totalSec % 60
            return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
            else String.format("%02d:%02d", m, s)
        }

        fun sizeLabel(): String {
            val mb = sizeBytes / (1024.0 * 1024.0)
            return if (mb >= 1024) String.format("%.1f GB", mb / 1024)
            else String.format("%.1f MB", mb)
        }
    }

    /** Returns null when the file is not a usable video. */
    fun analyze(context: Context, uri: Uri): VideoInfo? {
        var name = "video.mp4"
        var size = 0L
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIdx >= 0) name = cursor.getString(nameIdx) ?: name
                    if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
                }
            }
        } catch (_: Exception) {
        }

        var durationMs = 0L
        var width = 0
        var height = 0
        var rotation = 0
        var fps = 30
        var videoMime = ""
        var hasAudio = false
        var audioMime = ""
        var sampleRate = 44100
        var channels = 2

        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            rotation = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            retriever.release()
        } catch (_: Exception) {
            return null
        }

        try {
            val extractor = MediaExtractor()
            extractor.setDataSource(context, uri, null)
            var foundVideo = false
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && !foundVideo) {
                    foundVideo = true
                    videoMime = mime
                    width = format.getIntOrDefault(MediaFormat.KEY_WIDTH, 0)
                    height = format.getIntOrDefault(MediaFormat.KEY_HEIGHT, 0)
                    fps = format.getIntOrDefault(MediaFormat.KEY_FRAME_RATE, 30)
                } else if (mime.startsWith("audio/") && !hasAudio) {
                    hasAudio = true
                    audioMime = mime
                    sampleRate = format.getIntOrDefault(MediaFormat.KEY_SAMPLE_RATE, 44100)
                    channels = format.getIntOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 2)
                }
            }
            extractor.release()
            if (!foundVideo) return null
        } catch (_: Exception) {
            return null
        }

        return VideoInfo(
            displayName = name,
            sizeBytes = size,
            durationMs = durationMs,
            width = width,
            height = height,
            rotation = rotation,
            fps = fps,
            videoMime = videoMime,
            hasAudio = hasAudio,
            audioMime = audioMime,
            sampleRate = sampleRate,
            channels = channels
        )
    }

    /** Thumbnail for the library / selected-video card. */
    fun thumbnail(context: Context, uri: Uri): Bitmap? = try {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(context, uri)
        val bitmap = retriever.getFrameAtTime(1_000_000)
            ?: retriever.getFrameAtTime(0)
        retriever.release()
        bitmap
    } catch (_: Exception) {
        null
    }

    private fun MediaFormat.getIntOrDefault(key: String, default: Int): Int = try {
        if (containsKey(key)) getInteger(key) else default
    } catch (_: Exception) {
        default
    }
}
