package com.livevip.app.data.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.livevip.app.data.Destination
import com.livevip.app.data.PlaylistItem
import com.livevip.app.data.Project
import com.livevip.app.data.ProjectBundle
import com.livevip.app.data.StreamSession
import com.livevip.app.overlay.OverlayConfig
import com.livevip.app.overlay.SceneConfig
import com.livevip.app.streaming.BroadcastMode
import com.livevip.app.streaming.LoopMode
import com.livevip.app.streaming.StreamPlatform

/**
 * PROJECT DATABASE — hand-rolled SQLite (no annotation processors → a
 * dependency-light, version-lock-free, CI-friendly build).
 *
 * Tables: projects, destinations, playlist_items, stream_sessions.
 * Secrets (stream keys / relay tokens) are NOT here — see SecretsVault.
 *
 * Threading: all methods are synchronous; callers use a background executor.
 * SQLiteOpenHelper serializes access on a single connection.
 */
class LiveVipDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "live_vip_projects.db"
        private const val DB_VERSION = 2
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE projects (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              name TEXT NOT NULL,
              mode TEXT NOT NULL DEFAULT 'VIDEO',
              loop_mode TEXT NOT NULL DEFAULT 'LOOP_ALL',
              broadcast_mode TEXT NOT NULL DEFAULT 'DIRECT',
              title TEXT NOT NULL DEFAULT '',
              description TEXT NOT NULL DEFAULT '',
              width INTEGER NOT NULL DEFAULT 1280,
              height INTEGER NOT NULL DEFAULT 720,
              fps INTEGER NOT NULL DEFAULT 30,
              video_bitrate INTEGER NOT NULL DEFAULT 2500,
              audio_bitrate INTEGER NOT NULL DEFAULT 128,
              sample_rate INTEGER NOT NULL DEFAULT 44100,
              stereo INTEGER NOT NULL DEFAULT 1,
              video_volume REAL NOT NULL DEFAULT 1.0,
              mic_volume REAL NOT NULL DEFAULT 0.6,
              mic_default INTEGER NOT NULL DEFAULT 0,
              echo_canceler INTEGER NOT NULL DEFAULT 1,
              noise_suppressor INTEGER NOT NULL DEFAULT 1,
              overlays_json TEXT NOT NULL DEFAULT '[]',
              scenes_json TEXT NOT NULL DEFAULT '[]',
              canvas_json TEXT NOT NULL DEFAULT '',
              last_streamed_at INTEGER NOT NULL DEFAULT 0,
              last_duration_sec INTEGER NOT NULL DEFAULT 0,
              last_loop_count INTEGER NOT NULL DEFAULT 0,
              total_stream_count INTEGER NOT NULL DEFAULT 0,
              created_at INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE destinations (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              project_id INTEGER NOT NULL,
              name TEXT NOT NULL,
              platform TEXT NOT NULL DEFAULT 'CUSTOM_RTMP',
              url TEXT NOT NULL DEFAULT '',
              enabled INTEGER NOT NULL DEFAULT 1,
              title TEXT NOT NULL DEFAULT '',
              description TEXT NOT NULL DEFAULT '',
              sort_order INTEGER NOT NULL DEFAULT 0,
              FOREIGN KEY(project_id) REFERENCES projects(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE playlist_items (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              project_id INTEGER NOT NULL,
              video_id INTEGER NOT NULL,
              sort_order INTEGER NOT NULL DEFAULT 0,
              FOREIGN KEY(project_id) REFERENCES projects(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE stream_sessions (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              project_id INTEGER NOT NULL,
              project_name TEXT NOT NULL DEFAULT '',
              started_at INTEGER NOT NULL,
              ended_at INTEGER NOT NULL DEFAULT 0,
              duration_sec INTEGER NOT NULL DEFAULT 0,
              loop_count INTEGER NOT NULL DEFAULT 0,
              destinations_count INTEGER NOT NULL DEFAULT 1,
              broadcast_mode TEXT NOT NULL DEFAULT 'DIRECT',
              status TEXT NOT NULL DEFAULT 'LIVE',
              notes TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_dest_project ON destinations(project_id)")
        db.execSQL("CREATE INDEX idx_playlist_project ON playlist_items(project_id)")
        db.execSQL("CREATE INDEX idx_sessions_project ON stream_sessions(project_id)")
        createV2Tables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Schema v1 → v2 (Part 2): canvas + overlay templates. Never drop data.
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE projects ADD COLUMN canvas_json TEXT NOT NULL DEFAULT ''")
            createV2Tables(db)
        }
    }

    private fun createV2Tables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS overlay_templates (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              name TEXT NOT NULL,
              layers_json TEXT NOT NULL DEFAULT '[]',
              created_at INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
    }

    // ------------------------------------------------------------------
    // Projects
    // ------------------------------------------------------------------

    fun insertProject(project: Project): Long =
        writableDatabase.insert("projects", null, projectValues(project))

    fun updateProject(project: Project) {
        writableDatabase.update(
            "projects", projectValues(project), "id = ?", arrayOf(project.id.toString())
        )
    }

    fun deleteProject(projectId: Long) {
        val db = writableDatabase
        db.delete("destinations", "project_id = ?", arrayOf(projectId.toString()))
        db.delete("playlist_items", "project_id = ?", arrayOf(projectId.toString()))
        db.delete("projects", "id = ?", arrayOf(projectId.toString()))
    }

    fun allProjects(): List<Project> {
        val list = mutableListOf<Project>()
        readableDatabase.query(
            "projects", null, null, null, null, null,
            "last_streamed_at DESC, created_at DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) list.add(projectFromCursor(cursor))
        }
        return list
    }

    fun projectById(id: Long): Project? =
        readableDatabase.query(
            "projects", null, "id = ?", arrayOf(id.toString()), null, null, null
        ).use { cursor -> if (cursor.moveToFirst()) projectFromCursor(cursor) else null }

    // ------------------------------------------------------------------
    // Destinations / playlist / bundle
    // ------------------------------------------------------------------

    fun destinationsFor(projectId: Long): List<Destination> {
        val list = mutableListOf<Destination>()
        readableDatabase.query(
            "destinations", null, "project_id = ?", arrayOf(projectId.toString()),
            null, null, "sort_order ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) list.add(destinationFromCursor(cursor))
        }
        return list
    }

    fun playlistFor(projectId: Long): List<PlaylistItem> {
        val list = mutableListOf<PlaylistItem>()
        readableDatabase.query(
            "playlist_items", null, "project_id = ?", arrayOf(projectId.toString()),
            null, null, "sort_order ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) list.add(playlistFromCursor(cursor))
        }
        return list
    }

    fun bundle(projectId: Long): ProjectBundle? {
        val project = projectById(projectId) ?: return null
        return ProjectBundle(
            project = project,
            destinations = destinationsFor(projectId),
            playlist = playlistFor(projectId)
        )
    }

    fun saveDestinations(projectId: Long, destinations: List<Destination>) {
        val db = writableDatabase
        db.delete("destinations", "project_id = ?", arrayOf(projectId.toString()))
        destinations.forEachIndexed { index, dest ->
            db.insert(
                "destinations", null, ContentValues().apply {
                    put("project_id", projectId)
                    put("name", dest.name)
                    put("platform", dest.platform.name)
                    put("url", dest.url)
                    put("enabled", if (dest.enabled) 1 else 0)
                    put("title", dest.title)
                    put("description", dest.description)
                    put("sort_order", index)
                }
            )
        }
    }

    fun savePlaylist(projectId: Long, items: List<PlaylistItem>) {
        val db = writableDatabase
        db.delete("playlist_items", "project_id = ?", arrayOf(projectId.toString()))
        items.forEachIndexed { index, item ->
            db.insert(
                "playlist_items", null, ContentValues().apply {
                    put("project_id", projectId)
                    put("video_id", item.videoId)
                    put("sort_order", index)
                }
            )
        }
    }

    fun touchProjectStreamed(projectId: Long, at: Long) {
        writableDatabase.execSQL(
            "UPDATE projects SET last_streamed_at = ?, total_stream_count = total_stream_count + 1 " +
                "WHERE id = ?",
            arrayOf(at, projectId)
        )
    }

    fun updateProjectStreamStats(projectId: Long, durationSec: Long, loopCount: Int) {
        writableDatabase.execSQL(
            "UPDATE projects SET last_duration_sec = ?, last_loop_count = ? WHERE id = ?",
            arrayOf(durationSec, loopCount, projectId)
        )
    }

    // ------------------------------------------------------------------
    // Stream sessions (history)
    // ------------------------------------------------------------------

    fun insertSession(session: StreamSession): Long =
        writableDatabase.insert("stream_sessions", null, ContentValues().apply {
            put("project_id", session.projectId)
            put("project_name", session.projectName)
            put("started_at", session.startedAt)
            put("ended_at", session.endedAt)
            put("duration_sec", session.durationSec)
            put("loop_count", session.loopCount)
            put("destinations_count", session.destinationsCount)
            put("broadcast_mode", session.broadcastMode.name)
            put("status", session.status)
            put("notes", session.notes)
        })

    fun finishSession(
        sessionId: Long,
        endedAt: Long,
        durationSec: Long,
        loopCount: Int,
        status: String
    ) {
        writableDatabase.update(
            "stream_sessions", ContentValues().apply {
                put("ended_at", endedAt)
                put("duration_sec", durationSec)
                put("loop_count", loopCount)
                put("status", status)
            },
            "id = ?", arrayOf(sessionId.toString())
        )
    }

    fun sessionsFor(projectId: Long, limit: Int = 50): List<StreamSession> {
        val list = mutableListOf<StreamSession>()
        readableDatabase.query(
            "stream_sessions", null, "project_id = ?", arrayOf(projectId.toString()),
            null, null, "started_at DESC", limit.toString()
        ).use { cursor ->
            while (cursor.moveToNext()) list.add(sessionFromCursor(cursor))
        }
        return list
    }

    // ------------------------------------------------------------------
    // Overlay templates (Part 2 — reusable layer sets across projects)
    // ------------------------------------------------------------------

    fun insertTemplate(name: String, layersJson: String): Long =
        writableDatabase.insert(
            "overlay_templates", null, ContentValues().apply {
                put("name", name)
                put("layers_json", layersJson)
                put("created_at", System.currentTimeMillis())
            }
        )

    fun templates(): List<OverlayTemplate> {
        val list = mutableListOf<OverlayTemplate>()
        readableDatabase.query(
            "overlay_templates", null, null, null, null, null, "created_at DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list += OverlayTemplate(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                    layersJson = cursor.getString(cursor.getColumnIndexOrThrow("layers_json")),
                    createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at"))
                )
            }
        }
        return list
    }

    fun deleteTemplate(id: Long) {
        writableDatabase.delete("overlay_templates", "id = ?", arrayOf(id.toString()))
    }

    // ------------------------------------------------------------------
    // Row mapping
    // ------------------------------------------------------------------

    private fun projectValues(p: Project): ContentValues = ContentValues().apply {
        if (p.id != 0L) put("id", p.id)
        put("name", p.name)
        put("mode", p.mode)
        put("loop_mode", p.loopMode.name)
        put("broadcast_mode", p.broadcastMode.name)
        put("title", p.title)
        put("description", p.description)
        put("width", p.width)
        put("height", p.height)
        put("fps", p.fps)
        put("video_bitrate", p.videoBitrateKbps)
        put("audio_bitrate", p.audioBitrateKbps)
        put("sample_rate", p.sampleRate)
        put("stereo", if (p.stereo) 1 else 0)
        put("video_volume", p.videoVolume.toDouble())
        put("mic_volume", p.micVolume.toDouble())
        put("mic_default", if (p.micEnabledByDefault) 1 else 0)
        put("echo_canceler", if (p.echoCanceler) 1 else 0)
        put("noise_suppressor", if (p.noiseSuppressor) 1 else 0)
        put("overlays_json", OverlayConfig.listToJson(p.overlays))
        put("scenes_json", SceneConfig.listToJson(p.scenes))
        put("canvas_json", p.canvasJson)
        put("created_at", p.createdAt)
    }

    private fun projectFromCursor(c: android.database.Cursor): Project = Project(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        name = c.getString(c.getColumnIndexOrThrow("name")),
        mode = c.getString(c.getColumnIndexOrThrow("mode")),
        loopMode = LoopMode.from(c.getString(c.getColumnIndexOrThrow("loop_mode"))),
        broadcastMode = runCatching {
            BroadcastMode.valueOf(c.getString(c.getColumnIndexOrThrow("broadcast_mode")))
        }.getOrDefault(BroadcastMode.DIRECT),
        title = c.getString(c.getColumnIndexOrThrow("title")),
        description = c.getString(c.getColumnIndexOrThrow("description")),
        width = c.getInt(c.getColumnIndexOrThrow("width")),
        height = c.getInt(c.getColumnIndexOrThrow("height")),
        fps = c.getInt(c.getColumnIndexOrThrow("fps")),
        videoBitrateKbps = c.getInt(c.getColumnIndexOrThrow("video_bitrate")),
        audioBitrateKbps = c.getInt(c.getColumnIndexOrThrow("audio_bitrate")),
        sampleRate = c.getInt(c.getColumnIndexOrThrow("sample_rate")),
        stereo = c.getInt(c.getColumnIndexOrThrow("stereo")) == 1,
        videoVolume = c.getFloat(c.getColumnIndexOrThrow("video_volume")),
        micVolume = c.getFloat(c.getColumnIndexOrThrow("mic_volume")),
        micEnabledByDefault = c.getInt(c.getColumnIndexOrThrow("mic_default")) == 1,
        echoCanceler = c.getInt(c.getColumnIndexOrThrow("echo_canceler")) == 1,
        noiseSuppressor = c.getInt(c.getColumnIndexOrThrow("noise_suppressor")) == 1,
        overlays = OverlayConfig.listFromJson(c.getString(c.getColumnIndexOrThrow("overlays_json"))),
        scenes = SceneConfig.listFromJson(c.getString(c.getColumnIndexOrThrow("scenes_json"))),
        canvasJson = runCatching {
            c.getString(c.getColumnIndexOrThrow("canvas_json"))
        }.getOrDefault(""),
        lastStreamedAt = c.getLong(c.getColumnIndexOrThrow("last_streamed_at")),
        lastDurationSec = c.getLong(c.getColumnIndexOrThrow("last_duration_sec")),
        lastLoopCount = c.getInt(c.getColumnIndexOrThrow("last_loop_count")),
        totalStreamCount = c.getInt(c.getColumnIndexOrThrow("total_stream_count")),
        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"))
    )

    private fun destinationFromCursor(c: android.database.Cursor): Destination = Destination(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        projectId = c.getLong(c.getColumnIndexOrThrow("project_id")),
        name = c.getString(c.getColumnIndexOrThrow("name")),
        platform = runCatching {
            StreamPlatform.valueOf(c.getString(c.getColumnIndexOrThrow("platform")))
        }.getOrDefault(StreamPlatform.CUSTOM_RTMP),
        url = c.getString(c.getColumnIndexOrThrow("url")),
        enabled = c.getInt(c.getColumnIndexOrThrow("enabled")) == 1,
        title = c.getString(c.getColumnIndexOrThrow("title")),
        description = c.getString(c.getColumnIndexOrThrow("description")),
        sortOrder = c.getInt(c.getColumnIndexOrThrow("sort_order"))
    )

    private fun playlistFromCursor(c: android.database.Cursor): PlaylistItem = PlaylistItem(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        projectId = c.getLong(c.getColumnIndexOrThrow("project_id")),
        videoId = c.getLong(c.getColumnIndexOrThrow("video_id")),
        sortOrder = c.getInt(c.getColumnIndexOrThrow("sort_order"))
    )

    private fun sessionFromCursor(c: android.database.Cursor): StreamSession = StreamSession(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        projectId = c.getLong(c.getColumnIndexOrThrow("project_id")),
        projectName = c.getString(c.getColumnIndexOrThrow("project_name")),
        startedAt = c.getLong(c.getColumnIndexOrThrow("started_at")),
        endedAt = c.getLong(c.getColumnIndexOrThrow("ended_at")),
        durationSec = c.getLong(c.getColumnIndexOrThrow("duration_sec")),
        loopCount = c.getInt(c.getColumnIndexOrThrow("loop_count")),
        destinationsCount = c.getInt(c.getColumnIndexOrThrow("destinations_count")),
        broadcastMode = runCatching {
            BroadcastMode.valueOf(c.getString(c.getColumnIndexOrThrow("broadcast_mode")))
        }.getOrDefault(BroadcastMode.DIRECT),
        status = c.getString(c.getColumnIndexOrThrow("status")),
        notes = c.getString(c.getColumnIndexOrThrow("notes"))
    )
}
