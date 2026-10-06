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
 * DIRECT mode with N destinations needs ≈ N × that on the PHONE.
 * SMART RELAY keeps the phone at ONE upstream regardless of N — the relay
 * server's own uplink handles the fan-out (its capacity is a deployment
 * concern, surfaced in the relay dashboard, not hidden in the app).
 */
object NetworkMath {

    /** RTMP/FLV/TCP overhead factor. */
    const val PROTOCOL_OVERHEAD = 0.10

    /** Safety margin factor on top of everything. */
    const val SAFETY_MARGIN = 0.25

    enum class Status {
        EXCELLENT, GOOD, TIGHT, INSUFFICIENT, UNKNOWN
    }

    /** Per-upstream requirement in kbps (video + audio + overhead + margin). */
    fun requiredPerStreamKbps(videoKbps: Int, audioKbps: Int): Int {
        val base = videoKbps + audioKbps
        return ((base * (1 + PROTOCOL_OVERHEAD)) * (1 + SAFETY_MARGIN)).toInt()
    }

    /** What the PHONE must upload. */
    fun requiredPhoneUploadKbps(
        videoKbps: Int,
        audioKbps: Int,
        destinationCount: Int,
        mode: BroadcastMode
    ): Int {
        val perStream = requiredPerStreamKbps(videoKbps, audioKbps)
        return when (mode) {
            BroadcastMode.DIRECT -> perStream * destinationCount.coerceAtLeast(1)
            BroadcastMode.SMART_RELAY -> perStream // ONE upstream — relay fans out
        }
    }

    data class Assessment(
        val mode: BroadcastMode,
        val destinationCount: Int,
        val requiredPerStreamKbps: Int,
        val requiredPhoneUploadKbps: Int,
        val availableUploadKbps: Long?,
        val marginKbps: Long?,
        val status: Status,
        /** Honest recommendation for multi-destination setups. */
        val recommendedMode: BroadcastMode,
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
        destinationCount: Int,
        mode: BroadcastMode,
        availableUploadKbps: Long?
    ): Assessment {
        val perStream = requiredPerStreamKbps(videoKbps, audioKbps)
        val count = destinationCount.coerceAtLeast(1)
        val required = requiredPhoneUploadKbps(videoKbps, audioKbps, count, mode)

        // Recommendation logic (Smart Network Mode):
        // Multiple destinations should not multiply phone upload unless the
        // network can comfortably afford it.
        val recommended: BroadcastMode = when {
            count <= 1 -> BroadcastMode.DIRECT
            availableUploadKbps == null -> BroadcastMode.SMART_RELAY
            else -> {
                val directNeed = perStream * count
                // Direct is fine only with clear headroom (≥ GOOD).
                if (availableUploadKbps >= directNeed * 2) BroadcastMode.DIRECT
                else BroadcastMode.SMART_RELAY
            }
        }

        val status: Status
        val margin: Long?
        val summary: String
        if (availableUploadKbps == null) {
            status = Status.UNKNOWN
            margin = null
            summary = "Required ≈ ${required} kbps on this phone. " +
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
                Status.EXCELLENT -> "Excellent — ample headroom for $mode.label."
                Status.GOOD -> "Good — comfortable margin for $mode.label."
                Status.TIGHT -> "Tight — consider lower quality or Smart Relay."
                Status.INSUFFICIENT ->
                    if (mode == BroadcastMode.DIRECT && count > 1)
                        "Not enough upload for DIRECT with $count destinations — use Smart Relay."
                    else "Not enough upload — lower the bitrate/resolution."
                Status.UNKNOWN -> ""
            }
        }

        return Assessment(
            mode = mode,
            destinationCount = count,
            requiredPerStreamKbps = perStream,
            requiredPhoneUploadKbps = required,
            availableUploadKbps = availableUploadKbps,
            marginKbps = margin,
            status = status,
            recommendedMode = recommended,
            summary = summary
        )
    }
}
