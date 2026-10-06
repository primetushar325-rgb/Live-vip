package com.livevip.app.overlay

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Overlay / scene configuration JSON round-trips — these are persisted with
 * the project, so a schema drift would silently drop user overlays.
 */
class OverlayConfigTest {

    @Test
    fun `overlay round-trips through json`() {
        val original = OverlayConfig(
            id = 7,
            type = OverlayType.LOWER_THIRD,
            enabled = false,
            text = "LIVE • Dhaka",
            secondaryText = "guest name",
            positionX = 0.3f,
            positionY = 0.9f,
            scale = 0.5f,
            opacity = 0.8f
        )
        val copy = OverlayConfig.fromJson(original.toJson())
        assertEquals(original.id, copy.id)
        assertEquals(original.type, copy.type)
        assertEquals(original.enabled, copy.enabled)
        assertEquals(original.text, copy.text)
        assertEquals(original.secondaryText, copy.secondaryText)
        assertEquals(original.positionX, copy.positionX)
        assertEquals(original.positionY, copy.positionY)
        assertEquals(original.scale, copy.scale)
        assertEquals(original.opacity, copy.opacity, 0.001f)
    }

    @Test
    fun `every overlay type survives the round-trip`() {
        OverlayType.entries.forEach { type ->
            val copy = OverlayConfig.fromJson(
                OverlayConfig(id = 1, type = type).toJson()
            )
            assertEquals(type, copy.type)
        }
    }

    @Test
    fun `unknown type falls back to text`() {
        assertEquals(OverlayType.TEXT, OverlayType.from("does-not-exist"))
        assertEquals(OverlayType.TEXT, OverlayType.from(null))
    }

    @Test
    fun `scene list round-trips`() {
        val scenes = listOf(
            SceneConfig(id = 1, name = "Full video", overlayIds = listOf(1, 2, 3)),
            SceneConfig(id = 2, name = "With logo", overlayIds = listOf(2))
        )
        val restored = SceneConfig.listFromJson(SceneConfig.listToJson(scenes))
        assertEquals(2, restored.size)
        assertEquals("Full video", restored[0].name)
        assertEquals(listOf(1L, 2L, 3L), restored[0].overlayIds)
        assertEquals(listOf(2L), restored[1].overlayIds)
    }

    @Test
    fun `null or blank scene json yields empty list`() {
        assertTrue(SceneConfig.listFromJson(null).isEmpty())
        assertTrue(SceneConfig.listFromJson("").isEmpty())
        assertTrue(SceneConfig.listFromJson("not-json").isEmpty())
    }

    @Test
    fun `countdown and scrolling fields round-trip`() {
        val original = OverlayConfig(
            id = 3,
            type = OverlayType.SCROLLING_TEXT,
            text = "follow @livevip",
            scrollSpeedPercentPerSec = 25f
        )
        val copy = OverlayConfig.fromJson(original.toJson())
        assertEquals(25f, copy.scrollSpeedPercentPerSec, 0.001f)

        val countdown = OverlayConfig(
            id = 4, type = OverlayType.COUNTDOWN,
            countdownTargetEpochMs = 1_770_000_000_000
        )
        assertEquals(
            1_770_000_000_000,
            OverlayConfig.fromJson(countdown.toJson()).countdownTargetEpochMs
        )
    }
}
