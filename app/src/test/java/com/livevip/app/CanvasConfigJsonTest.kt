package com.livevip.app.overlay

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PART 2 PERSISTENCE: canvas + overlay JSON round trips (project reopen must
 * restore everything — old projects must keep parsing).
 */
class CanvasConfigJsonTest {

    @Test
    fun `canvas round trip preserves all fields`() {
        val canvas = CanvasConfig(
            aspect = CanvasAspect.PORTRAIT_9_16,
            width = 1080, height = 1920,
            backgroundColor = 0xFF102030.toInt(),
            transform = VideoTransform(
                scale = 1.4f, offsetX = 0.1f, offsetY = -0.2f, rotationDeg = 15f,
                fitMode = FitMode.CUSTOM, cropL = 0.05f, cropT = 0.1f,
                cropR = 0.15f, cropB = 0.2f
            )
        )
        val parsed = CanvasConfig.fromJson(canvas.toJson().toString())
        assertNotNull(parsed)
        assertEquals(canvas.aspect, parsed!!.aspect)
        assertEquals(1080, parsed.width)
        assertEquals(1920, parsed.height)
        assertEquals(0xFF102030.toInt(), parsed.backgroundColor)
        assertEquals(1.4f, parsed.transform.scale, 1e-4f)
        assertEquals(0.1f, parsed.transform.offsetX, 1e-4f)
        assertEquals(-0.2f, parsed.transform.offsetY, 1e-4f)
        assertEquals(15f, parsed.transform.rotationDeg, 1e-4f)
        assertEquals(FitMode.CUSTOM, parsed.transform.fitMode)
        assertEquals(0.05f, parsed.transform.cropL, 1e-4f)
        assertEquals(0.2f, parsed.transform.cropB, 1e-4f)
    }

    @Test
    fun `minimal or empty json falls back to null or defaults`() {
        assertNull(CanvasConfig.fromJson(""))
        assertNull(CanvasConfig.fromJson("not json"))
        val minimal = CanvasConfig.fromJson(
            JSONObject().put("width", 1280).put("height", 720).toString()
        )
        assertNotNull(minimal)
        assertEquals(1280, minimal!!.width)
        assertEquals(720, minimal.height)
        assertEquals(CanvasAspect.LANDSCAPE_16_9, minimal.aspect)
        assertEquals(FitMode.FIT, minimal.transform.fitMode)
    }

    @Test
    fun `copyWithResolution infers the aspect`() {
        val base = CanvasConfig(width = 1920, height = 1080)
        val portrait = base.copyWithResolution(1080, 1920)
        assertEquals(CanvasAspect.PORTRAIT_9_16, portrait.aspect)
        val square = base.copyWithResolution(1080, 1080)
        assertEquals(CanvasAspect.SQUARE_1_1, square.aspect)
        val portrait45 = base.copyWithResolution(1080, 1350)
        assertEquals(CanvasAspect.PORTRAIT_4_5, portrait45.aspect)
        val odd = base.copyWithResolution(1079, 1921)
        assertEquals(1078, odd.width) // coerced even (rounds down)
        assertEquals(1920, odd.height)
        // Landscape inference survives too.
        val landscape = base.copyWithResolution(1920, 1080)
        assertEquals(CanvasAspect.LANDSCAPE_16_9, landscape.aspect)
    }

    @Test
    fun `overlay round trip includes rotation lock and animation`() {
        val overlay = OverlayConfig(
            id = 42, type = OverlayType.SUBSCRIBE, text = "SUBSCRIBE",
            positionX = 0.5f, positionY = 0.85f, scale = 0.4f, opacity = 0.9f,
            rotation = 15, locked = true, animation = OverlayAnimation.PULSE
        )
        val parsed = OverlayConfig.fromJson(JSONObject(overlay.toJson().toString()))
        assertEquals(42L, parsed.id)
        assertEquals(OverlayType.SUBSCRIBE, parsed.type)
        assertEquals(15, parsed.rotation)
        assertEquals(true, parsed.locked)
        assertEquals(OverlayAnimation.PULSE, parsed.animation)
        assertEquals(0.4f, parsed.scale, 1e-4f)
    }

    @Test
    fun `old overlay json without part2 keys parses with defaults`() {
        // Part 1-era JSON: no rotation/locked/animation keys.
        val legacy = JSONObject()
            .put("id", 7)
            .put("type", "TEXT")
            .put("text", "LIVE")
            .put("positionX", 0.5)
            .put("positionY", 0.9)
            .put("scale", 0.5)
        val parsed = OverlayConfig.fromJson(legacy)
        assertEquals(OverlayType.TEXT, parsed.type)
        assertEquals(0, parsed.rotation)
        assertEquals(false, parsed.locked)
        assertEquals(OverlayAnimation.NONE, parsed.animation)
    }

    @Test
    fun `new layer types serialize and parse`() {
        listOf(
            OverlayType.VIDEO to "content://media/video/1",
            OverlayType.GIF to "content://media/gif/2",
            OverlayType.SUBSCRIBE to "",
            OverlayType.BACKGROUND to "content://media/bg/3"
        ).forEach { (type, uri) ->
            val o = OverlayConfig(
                id = 1, type = type, text = "x", imageUri = uri
            )
            val parsed = OverlayConfig.fromJson(JSONObject(o.toJson().toString()))
            assertEquals(type, parsed.type)
            assertEquals(uri, parsed.imageUri)
        }
    }

    @Test
    fun `unknown overlay type falls back safely`() {
        val parsed = OverlayConfig.fromJson(JSONObject().put("type", "HOLOGRAM"))
        assertEquals(OverlayType.TEXT, parsed.type)
    }

    @Test
    fun `overlay list round trip keeps z order`() {
        val list = listOf(
            OverlayConfig(id = 3, type = OverlayType.IMAGE, text = "c"),
            OverlayConfig(id = 1, type = OverlayType.TEXT, text = "a"),
            OverlayConfig(id = 2, type = OverlayType.SUBSCRIBE, text = "b")
        )
        val parsed = OverlayConfig.listFromJson(OverlayConfig.listToJson(list))
        assertEquals(3, parsed.size)
        assertEquals(listOf(3L, 1L, 2L), parsed.map { it.id })
        assertEquals(listOf("c", "a", "b"), parsed.map { it.text })
    }

    @Test
    fun `scene round trip keeps overlay ids and order`() {
        val scene = SceneConfig(id = 9, name = "Intro", overlayIds = listOf(5L, 2L, 7L))
        val parsed = SceneConfig.fromJson(JSONObject(scene.toJson().toString()))
        assertEquals(9L, parsed.id)
        assertEquals("Intro", parsed.name)
        assertEquals(listOf(5L, 2L, 7L), parsed.overlayIds)

        val list = SceneConfig.listFromJson(SceneConfig.listToJson(listOf(scene)))
        assertEquals(1, list.size)
        assertEquals(listOf(5L, 2L, 7L), list[0].overlayIds)
    }
}
