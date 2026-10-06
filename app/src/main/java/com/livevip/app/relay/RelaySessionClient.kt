package com.livevip.app.relay

import android.util.Log
import com.livevip.app.streaming.BroadcastMode
import com.livevip.app.streaming.DestinationConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * LIVE VIP SMART RELAY — client side.
 *
 *   PHONE ── ONE upstream RTMP ──► RELAY ──┬──► Destination A
 *                                         ├──► Destination B
 *                                         └──► Destination C
 *
 * Before going live the app registers the destination list with the relay
 * over HTTPS (token auth); the relay returns a private ingest URL + key.
 * The phone then streams ONE upstream regardless of destination count.
 *
 * Stream keys only travel over HTTPS to the user's OWN relay server.
 * They are never logged (all logging is sanitized through [Sanitizer]).
 */
object RelaySessionClient {

    private const val TAG = "LiveVipRelay"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000

    /** Server-assigned session with its private ingest point. */
    data class Session(
        val sessionId: String,
        val ingestUrl: String,
        val ingestKey: String,
        val httpBase: String
    )

    /** Live status of one fan-out destination as reported by the relay. */
    data class DestinationStatus(
        val name: String,
        val state: String,        // connecting | live | reconnecting | failed | stopped
        val restarts: Int,
        val detail: String?
    )

    data class SessionStatus(
        val sessionId: String,
        val upstream: String,     // waiting | publishing | ended
        val destinations: List<DestinationStatus>
    )

    sealed class Result<out T> {
        data class Ok<T>(val value: T) : Result<T>()
        data class Error(val message: String) : Result<Nothing>()
    }

    // ------------------------------------------------------------------
    // JSON wire format (pure functions — unit tested)
    // ------------------------------------------------------------------

    object Wire {
        fun buildCreateBody(projectName: String, destinations: List<DestinationConfig>): String {
            val destArray = JSONArray()
            destinations.forEach { d ->
                destArray.put(
                    JSONObject()
                        .put("name", d.name)
                        .put("platform", d.platform.name)
                        .put("url", d.url)          // base url only
                        .put("key", d.streamKey)    // secret — HTTPS only, never logged
                )
            }
            return JSONObject()
                .put("name", projectName)
                .put("destinations", destArray)
                .toString()
        }

        fun parseCreateResponse(body: String): Session? = try {
            val o = JSONObject(body)
            val ingest = o.optJSONObject("ingest") ?: return null
            Session(
                sessionId = o.optString("sessionId"),
                ingestUrl = ingest.optString("url"),
                ingestKey = ingest.optString("key"),
                httpBase = o.optString("httpBase", "")
            )
        } catch (_: Throwable) {
            null
        }

        fun parseStatusResponse(body: String): SessionStatus? = try {
            val o = JSONObject(body)
            val dests = mutableListOf<DestinationStatus>()
            val array = o.optJSONArray("destinations") ?: JSONArray()
            (0 until array.length()).forEach { i ->
                val d = array.optJSONObject(i) ?: return@forEach
                dests += DestinationStatus(
                    name = d.optString("name"),
                    state = d.optString("state"),
                    restarts = d.optInt("restarts"),
                    detail = if (d.has("detail")) d.optString("detail") else null
                )
            }
            SessionStatus(
                sessionId = o.optString("sessionId"),
                upstream = o.optString("upstream"),
                destinations = dests
            )
        } catch (_: Throwable) {
            null
        }
    }

    // ------------------------------------------------------------------
    // HTTP (HttpURLConnection — zero extra dependencies)
    // ------------------------------------------------------------------

    /** Register destinations and obtain the private ingest point. */
    fun createSession(
        apiUrl: String,
        token: String,
        projectName: String,
        destinations: List<DestinationConfig>
    ): Result<Session> {
        val body = Wire.buildCreateBody(projectName, destinations)
        return when (val response = http("POST", "$apiUrl/api/v1/sessions", token, body)) {
            is Result.Error -> response
            is Result.Ok -> {
                val session = Wire.parseCreateResponse(response.value)
                if (session == null || session.sessionId.isBlank() || session.ingestUrl.isBlank()) {
                    Result.Error("Relay returned an invalid session response")
                } else {
                    Result.Ok(session)
                }
            }
        }
    }

    /** Poll the real per-destination fan-out status (no invented data). */
    fun sessionStatus(apiUrl: String, token: String, sessionId: String): Result<SessionStatus> {
        return when (val response = http("GET", "$apiUrl/api/v1/sessions/$sessionId", token, null)) {
            is Result.Error -> response
            is Result.Ok -> {
                val status = Wire.parseStatusResponse(response.value)
                if (status == null) Result.Error("Relay returned an invalid status response")
                else Result.Ok(status)
            }
        }
    }

    /** End the session; the relay stops all fan-out processes. */
    fun endSession(apiUrl: String, token: String, sessionId: String): Result<Unit> {
        return when (val r = http("DELETE", "$apiUrl/api/v1/sessions/$sessionId", token, null)) {
            is Result.Error -> r
            is Result.Ok -> Result.Ok(Unit)
        }
    }

    /** Quick health probe used by settings / pre-flight. */
    fun ping(apiUrl: String, token: String): Result<Unit> {
        return when (val r = http("GET", "$apiUrl/api/v1/health", token, null)) {
            is Result.Error -> r
            is Result.Ok -> Result.Ok(Unit)
        }
    }

    // ------------------------------------------------------------------

    private fun http(method: String, url: String, token: String, body: String?): Result<String> {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/json")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
            }
            if (body != null) {
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let {
                BufferedReader(InputStreamReader(it)).use { reader -> reader.readText() }
            } ?: ""
            if (code in 200..299) Result.Ok(text)
            else Result.Error("Relay HTTP $code: ${Sanitizer.shorten(text)}")
        } catch (t: Throwable) {
            if (BuildConfigRelayDebug.debug) Log.w(TAG, "relay call failed: ${t.message}")
            Result.Error("Relay unreachable: ${t.message ?: "network error"}")
        } finally {
            conn?.disconnect()
        }
    }
}

/** Never let full URLs (which contain keys) reach logs or messages. */
object Sanitizer {
    fun shorten(text: String, max: Int = 120): String {
        val stripped = text.replace(Regex("rtmps?://\\S+"), "rtmp://***")
        return if (stripped.length <= max) stripped else stripped.take(max) + "…"
    }
}

@Suppress("unused")
private object BuildConfigRelayDebug {
    const val debug = false
}
