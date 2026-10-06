package com.livevip.app.data

import android.content.Context

/**
 * One-time migration from the pre-project ("quick live") app version:
 *
 *  - saved stream URL + key (SettingsRepository, already encrypted) become a
 *    "Quick Live" project with ONE destination — the exact legacy setup keeps
 *    working inside the new project system;
 *  - existing video library selection becomes the project's playlist;
 *  - legacy stream profiles become additional destinations of that project.
 *
 * Migration never deletes legacy data; it copies it. Keys move into the
 * SecretsVault (still Keystore-encrypted).
 */
object LegacyMigrator {

    private const val PREFS = "live_vip_migration"
    private const val KEY_DONE = "migrated_v1"

    fun migrateIfNeeded(context: Context) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DONE, false)) return
        prefs.edit().putBoolean(KEY_DONE, true).apply()

        try {
            val settings = SettingsRepository.get(appContext)
            val vault = SecretsVault.get(appContext)
            val videos = com.livevip.app.media.VideoRepository.get(appContext)
            val profiles = StreamProfilesRepository.get(appContext)
            val repo = ProjectRepository.get(appContext)

            val savedUrl = settings.streamUrl
            if (savedUrl.isBlank()) return // nothing legacy to carry over

            val destinations = mutableListOf<Destination>()
            // Primary legacy destination (saved url/key).
            destinations += Destination(
                id = 0L,
                name = "Primary",
                platform = StreamPlatform.entries.getOrElse(
                    settings.platformIndex.coerceIn(0, StreamPlatform.entries.size - 1)
                ) { StreamPlatform.CUSTOM_RTMP },
                url = savedUrl,
                enabled = true
            )
            // Legacy saved profiles as additional destinations.
            val extraProfiles = profiles.all().take(9).filter {
                it.url.isNotBlank() && it.url != savedUrl
            }
            extraProfiles.forEachIndexed { i, p ->
                destinations += Destination(
                    id = (i + 1).toLong(),
                    name = p.name,
                    platform = StreamPlatform.entries.getOrElse(
                        p.platformIndex.coerceIn(0, StreamPlatform.entries.size - 1)
                    ) { StreamPlatform.CUSTOM_RTMP },
                    url = p.url,
                    enabled = true
                )
            }

            val playlist = if (settings.selectedVideoId != 0L) {
                listOf(PlaylistItem(videoId = settings.selectedVideoId))
            } else emptyList()

            val project = Project(
                name = "Quick Live",
                mode = if (settings.lastMode == 1) "CAMERA" else "VIDEO",
                loopMode = com.livevip.app.streaming.LoopMode.LOOP_ONE,
                broadcastMode = com.livevip.app.streaming.BroadcastMode.DIRECT,
                width = settings.videoWidth,
                height = settings.videoHeight,
                fps = settings.videoFps,
                videoBitrateKbps = settings.videoBitrateKbps,
                audioBitrateKbps = settings.audioBitrateKbps,
                sampleRate = settings.audioSampleRate,
                stereo = settings.audioStereo,
                echoCanceler = settings.echoCanceler,
                noiseSuppressor = settings.noiseSuppressor
            )

            val keys = mutableMapOf<Long, String>()
            keys[0L] = settings.streamKey // primary destination key
            extraProfiles.forEachIndexed { i, p ->
                if (p.key.isNotBlank()) keys[(i + 1).toLong()] = p.key
            }

            val id = repo.saveProject(project, destinations, keys, playlist)
            // Point the app at the migrated project.
            settings.currentProjectId = id
        } catch (_: Throwable) {
            // Migration is best-effort; the legacy quick-live flow still works.
        }
    }
}
