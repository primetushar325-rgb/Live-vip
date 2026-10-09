package com.livevip.wallpaper.project

import com.livevip.wallpaper.TestFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ManifestParserTest {

    @Test
    fun parsesLayersRigParticlesAndColors() {
        val json = TestFiles.manifestJson(
            layers = """{"id":"hair","role":"hair","file":"layers/hair.png","x":10,"y":20,"depth":0.7,
                "mask":"masks/hair.png","rig":{"mode":"ripple","amplitude":0.02,"direction":[0,2]}}""",
            extra = """, "particles":[{"type":"fire","count":40,"region":[0,0.5,1,0.5],"color":"#FF7A1A"}],
                "effects":{"outerGlowColor":"#00E5FF","outerGlowIntensity":1.5}, "motion":{"strength":0.5}""",
        )
        val m = ManifestParser.parse(json)
        assertEquals("Test Project", m.name)
        assertEquals(720, m.canvasWidth)
        assertEquals(1, m.layers.size)
        val layer = m.layers[0]
        assertEquals("hair", layer.id)
        assertEquals(0.7f, layer.depth, 1e-6f)
        assertNotNull(layer.rig)
        val rig = layer.rig!!
        assertEquals("ripple", rig.mode)
        assertEquals(1f, rig.dirY, 1e-6f) // direction normalized
        assertEquals("fire", m.particles[0].type)
        assertEquals(0xFFFF7A1A.toInt(), m.particles[0].color)
        assertNotNull(m.defaults)
        val defaults = m.defaults!!
        assertEquals(0xFF00E5FF.toInt(), defaults.effects.outerGlowColor)
        assertEquals(1.5f, defaults.effects.outerGlowIntensity, 1e-6f)
        assertEquals(0.5f, defaults.motion.strength, 1e-6f)
    }

    @Test
    fun rejectsWrongFormatAndVersion() {
        assertThrows(ProjectException::class.java) {
            ManifestParser.parse(TestFiles.manifestJson().replace("mwproj", "zip"))
        }
        assertThrows(ProjectException::class.java) {
            ManifestParser.parse(TestFiles.manifestJson().replace("\"formatVersion\": 1", "\"formatVersion\": 7"))
        }
    }

    @Test
    fun rejectsInvalidJson() {
        assertThrows(ProjectException::class.java) { ManifestParser.parse("{not json") }
    }

    @Test
    fun rejectsUnsafeAssetPaths() {
        val json = TestFiles.manifestJson().replace("background.png", "../background.png")
        assertThrows(ProjectException::class.java) { ManifestParser.parse(json) }
    }

    @Test
    fun rejectsDuplicateLayerIdsAndBadDepth() {
        val dup = TestFiles.manifestJson(layers = """
            {"id":"a","file":"layers/a.png"},{"id":"a","file":"layers/b.png"}""")
        assertThrows(ProjectException::class.java) { ManifestParser.parse(dup) }
        val badDepth = TestFiles.manifestJson(layers = """{"id":"a","file":"layers/a.png","depth":3}""")
        assertThrows(ProjectException::class.java) { ManifestParser.parse(badDepth) }
    }

    @Test
    fun rejectsUnknownParticleTypeAndRigMode() {
        val particle = TestFiles.manifestJson(extra = """, "particles":[{"type":"lasers","count":4}]""")
        assertThrows(ProjectException::class.java) { ManifestParser.parse(particle) }
        val rig = TestFiles.manifestJson(layers = """{"id":"a","file":"layers/a.png","rig":{"mode":"teleport"}}""")
        assertThrows(ProjectException::class.java) { ManifestParser.parse(rig) }
    }

    @Test
    fun rejectsCanvasOutsideLimits() {
        assertThrows(ProjectException::class.java) { ManifestParser.parse(TestFiles.manifestJson(canvasW = 50)) }
        assertThrows(ProjectException::class.java) { ManifestParser.parse(TestFiles.manifestJson(canvasW = 9000)) }
    }

    @Test
    fun sanitizeClampsOutOfRangeSettings() {
        val t = ProjectTuning(
            motion = MotionSettings(strength = 9f, smoothing = 0f, motionLimit = 4f),
            effects = EffectSettings(bgContrast = 7f, outerGlowRadius = 1f),
        )
        val clean = ManifestParser.sanitize(t)
        assertEquals(2f, clean.motion.strength, 1e-6f)
        assertEquals(1f, clean.motion.smoothing, 1e-6f)
        assertEquals(1f, clean.motion.motionLimit, 1e-6f)
        assertEquals(1.5f, clean.effects.bgContrast, 1e-6f)
        assertEquals(0.06f, clean.effects.outerGlowRadius, 1e-6f)
    }
}
