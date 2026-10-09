package com.livevip.wallpaper.project

import com.livevip.wallpaper.TestFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parsing of the soft_body rig mode and the soft-body effect settings. formatVersion 1 files stay valid. */
class SoftBodyRigTest {

    private fun layerJson(rig: String) = TestFiles.manifestJson(
        layers = """{"id":"body","file":"layers/body.png","mask":"masks/body.png","rig":$rig}""",
    )

    @Test
    fun softBodyRigIsAcceptedWithItsOwnDefaults() {
        val rig = ManifestParser.parse(layerJson("""{"mode":"soft_body"}""")).layers[0].rig!!
        assertEquals("soft_body", rig.mode)
        assertEquals(0.25f, rig.frequency, 1e-6f) // slower than the other modes by default
        assertEquals(0.01f, rig.amplitude, 1e-6f)
        assertEquals(0.5f, rig.damping, 1e-6f)
    }

    @Test
    fun softBodyDampingAndAmplitudeAreRangeChecked() {
        assertEquals(0.9f, ManifestParser.parse(layerJson("""{"mode":"soft_body","damping":0.9}""")).layers[0].rig!!.damping, 1e-6f)
        assertThrows(ProjectException::class.java) {
            ManifestParser.parse(layerJson("""{"mode":"soft_body","damping":1.5}"""))
        }
        assertThrows(ProjectException::class.java) {
            ManifestParser.parse(layerJson("""{"mode":"soft_body","damping":"heavy"}"""))
        }
        assertThrows(ProjectException::class.java) {
            ManifestParser.parse(layerJson("""{"mode":"soft_body","amplitude":0.5}"""))
        }
    }

    @Test
    fun existingRigsKeepTheirOldDefaults() {
        // A formatVersion 1 project written before soft_body existed has no damping and the old 0.4 Hz default.
        val rig = ManifestParser.parse(layerJson("""{"mode":"sway"}""")).layers[0].rig!!
        assertEquals("sway", rig.mode)
        assertEquals(0.4f, rig.frequency, 1e-6f)
        assertEquals(0.5f, rig.damping, 1e-6f)
    }

    @Test
    fun unknownRigModeStillFailsWithTheListOfModes() {
        val e = assertThrows(ProjectException::class.java) {
            ManifestParser.parse(layerJson("""{"mode":"breathe_hard"}"""))
        }
        assertTrue(e.message!!, e.message!!.contains("soft_body"))
    }

    @Test
    fun softBodyEffectSettingsAreParsedAndClamped() {
        val json = TestFiles.manifestJson(
            extra = ""","effects":{"softBodyStrength":9,"softBodySpeed":10,"softBodyDamping":-1}""",
        )
        val fx = ManifestParser.parse(json).defaults!!.effects
        assertEquals(2f, fx.softBodyStrength, 1e-6f)
        assertEquals(3f, fx.softBodySpeed, 1e-6f)
        assertEquals(0f, fx.softBodyDamping, 1e-6f)
    }

    @Test
    fun effectSettingsWithoutSoftBodyKeysUseDefaults() {
        val json = TestFiles.manifestJson(extra = ""","effects":{"outerGlowIntensity":0.5}""")
        val fx = ManifestParser.parse(json).defaults!!.effects
        assertEquals(1f, fx.softBodyStrength, 1e-6f)
        assertEquals(1f, fx.softBodySpeed, 1e-6f)
        assertEquals(1f, fx.softBodyDamping, 1e-6f)
    }
}
