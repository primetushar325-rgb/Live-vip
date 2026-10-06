package com.livevip.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Single source of truth for all persisted settings.
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

    // ---------------- Video ----------------

    var videoWidth: Int
        get() = prefs.getInt(KEY_VIDEO_WIDTH, 1280)
        set(value) = prefs.edit().putInt(KEY_VIDEO_WIDTH, value).apply()

    var videoHeight: Int
        get() = prefs.getInt(KEY_VIDEO_HEIGHT, 720)
        set(value) = prefs.edit().putInt(KEY_VIDEO_HEIGHT, value).apply()

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

    // ---------------- Stream behaviour ----------------

    var autoReconnect: Boolean
        get() = prefs.getBoolean(KEY_AUTO_RECONNECT, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_RECONNECT, value).apply()

    var maxReconnectAttempts: Int
        get() = prefs.getInt(KEY_MAX_RECONNECT, 3)
        set(value) = prefs.edit().putInt(KEY_MAX_RECONNECT, value).apply()

    var platformIndex: Int
        get() = prefs.getInt(KEY_PLATFORM, 0)
        set(value) = prefs.edit().putInt(KEY_PLATFORM, value).apply()

    /** Id of the video selected for VIDEO LIVE (0 = none). */
    var selectedVideoId: Long
        get() = prefs.getLong(KEY_SELECTED_VIDEO, 0L)
        set(value) = prefs.edit().putLong(KEY_SELECTED_VIDEO, value).apply()

    /** Last used mode: 0 = VIDEO (default), 1 = CAMERA. */
    var lastMode: Int
        get() = prefs.getInt(KEY_LAST_MODE, 0)
        set(value) = prefs.edit().putInt(KEY_LAST_MODE, value).apply()

    // ---------------- Appearance / Advanced ----------------

    var themeMode: Int
        get() = prefs.getInt(KEY_THEME, THEME_DARK)
        set(value) = prefs.edit().putInt(KEY_THEME, value).apply()

    var debugLogging: Boolean
        get() = prefs.getBoolean(KEY_DEBUG_LOGGING, false)
        set(value) = prefs.edit().putBoolean(KEY_DEBUG_LOGGING, value).apply()

    var showStats: Boolean
        get() = prefs.getBoolean(KEY_SHOW_STATS, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_STATS, value).apply()

    companion object {
        const val THEME_SYSTEM = 0
        const val THEME_DARK = 1
        const val THEME_LIGHT = 2

        private const val KEY_STREAM_URL = "stream_url"
        private const val KEY_STREAM_KEY = "stream_key"
        private const val KEY_VIDEO_WIDTH = "video_width"
        private const val KEY_VIDEO_HEIGHT = "video_height"
        private const val KEY_VIDEO_FPS = "video_fps"
        private const val KEY_VIDEO_BITRATE = "video_bitrate"
        private const val KEY_KEYFRAME_INTERVAL = "keyframe_interval"
        private const val KEY_AUDIO_BITRATE = "audio_bitrate"
        private const val KEY_AUDIO_SAMPLE_RATE = "audio_sample_rate"
        private const val KEY_AUDIO_STEREO = "audio_stereo"
        private const val KEY_ECHO_CANCELER = "echo_canceler"
        private const val KEY_NOISE_SUPPRESSOR = "noise_suppressor"
        private const val KEY_AUTO_RECONNECT = "auto_reconnect"
        private const val KEY_MAX_RECONNECT = "max_reconnect"
        private const val KEY_PLATFORM = "platform_index"
        private const val KEY_SELECTED_VIDEO = "selected_video_id"
        private const val KEY_LAST_MODE = "last_mode"
        private const val KEY_THEME = "theme_mode"
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
