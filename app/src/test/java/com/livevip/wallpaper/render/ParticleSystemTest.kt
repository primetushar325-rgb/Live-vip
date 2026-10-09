package com.livevip.wallpaper.render

import com.livevip.wallpaper.project.ParticleSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.FloatBuffer

class ParticleSystemTest {

    private fun buffer() = FloatBuffer.allocate(ParticleSystem.MAX_TOTAL * ParticleSystem.FLOATS_PER_PARTICLE)

    @Test
    fun spawnsRequestedCountScaledByAmount() {
        val ps = ParticleSystem()
        ps.configure(listOf(ParticleSpec("fire", 100, 0f, 0.5f, 1f, 0.5f, null)))
        ps.update(0.016f, mapOf("fire" to 0.5f))
        val n = ps.fill(buffer(), 1280f, 0f, 0f)
        assertEquals(50, n)
    }

    @Test
    fun neverExceedsCapacityEvenWithManyEmitters() {
        val ps = ParticleSystem()
        ps.configure(List(8) { ParticleSpec("sparks", 300, 0f, 0f, 1f, 1f, null) })
        ps.update(0.016f, mapOf("sparks" to 2f))
        val n = ps.fill(buffer(), 1280f, 0f, 0f)
        assertTrue(n <= ParticleSystem.MAX_TOTAL)
    }

    @Test
    fun particlesStayFiniteOverLongRuns() {
        val ps = ParticleSystem()
        ps.configure(listOf(
            ParticleSpec("magic", 40, 0.1f, 0.1f, 0.8f, 0.5f, null),
            ParticleSpec("ambient", 40, 0f, 0f, 1f, 1f, null),
        ))
        val buf = buffer()
        repeat(2000) {
            ps.update(1f / 60f, mapOf("magic" to 1f, "ambient" to 1f))
        }
        val n = ps.fill(buf, 1280f, 0f, 0f)
        for (i in 0 until n * ParticleSystem.FLOATS_PER_PARTICLE) {
            assertTrue("value $i finite", buf.get(i).isFinite())
        }
    }
}
