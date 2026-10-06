package com.livevip.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

/** A saved streaming destination: name + URL + key + default quality. */
data class StreamProfile(
    val id: Long,
    var name: String,
    var url: String,
    var key: String,
    var platformIndex: Int,
    var width: Int,
    var height: Int,
    var fps: Int,
    var bitrateKbps: Int
)

/**
 * Stream Profiles stored in Android-Keystore-backed encrypted storage
 * (profiles contain stream keys). Falls back to app-private prefs when
 * the device keystore is unavailable — never crashes.
 */
class StreamProfilesRepository private constructor(context: Context) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                "live_vip_profiles",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (t: Throwable) {
            appContext.getSharedPreferences("live_vip_profiles_fb", Context.MODE_PRIVATE)
        }
    }

    fun all(): List<StreamProfile> {
        val raw = prefs.getString(KEY_PROFILES, "[]") ?: "[]"
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                StreamProfile(
                    id = o.optLong("id"),
                    name = o.optString("name"),
                    url = o.optString("url"),
                    key = o.optString("key"),
                    platformIndex = o.optInt("platformIndex"),
                    width = o.optInt("width", 1280),
                    height = o.optInt("height", 720),
                    fps = o.optInt("fps", 30),
                    bitrateKbps = o.optInt("bitrateKbps", 2500)
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(profile: StreamProfile) {
        val others = all().filter { it.id != profile.id && it.name != profile.name }
        persist(others + profile)
    }

    fun delete(id: Long) {
        persist(all().filter { it.id != id })
    }

    private fun persist(profiles: List<StreamProfile>) {
        val array = JSONArray()
        profiles.forEach { p ->
            array.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("url", p.url)
                    .put("key", p.key)
                    .put("platformIndex", p.platformIndex)
                    .put("width", p.width)
                    .put("height", p.height)
                    .put("fps", p.fps)
                    .put("bitrateKbps", p.bitrateKbps)
            )
        }
        prefs.edit().putString(KEY_PROFILES, array.toString()).apply()
    }

    companion object {
        private const val KEY_PROFILES = "profiles"

        @Volatile
        private var instance: StreamProfilesRepository? = null

        fun get(context: Context): StreamProfilesRepository =
            instance ?: synchronized(this) {
                instance ?: StreamProfilesRepository(context.applicationContext)
                    .also { instance = it }
            }
    }
}
