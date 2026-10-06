package com.livevip.app.overlay

import org.json.JSONArray
import org.json.JSONObject

/**
 * OVERLAY SYSTEM — configuration model.
 *
 * Overlays are composited INTO THE ENCODED STREAM via the GL filter chain
 * (GlStreamInterface → encoder surface), NOT as Android UI chrome. Toggling
 * one during a live broadcast is a filter add/remove — RTMP is never touched.
 */
enum class OverlayType(val label: String) {
    TEXT("Text"),
    IMAGE("Image / Logo"),
    WATERMARK("Watermark"),
    LOWER_THIRD("Lower Third"),
    COUNTDOWN("Countdown"),
    CLOCK("Clock"),
    SCROLLING_TEXT("Scrolling Text"),
    BORDER("Border / Frame");

    companion object {
        fun from(name: String?): OverlayType =
            entries.firstOrNull { it.name == name } ?: TEXT
    }
}

/**
 * @param positionX/positionY normalized CENTER of the overlay (0..1, origin top-left).
 * @param scale relative WIDTH of the overlay (0..1 of stream width). Height follows
 *        aspect ratio for text/image objects.
 */
data class OverlayConfig(
    val id: Long,
    var type: OverlayType = OverlayType.TEXT,
    var enabled: Boolean = true,
    var text: String = "",
    var secondaryText: String = "",
    var imageUri: String? = null,
    var positionX: Float = 0.5f,
    var positionY: Float = 0.88f,
    var scale: Float = 0.35f,
    var color: Int = 0xFFFFFFFF.toInt(),
    var background: Int = 0x00000000,
    var fontSize: Float = 26f,
    var opacity: Float = 1f,
    var scrollSpeedPercentPerSec: Float = 12f,
    var countdownTargetEpochMs: Long = 0L,
    var borderThickness: Float = 0.025f
) {

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", type.name)
        put("enabled", enabled)
        put("text", text)
        put("secondaryText", secondaryText)
        imageUri?.let { put("imageUri", it) }
        put("positionX", positionX.toDouble())
        put("positionY", positionY.toDouble())
        put("scale", scale.toDouble())
        put("color", color.toLong())
        put("background", background.toLong())
        put("fontSize", fontSize.toDouble())
        put("opacity", opacity.toDouble())
        put("scrollSpeed", scrollSpeedPercentPerSec.toDouble())
        put("countdownTarget", countdownTargetEpochMs)
        put("borderThickness", borderThickness.toDouble())
    }

    companion object {
        fun fromJson(o: JSONObject): OverlayConfig = OverlayConfig(
            id = o.optLong("id"),
            type = OverlayType.from(o.optString("type")),
            enabled = o.optBoolean("enabled", true),
            text = o.optString("text"),
            secondaryText = o.optString("secondaryText"),
            imageUri = if (o.has("imageUri")) o.optString("imageUri") else null,
            positionX = o.optDouble("positionX", 0.5).toFloat(),
            positionY = o.optDouble("positionY", 0.88).toFloat(),
            scale = o.optDouble("scale", 0.35).toFloat(),
            color = o.optLong("color", 0xFFFFFFFF).toInt(),
            background = o.optLong("background", 0x00000000).toInt(),
            fontSize = o.optDouble("fontSize", 26.0).toFloat(),
            opacity = o.optDouble("opacity", 1.0).toFloat(),
            scrollSpeedPercentPerSec = o.optDouble("scrollSpeed", 12.0).toFloat(),
            countdownTargetEpochMs = o.optLong("countdownTarget"),
            borderThickness = o.optDouble("borderThickness", 0.025).toFloat()
        )

        fun listToJson(list: List<OverlayConfig>): String {
            val array = JSONArray()
            list.forEach { array.put(it.toJson()) }
            return array.toString()
        }

        fun listFromJson(raw: String?): List<OverlayConfig> {
            if (raw.isNullOrBlank()) return emptyList()
            return try {
                val array = JSONArray(raw)
                (0 until array.length()).mapNotNull { i ->
                    array.optJSONObject(i)?.let { fromJson(it) }
                }
            } catch (_: Throwable) {
                emptyList()
            }
        }
    }
}

/**
 * A Scene is a named SET of overlays. Switching scenes during live applies the
 * diff of the overlay set to the GL filter chain — no encoder restart, no RTMP
 * reconnect, no stream interruption.
 */
data class SceneConfig(
    val id: Long,
    var name: String,
    /** Overlay ids active in this scene. */
    var overlayIds: List<Long>
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("overlays", JSONArray(overlayIds))
    }

    companion object {
        fun fromJson(o: JSONObject): SceneConfig = SceneConfig(
            id = o.optLong("id"),
            name = o.optString("name"),
            overlayIds = run {
                val array = o.optJSONArray("overlays") ?: JSONArray()
                (0 until array.length()).map { idx -> array.optLong(idx) }
            }
        )

        fun listToJson(list: List<SceneConfig>): String {
            val array = JSONArray()
            list.forEach { array.put(it.toJson()) }
            return array.toString()
        }

        fun listFromJson(raw: String?): List<SceneConfig> {
            if (raw.isNullOrBlank()) return emptyList()
            return try {
                val array = JSONArray(raw)
                (0 until array.length()).mapNotNull { i ->
                    array.optJSONObject(i)?.let { fromJson(it) }
                }
            } catch (_: Throwable) {
                emptyList()
            }
        }
    }
}
