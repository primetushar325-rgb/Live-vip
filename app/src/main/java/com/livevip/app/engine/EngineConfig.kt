package com.livevip.app.engine

/**
 * Immutable snapshot of everything needed to start one direct RTMP/RTMPS
 * session. The stream key lives ONLY here and inside encrypted storage —
 * it is never logged and never appears in error messages.
 */
data class EngineConfig(
    val url: String,
    val key: String,
    val videoWidth: Int,
    val videoHeight: Int,
    val fps: Int,
    val videoBitrateKbps: Int,
    val keyframeIntervalSec: Int,
    val audioBitrateKbps: Int,
    val sampleRate: Int,
    val stereo: Boolean,
    val echoCanceler: Boolean,
    val noiseSuppressor: Boolean,
    val autoReconnect: Boolean = true,
    val maxReconnectAttempts: Int = 5
) {
    /**
     * Combine RTMP/RTMPS url + stream key correctly:
     * rtmp://a.rtmp.youtube.com/live2 + abcd-1234
     *   → rtmp://a.rtmp.youtube.com/live2/abcd-1234
     */
    fun fullUrl(): String {
        val base = url.trim().trimEnd('/')
        val k = key.trim()
        return if (k.isEmpty()) base else "$base/$k"
    }

    fun isValidUrl(): Boolean {
        val u = url.trim().lowercase()
        val schemeOk = u.startsWith("rtmp://") || u.startsWith("rtmps://")
        return schemeOk && key.isNotBlank()
    }

    companion object {
        /**
         * Credentials never appear in surfaced messages: any rtmp(s) URL is
         * masked before it reaches the UI, a toast or a log line.
         */
        fun sanitize(text: String): String =
            text.replace(Regex("rtmps?://\\S+"), "rtmps://***")
    }
}
