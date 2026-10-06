package com.livevip.app.store

import android.content.Context
import android.content.SharedPreferences

/**
 * Plain persisted settings (everything EXCEPT credentials — the stream URL
 * and key live in [SecureStore]).
 */
class SettingsStore private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("live_vip_settings", Context.MODE_PRIVATE)

    // ---------------- Output ----------------

    /** OutputFormat name (LANDSCAPE_16_9 / VERTICAL_9_16). */
    var outputFormat: String
        get() = prefs.getString(KEY_FORMAT, "LANDSCAPE_16_9") ?: "LANDSCAPE_16_9"
        set(value) = prefs.edit().putString(KEY_FORMAT, value).apply()

    /** Quality preset: "auto" / "480p" / "720p" / "1080p". Default 720p-class AUTO. */
    var quality: String
        get() = prefs.getString(KEY_QUALITY, "auto") ?: "auto"
        set(value) = prefs.edit().putString(KEY_QUALITY, value).apply()

    var fps: Int
        get() = prefs.getInt(KEY_FPS, 30)
        set(value) = prefs.edit().putInt(KEY_FPS, value).apply()

    /** Saved gesture transform (LiveCompositionState JSON). */
    var compositionJson: String
        get() = prefs.getString(KEY_COMPOSITION, "") ?: ""
        set(value) = prefs.edit().putString(KEY_COMPOSITION, value).apply()

    // ---------------- Current selection ----------------

    /** Selected video for the current session ("" = none). Reference only. */
    var currentVideoUri: String
        get() = prefs.getString(KEY_CURRENT_VIDEO, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CURRENT_VIDEO, value).apply()

    /** Selected video metadata JSON (name/duration/res/fps/rotation/audio). */
    var currentVideoJson: String
        get() = prefs.getString(KEY_CURRENT_VIDEO_META, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CURRENT_VIDEO_META, value).apply()

    // ---------------- Audio ----------------

    var videoAudioEnabled: Boolean
        get() = prefs.getBoolean(KEY_VIDEO_AUDIO, true)
        set(value) = prefs.edit().putBoolean(KEY_VIDEO_AUDIO, value).apply()

    var microphoneEnabled: Boolean
        get() = prefs.getBoolean(KEY_MICROPHONE, false)
        set(value) = prefs.edit().putBoolean(KEY_MICROPHONE, value).apply()

    var audioSampleRate: Int
        get() = prefs.getInt(KEY_SAMPLE_RATE, 44_100)
        set(value) = prefs.edit().putInt(KEY_SAMPLE_RATE, value).apply()

    var audioStereo: Boolean
        get() = prefs.getBoolean(KEY_STEREO, true)
        set(value) = prefs.edit().putBoolean(KEY_STEREO, value).apply()

    var echoCanceler: Boolean
        get() = prefs.getBoolean(KEY_ECHO, true)
        set(value) = prefs.edit().putBoolean(KEY_ECHO, value).apply()

    var noiseSuppressor: Boolean
        get() = prefs.getBoolean(KEY_NOISE, true)
        set(value) = prefs.edit().putBoolean(KEY_NOISE, value).apply()

    // ---------------- Behaviour ----------------

    var autoReconnect: Boolean
        get() = prefs.getBoolean(KEY_AUTO_RECONNECT, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_RECONNECT, value).apply()

    var maxReconnectAttempts: Int
        get() = prefs.getInt(KEY_MAX_RECONNECT, 5)
        set(value) = prefs.edit().putInt(KEY_MAX_RECONNECT, value).apply()

    /** Show the floating LIVE bubble when the app goes to background. */
    var floatingBubbleEnabled: Boolean
        get() = prefs.getBoolean(KEY_FLOATING_BUBBLE, true)
        set(value) = prefs.edit().putBoolean(KEY_FLOATING_BUBBLE, value).apply()

    // ---------------- Diagnostics ----------------

    /** Developer diagnostics panel visibility (long-press the title). */
    var debugDiagnostics: Boolean
        get() = prefs.getBoolean(KEY_DEBUG_DIAG, false)
        set(value) = prefs.edit().putBoolean(KEY_DEBUG_DIAG, value).apply()

    companion object {
        private const val KEY_FORMAT = "output_format"
        private const val KEY_QUALITY = "quality"
        private const val KEY_FPS = "fps"
        private const val KEY_COMPOSITION = "composition_json"
        private const val KEY_CURRENT_VIDEO = "current_video_uri"
        private const val KEY_CURRENT_VIDEO_META = "current_video_json"
        private const val KEY_VIDEO_AUDIO = "video_audio_enabled"
        private const val KEY_MICROPHONE = "microphone_enabled"
        private const val KEY_SAMPLE_RATE = "sample_rate"
        private const val KEY_STEREO = "stereo"
        private const val KEY_ECHO = "echo_canceler"
        private const val KEY_NOISE = "noise_suppressor"
        private const val KEY_AUTO_RECONNECT = "auto_reconnect"
        private const val KEY_MAX_RECONNECT = "max_reconnect"
        private const val KEY_FLOATING_BUBBLE = "floating_bubble_enabled"
        private const val KEY_DEBUG_DIAG = "debug_diagnostics"

        @Volatile
        private var instance: SettingsStore? = null

        fun get(context: Context): SettingsStore =
            instance ?: synchronized(this) {
                instance ?: SettingsStore(context.applicationContext)
                    .also { instance = it }
            }
    }
}
