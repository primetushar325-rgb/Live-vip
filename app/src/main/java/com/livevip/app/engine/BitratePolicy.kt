package com.livevip.app.engine

import com.livevip.app.streaming.NetworkMath

/**
 * YOUTUBE-COMPATIBLE BITRATE POLICY — pure logic, unit tested.
 *
 * CBR H.264, progressive, square pixels, 2-second keyframes (set by the
 * engine). Bitrates follow YouTube's current H.264 guidance, on the SAFE
 * side, and are never forced above what the user's connection can sustain:
 *
 *   480p30 → 1800 kbps
 *   720p30 → 4500 kbps (guidance: 3 min / 8 recommended — inside 3–6)
 *   720p60 → 5500 kbps
 *   1080p30 → 6000 kbps (guidance: 5 min / 14 recommended — 5–10 baseline)
 *   1080p60 → 9000 kbps
 */
object BitratePolicy {

    /** Steady bitrate tiers used when clamping to the network (ascending). */
    val TIERS = listOf(1200, 1800, 2500, 3500, 4500, 5500, 6000, 9000)

    fun recommendedKbps(width: Int, height: Int, fps: Int): Int {
        val shortSide = minOf(width, height)
        return when {
            shortSide >= 1080 -> if (fps > 30) 9_000 else 6_000
            shortSide >= 720 -> if (fps > 30) 5_500 else 4_500
            else -> if (fps > 30) 2_500 else 1_800
        }
    }

    /**
     * Honest network clamp: pick the highest tier the measured upload can
     * sustain (video + audio + ~10% protocol overhead + 25% safety margin
     * — see [NetworkMath.requiredUploadKbps]).
     *
     * @param uploadKbps null = no honest estimate exists → keep the
     *        recommendation (the live bitrate/congestion probes still
     *        report the truth while streaming).
     * @return the chosen bitrate plus a note when it was reduced.
     */
    fun clampToNetwork(kbps: Int, uploadKbps: Long?): Pair<Int, String?> {
        if (uploadKbps == null || uploadKbps <= 0) return kbps to null
        val required = NetworkMath.requiredUploadKbps(kbps, 128)
        if (uploadKbps >= required) return kbps to null
        val reduced = TIERS.filter { NetworkMath.requiredUploadKbps(it, 128) <= uploadKbps }
            .maxOrNull() ?: TIERS.first()
        return reduced to "Network estimate ${uploadKbps}kbps — bitrate reduced to ${reduced}kbps"
    }
}
