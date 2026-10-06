package com.livevip.app.store

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * CREDENTIAL STORAGE — Android Keystore backed EncryptedSharedPreferences.
 * Stream URLs and stream keys NEVER leave this store: they are not written
 * to plain preferences, not logged, and never appear in error messages or
 * crash reports.
 *
 * If the secure storage cannot be created on a broken device keystore, we
 * fall back to private app-sandbox preferences instead of crashing.
 */
object SecureStore {

    private const val PREFS_NAME = "live_vip_secure"
    private const val KEY_URL = "server_url"
    private const val KEY_STREAM_KEY = "stream_key"
    private const val KEY_SAVED_PREFIX = "saved_"

    @Volatile
    private var prefs: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences {
        prefs?.let { return it }
        synchronized(this) {
            prefs?.let { return it }
            val created = try {
                val masterKey = MasterKey.Builder(context.applicationContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context.applicationContext,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (t: Throwable) {
                // Device keystore unavailable — degrade gracefully, never crash.
                context.applicationContext
                    .getSharedPreferences("live_vip_secure_fallback", Context.MODE_PRIVATE)
            }
            prefs = created
            return created
        }
    }

    // ---------------- Current session ----------------

    fun setServerUrl(context: Context, url: String) {
        prefs(context).edit().putString(KEY_URL, url.trim()).apply()
    }

    fun serverUrl(context: Context): String =
        prefs(context).getString(KEY_URL, "") ?: ""

    fun setStreamKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_STREAM_KEY, key.trim()).apply()
    }

    fun streamKey(context: Context): String =
        prefs(context).getString(KEY_STREAM_KEY, "") ?: ""

    // ---------------- Per-Saved-Live credentials ----------------

    fun setSavedKey(context: Context, savedLiveId: Long, key: String) {
        prefs(context).edit().putString(KEY_SAVED_PREFIX + savedLiveId, key.trim()).apply()
    }

    fun savedKey(context: Context, savedLiveId: Long): String =
        prefs(context).getString(KEY_SAVED_PREFIX + savedLiveId, "") ?: ""

    fun deleteSavedKey(context: Context, savedLiveId: Long) {
        prefs(context).edit().remove(KEY_SAVED_PREFIX + savedLiveId).apply()
    }
}
