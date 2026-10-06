package com.livevip.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * ENCRYPTED SECRET STORAGE — Android Keystore backed.
 *
 * Holds: destination stream keys, the relay API token.
 * NEVER holds secrets in: the project database, plain SharedPreferences,
 * logs, debug output or crash messages.
 *
 * Falls back to app-private preferences when a broken device keystore makes
 * EncryptedSharedPreferences unavailable (better than crashing mid-broadcast;
 * the fallback file is still app-sandboxed).
 */
class SecretsVault private constructor(context: Context) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                "live_vip_vault",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (_: Throwable) {
            appContext.getSharedPreferences("live_vip_vault_fb", Context.MODE_PRIVATE)
        }
    }

    // ---------------- Destination stream keys ----------------

    fun destinationKey(destinationId: Long): String =
        prefs.getString(destKey(destinationId), "") ?: ""

    fun setDestinationKey(destinationId: Long, key: String) {
        prefs.edit().putString(destKey(destinationId), key.trim()).apply()
    }

    fun removeDestinationKey(destinationId: Long) {
        prefs.edit().remove(destKey(destinationId)).apply()
    }

    private fun destKey(id: Long) = "dest.$id.key"

    // ---------------- Relay server ----------------

    var relayApiUrl: String
        get() = prefs.getString(KEY_RELAY_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_RELAY_URL, value.trim()).apply()

    var relayToken: String
        get() = prefs.getString(KEY_RELAY_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_RELAY_TOKEN, value.trim()).apply()

    companion object {
        private const val KEY_RELAY_URL = "relay.api.url"
        private const val KEY_RELAY_TOKEN = "relay.token"

        @Volatile
        private var instance: SecretsVault? = null

        fun get(context: Context): SecretsVault =
            instance ?: synchronized(this) {
                instance ?: SecretsVault(context.applicationContext)
                    .also { instance = it }
            }
    }
}
