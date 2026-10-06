package com.livevip.app.data

import android.content.Context
import com.livevip.app.data.db.LiveVipDatabase
import com.livevip.app.streaming.BroadcastPlan
import com.livevip.app.streaming.DestinationConfig
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Async access layer over [LiveVipDatabase] + [SecretsVault].
 *
 * Rule enforced here: destination STREAM KEYS are stored ONLY in the vault and
 * only ever surfaced inside a runtime [DestinationConfig] for the engine —
 * they never appear in project listings, logs, or the database.
 */
class ProjectRepository private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val db = LiveVipDatabase(appContext)
    private val vault = SecretsVault.get(appContext)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    fun <T> async(work: (ProjectRepository) -> T, done: (T) -> Unit) {
        executor.execute {
            val result = work(this)
            android.os.Handler(android.os.Looper.getMainLooper()).post { done(result) }
        }
    }

    // ---------------- Projects ----------------

    fun allProjects(): List<Project> = db.allProjects()

    fun recentProjects(limit: Int = 3): List<Project> =
        db.allProjects().sortedByDescending { it.lastStreamedAt }.take(limit)

    fun projectBundle(projectId: Long): ProjectBundle? = db.bundle(projectId)

    fun saveProject(
        project: Project,
        destinations: List<Destination>,
        destinationKeys: Map<Long, String>,
        playlist: List<PlaylistItem>
    ): Long {
        val id = if (project.id == 0L) {
            db.insertProject(project)
        } else {
            db.updateProject(project)
            project.id
        }
        db.saveDestinations(id, destinations)
        db.savePlaylist(id, playlist)

        // Persist keys in the encrypted vault keyed by the SAVED destination ids.
        val saved = db.destinationsFor(id)
        saved.forEach { savedDest ->
            val original = destinations.firstOrNull { it.name == savedDest.name }
            val key = original?.let { destinationKeys[it.id] } ?: ""
            if (key.isNotBlank()) vault.setDestinationKey(savedDest.id, key)
        }
        return id
    }

    fun savePlaylist(projectId: Long, items: List<PlaylistItem>) {
        db.savePlaylist(projectId, items)
    }

    fun deleteProject(project: Project) {
        db.destinationsFor(project.id).forEach { vault.removeDestinationKey(it.id) }
        db.deleteProject(project.id)
    }

    // ---------------- Sessions / history ----------------

    fun startSession(session: StreamSession): Long = db.insertSession(session)

    fun finishSession(sessionId: Long, durationSec: Long, loopCount: Int, status: String) {
        db.finishSession(sessionId, System.currentTimeMillis(), durationSec, loopCount, status)
    }

    fun historyFor(projectId: Long): List<StreamSession> = db.sessionsFor(projectId)

    fun recordStreamStarted(projectId: Long) {
        db.touchProjectStreamed(projectId, System.currentTimeMillis())
    }

    fun recordStreamStats(projectId: Long, durationSec: Long, loopCount: Int) {
        db.updateProjectStreamStats(projectId, durationSec, loopCount)
    }

    // ---------------- Broadcast plan assembly ----------------

    /**
     * Resolve a saved project into a runtime [BroadcastPlan] (keys injected
     * from the vault). Returns null + reason when the project is incomplete.
     */
    fun buildPlan(bundle: ProjectBundle): Pair<BroadcastPlan?, String?> {
        val project = bundle.project

        val destinations = bundle.destinations
            .filter { it.enabled }
            .map { dest ->
                DestinationConfig(
                    id = dest.id,
                    name = dest.name,
                    platform = dest.platform,
                    url = dest.url,
                    streamKey = vault.destinationKey(dest.id)
                )
            }
        if (destinations.isEmpty()) return null to "This project has no enabled destinations"

        val relay = vault.relayApiUrl.takeIf { it.isNotBlank() }?.let {
            RelayEndpointInfo(it, vault.relayToken)
        }

        val plan = BroadcastPlan(
            projectId = project.id,
            projectName = project.name,
            mode = if (project.isVideoMode) {
                com.livevip.app.streaming.LiveStreamingManager.Mode.VIDEO
            } else {
                com.livevip.app.streaming.LiveStreamingManager.Mode.CAMERA
            },
            playlist = emptyList(), // filled by the caller with resolved video items
            loopMode = project.loopMode,
            destinations = destinations,
            broadcastMode = project.broadcastMode,
            quality = com.livevip.app.streaming.VideoQuality(
                width = project.width,
                height = project.height,
                fps = project.fps,
                videoBitrateKbps = project.videoBitrateKbps
            ),
            audio = com.livevip.app.streaming.AudioConfig(
                sampleRate = project.sampleRate,
                stereo = project.stereo,
                audioBitrateKbps = project.audioBitrateKbps,
                videoVolume = project.videoVolume,
                micVolume = project.micVolume,
                micEnabled = project.micEnabledByDefault,
                echoCanceler = project.echoCanceler,
                noiseSuppressor = project.noiseSuppressor
            ),
            relay = relay?.let {
                com.livevip.app.streaming.RelayEndpoint(it.apiUrl, it.token)
            },
            metadata = com.livevip.app.streaming.LiveMetadata(
                title = project.title,
                description = project.description
            )
        )
        return plan to null
    }

    data class RelayEndpointInfo(val apiUrl: String, val token: String)

    companion object {
        @Volatile
        private var instance: ProjectRepository? = null

        fun get(context: Context): ProjectRepository =
            instance ?: synchronized(this) {
                instance ?: ProjectRepository(context.applicationContext)
                    .also { instance = it }
            }
    }
}
