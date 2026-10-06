package com.livevip.app.storage

import android.content.Context
import android.net.Uri
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/** Stream keys are encrypted at rest and are never returned in diagnostic text. */
class SecureSettingsStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        "live_vip_secure_settings",
        MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun save(serverUrl: String, streamKey: String, videoUri: Uri?) {
        prefs.edit()
            .putString(KEY_SERVER, serverUrl)
            .putString(KEY_STREAM, streamKey)
            .putString(KEY_VIDEO, videoUri?.toString())
            .apply()
    }

    fun serverUrl(): String = prefs.getString(KEY_SERVER, "") ?: ""
    fun streamKey(): String = prefs.getString(KEY_STREAM, "") ?: ""
    fun videoUri(): Uri? = prefs.getString(KEY_VIDEO, null)?.let(Uri::parse)

    companion object {
        private const val KEY_SERVER = "server_url"
        private const val KEY_STREAM = "stream_key"
        private const val KEY_VIDEO = "video_uri"
    }
}
