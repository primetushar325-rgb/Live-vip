package com.livevip.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Single source of truth for all persisted settings — the "Saved Live".
 *
 * - Regular preferences: plain SharedPreferences.
 * - Stream URL / Stream key: Android Keystore backed
 *   EncryptedSharedPreferences. Credentials never leave the device
 *   and are never written to logs.
 *
 * If the secure storage cannot be created on a broken device keystore,
 * we fall back to private app-sandbox preferences instead of crashing.
 */
class SettingsRepository private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("live_vip_settings", Context.MODE_PRIVATE)

    private val appContext = context.applicationContext

    // Lazy: the Android Keystore is only touched the first time
    // credentials are read/written — never during app startup.
    private val securePrefs: SharedPreferences by lazy { createSecurePrefs() }

    private fun createSecurePrefs(): SharedPreferences = try {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            "live_vip_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (t: Throwable) {
        // Device keystore unavailable — degrade gracefully, never crash.
        appContext.getSharedPreferences("live_vip_secure_fallback", Context.MODE_PRIVATE)
    }

    // ---------------- Stream credentials (secure) ----------------

    var streamUrl: String
        get() = securePrefs.getString(KEY_STREAM_URL, "") ?: ""
        set(value) = securePrefs.edit().putString(KEY_STREAM_URL, value.trim()).apply()

    var streamKey: String
        get() = securePrefs.getString(KEY_STREAM_KEY, "") ?: ""
        set(value) = securePrefs.edit().putString(KEY_STREAM_KEY, value.trim()).apply()

    /** UI-only preference: mask or reveal the stream key field. */
    var showStreamKey: Boolean
        get() = prefs.getBoolean(KEY_SHOW_STREAM_KEY, false)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_STREAM_KEY, value).apply()

    // ---------------- Saved Live: selected video ----------------

    /** Content URI of the selected video ("" = none). Never duplicated. */
    var selectedVideoUri: String
        get() = prefs.getString(KEY_SELECTED_VIDEO_URI, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SELECTED_VIDEO_URI, value).apply()

    /** Selected video metadata (JSON) for the info line — name/duration/res/fps. */
    var selectedVideoJson: String
        get() = prefs.getString(KEY_SELECTED_VIDEO_JSON, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SELECTED_VIDEO_JSON, value).apply()

    // ---------------- Output format ----------------

    /** Output format: CanvasAspect name (LANDSCAPE_16_9 / PORTRAIT_9_16). */
    var outputAspect: String
        get() = prefs.getString(KEY_OUTPUT_ASPECT, "LANDSCAPE_16_9") ?: "LANDSCAPE_16_9"
        set(value) = prefs.edit().putString(KEY_OUTPUT_ASPECT, value).apply()

    /** Quality preset: "auto" / "480p" / "720p" / "1080p". */
    var videoQuality: String
        get() = prefs.getString(KEY_VIDEO_QUALITY, "auto") ?: "auto"
        set(value) = prefs.edit().putString(KEY_VIDEO_QUALITY, value).apply()

    /** Saved video transform (JSON) — zoom/pan survive app restarts. */
    var transformJson: String
        get() = prefs.getString(KEY_TRANSFORM, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TRANSFORM, value).apply()

    // ---------------- Encoder ----------------

    var videoFps: Int
        get() = prefs.getInt(KEY_VIDEO_FPS, 30)
        set(value) = prefs.edit().putInt(KEY_VIDEO_FPS, value).apply()

    var videoBitrateKbps: Int
        get() = prefs.getInt(KEY_VIDEO_BITRATE, 2500)
        set(value) = prefs.edit().putInt(KEY_VIDEO_BITRATE, value).apply()

    var keyframeIntervalSec: Int
        get() = prefs.getInt(KEY_KEYFRAME_INTERVAL, 2)
        set(value) = prefs.edit().putInt(KEY_KEYFRAME_INTERVAL, value).apply()

    // ---------------- Audio ----------------

    var audioBitrateKbps: Int
        get() = prefs.getInt(KEY_AUDIO_BITRATE, 128)
        set(value) = prefs.edit().putInt(KEY_AUDIO_BITRATE, value).apply()

    var audioSampleRate: Int
        get() = prefs.getInt(KEY_AUDIO_SAMPLE_RATE, 44100)
        set(value) = prefs.edit().putInt(KEY_AUDIO_SAMPLE_RATE, value).apply()

    var audioStereo: Boolean
        get() = prefs.getBoolean(KEY_AUDIO_STEREO, true)
        set(value) = prefs.edit().putBoolean(KEY_AUDIO_STEREO, value).apply()

    var echoCanceler: Boolean
        get() = prefs.getBoolean(KEY_ECHO_CANCELER, true)
        set(value) = prefs.edit().putBoolean(KEY_ECHO_CANCELER, value).apply()

    var noiseSuppressor: Boolean
        get() = prefs.getBoolean(KEY_NOISE_SUPPRESSOR, true)
        set(value) = prefs.edit().putBoolean(KEY_NOISE_SUPPRESSOR, value).apply()

    /** Video audio ON/OFF — persists across sessions. */
    var videoAudioEnabled: Boolean
        get() = prefs.getBoolean(KEY_VIDEO_AUDIO, true)
        set(value) = prefs.edit().putBoolean(KEY_VIDEO_AUDIO, value).apply()

    /** Microphone ON/OFF — persists across sessions. */
    var microphoneEnabled: Boolean
        get() = prefs.getBoolean(KEY_MICROPHONE, false)
        set(value) = prefs.edit().putBoolean(KEY_MICROPHONE, value).apply()

    // ---------------- Stream behaviour ----------------

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

    // ---------------- Advanced ----------------

    var debugLogging: Boolean
        get() = prefs.getBoolean(KEY_DEBUG_LOGGING, false)
        set(value) = prefs.edit().putBoolean(KEY_DEBUG_LOGGING, value).apply()

    var showStats: Boolean
        get() = prefs.getBoolean(KEY_SHOW_STATS, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_STATS, value).apply()

    companion object {
        private const val KEY_STREAM_URL = "stream_url"
        private const val KEY_STREAM_KEY = "stream_key"
        private const val KEY_SHOW_STREAM_KEY = "show_stream_key"
        private const val KEY_SELECTED_VIDEO_URI = "selected_video_uri"
        private const val KEY_SELECTED_VIDEO_JSON = "selected_video_json"
        private const val KEY_OUTPUT_ASPECT = "output_aspect"
        private const val KEY_VIDEO_QUALITY = "video_quality"
        private const val KEY_TRANSFORM = "transform_json"
        private const val KEY_VIDEO_FPS = "video_fps"
        private const val KEY_VIDEO_BITRATE = "video_bitrate"
        private const val KEY_KEYFRAME_INTERVAL = "keyframe_interval"
        private const val KEY_AUDIO_BITRATE = "audio_bitrate"
        private const val KEY_AUDIO_SAMPLE_RATE = "audio_sample_rate"
        private const val KEY_AUDIO_STEREO = "audio_stereo"
        private const val KEY_ECHO_CANCELER = "echo_canceler"
        private const val KEY_NOISE_SUPPRESSOR = "noise_suppressor"
        private const val KEY_VIDEO_AUDIO = "video_audio_enabled"
        private const val KEY_MICROPHONE = "microphone_enabled"
        private const val KEY_AUTO_RECONNECT = "auto_reconnect"
        private const val KEY_MAX_RECONNECT = "max_reconnect"
        private const val KEY_FLOATING_BUBBLE = "floating_bubble_enabled"
        private const val KEY_DEBUG_LOGGING = "debug_logging"
        private const val KEY_SHOW_STATS = "show_stats"

        @Volatile
        private var instance: SettingsRepository? = null

        fun get(context: Context): SettingsRepository =
            instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext)
                    .also { instance = it }
            }
    }
}
