package com.livevip.app.streaming

/**
 * NETWORK PRE-FLIGHT MATH — pure logic, unit tested.
 *
 * Required upload for a live stream:
 *
 *   video bitrate
 * + audio bitrate
 * + protocol overhead   (~10% RTMP/FLV + TCP)
 * + safety margin       (25% — live streaming must survive bursts)
 *
 * ONE stream, ONE destination — the phone uploads exactly once.
 */
object NetworkMath {

    /** RTMP/FLV/TCP overhead factor. */
    const val PROTOCOL_OVERHEAD = 0.10

    /** Safety margin factor on top of everything. */
    const val SAFETY_MARGIN = 0.25

    enum class Status {
        EXCELLENT, GOOD, TIGHT, INSUFFICIENT, UNKNOWN
    }

    /** What the phone must upload, in kbps (video + audio + overhead + margin). */
    fun requiredUploadKbps(videoKbps: Int, audioKbps: Int): Int {
        val base = videoKbps + audioKbps
        return ((base * (1 + PROTOCOL_OVERHEAD)) * (1 + SAFETY_MARGIN)).toInt()
    }

    data class Assessment(
        val requiredUploadKbps: Int,
        val availableUploadKbps: Long?,
        val marginKbps: Long?,
        val status: Status,
        val summary: String
    )

    /**
     * @param availableUploadKbps null when no honest estimate exists — the UI then
     *        shows "estimate unavailable — upload will be monitored live" instead
     *        of inventing a number.
     */
    fun assess(
        videoKbps: Int,
        audioKbps: Int,
        availableUploadKbps: Long?
    ): Assessment {
        val required = requiredUploadKbps(videoKbps, audioKbps)
        val status: Status
        val margin: Long?
        val summary: String
        if (availableUploadKbps == null) {
            status = Status.UNKNOWN
            margin = null
            summary = "Required ≈ $required kbps upload. " +
                "Upload estimate unavailable — real throughput will be monitored live."
        } else {
            margin = availableUploadKbps - required
            val ratio = if (required > 0) availableUploadKbps.toDouble() / required else 99.0
            status = when {
                ratio >= 2.0 -> Status.EXCELLENT
                ratio >= 1.3 -> Status.GOOD
                ratio >= 1.0 -> Status.TIGHT
                else -> Status.INSUFFICIENT
            }
            summary = when (status) {
                Status.EXCELLENT -> "Excellent — ample upload headroom."
                Status.GOOD -> "Good — comfortable upload margin."
                Status.TIGHT -> "Tight — consider a lower quality preset."
                Status.INSUFFICIENT -> "Not enough upload — lower the quality preset."
                Status.UNKNOWN -> ""
            }
        }
        return Assessment(
            requiredUploadKbps = required,
            availableUploadKbps = availableUploadKbps,
            marginKbps = margin,
            status = status,
            summary = summary
        )
    }
}
