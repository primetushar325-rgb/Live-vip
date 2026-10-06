package com.livevip.app.model

import android.net.Uri
import android.os.Bundle

data class StreamSettings(
    val videoUri: Uri,
    val serverUrl: String,
    val streamKey: String,
    val outputFormat: OutputFormat,
    val quality: StreamQuality,
    val fps: Int,
    val videoAudio: Boolean,
    val microphone: Boolean,
    val composition: CompositionState = CompositionState()
) {
    fun toBundle(): Bundle = Bundle().apply {
        putString(KEY_VIDEO_URI, videoUri.toString())
        putString(KEY_SERVER, serverUrl)
        putString(KEY_KEY, streamKey)
        putString(KEY_FORMAT, outputFormat.name)
        putString(KEY_QUALITY, quality.name)
        putInt(KEY_FPS, fps)
        putBoolean(KEY_VIDEO_AUDIO, videoAudio)
        putBoolean(KEY_MIC, microphone)
        putFloat(KEY_SCALE, composition.scale)
        putFloat(KEY_TRANSLATION_X, composition.translationX)
        putFloat(KEY_TRANSLATION_Y, composition.translationY)
        putFloat(KEY_ROTATION, composition.rotation)
        putString(KEY_FIT_MODE, composition.fitMode.name)
    }

    companion object {
        const val KEY_VIDEO_URI = "video_uri"
        const val KEY_SERVER = "server_url"
        const val KEY_KEY = "stream_key"
        const val KEY_FORMAT = "format"
        const val KEY_QUALITY = "quality"
        const val KEY_FPS = "fps"
        const val KEY_VIDEO_AUDIO = "video_audio"
        const val KEY_MIC = "microphone"
        const val KEY_SCALE = "composition_scale"
        const val KEY_TRANSLATION_X = "composition_translation_x"
        const val KEY_TRANSLATION_Y = "composition_translation_y"
        const val KEY_ROTATION = "composition_rotation"
        const val KEY_FIT_MODE = "composition_fit_mode"

        fun fromBundle(bundle: Bundle): StreamSettings? {
            val uri = bundle.getString(KEY_VIDEO_URI)?.let(Uri::parse) ?: return null
            val server = bundle.getString(KEY_SERVER).orEmpty()
            val key = bundle.getString(KEY_KEY).orEmpty()
            val format = runCatching { OutputFormat.valueOf(bundle.getString(KEY_FORMAT) ?: "LANDSCAPE") }.getOrDefault(OutputFormat.LANDSCAPE)
            val quality = runCatching { StreamQuality.valueOf(bundle.getString(KEY_QUALITY) ?: "P720") }.getOrDefault(StreamQuality.P720)
            val fitMode = runCatching { FitMode.valueOf(bundle.getString(KEY_FIT_MODE) ?: FitMode.FIT.name) }.getOrDefault(FitMode.FIT)
            val composition = CompositionState(
                outputWidth = if (format == OutputFormat.VERTICAL) quality.height else quality.width,
                outputHeight = if (format == OutputFormat.VERTICAL) quality.width else quality.height,
                scale = bundle.getFloat(KEY_SCALE, 1f),
                translationX = bundle.getFloat(KEY_TRANSLATION_X, 0f),
                translationY = bundle.getFloat(KEY_TRANSLATION_Y, 0f),
                rotation = bundle.getFloat(KEY_ROTATION, 0f),
                fitMode = fitMode
            )
            return StreamSettings(uri, server, key, format, quality, bundle.getInt(KEY_FPS, 30), bundle.getBoolean(KEY_VIDEO_AUDIO, true), bundle.getBoolean(KEY_MIC, false), composition)
        }
    }
}
