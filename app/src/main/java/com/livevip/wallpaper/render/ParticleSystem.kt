package com.livevip.wallpaper.render

import com.livevip.wallpaper.project.ParticleSpec
import com.livevip.wallpaper.project.ProjectLimits
import java.nio.FloatBuffer
import java.util.Random
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * CPU particle simulation for fire, sparks, magic and ambient dust. Each emitter keeps a live count
 * (count * amount * quality scale). Positions live in normalized screen space (0..1, y down).
 */
class ParticleSystem(private val random: Random = Random(0x5EED)) {

    private class P {
        var x = 0f; var y = 0f; var vx = 0f; var vy = 0f
        var age = 0f; var life = 1f; var size = 4f
        var angle = 0f; var orbit = 0f; var spin = 0f
        var r = 1f; var g = 1f; var b = 1f; var alpha = 1f
    }

    private class Emitter(val spec: ParticleSpec) {
        val alive = ArrayList<P>()
    }

    private var emitters: List<Emitter> = emptyList()

    fun configure(specs: List<ParticleSpec>) {
        emitters = specs.map { Emitter(it) }
    }

    /** Total particles that can be drawn in one frame. */
    val capacity: Int get() = MAX_TOTAL

    /**
     * Advances the simulation. [amounts] maps type -> 0..2 multiplier (already including quality scale).
     */
    fun update(dt: Float, amounts: Map<String, Float>) {
        val step = dt.coerceIn(0f, 0.1f)
        var budget = MAX_TOTAL
        for (em in emitters) {
            val mult = amounts[em.spec.type] ?: 1f
            val target = (em.spec.count * mult).toInt().coerceIn(0, min(budget, ProjectLimits.MAX_PARTICLES_PER_GROUP))
            budget -= target
            while (em.alive.size > target) em.alive.removeAt(em.alive.size - 1)
            while (em.alive.size < target) {
                val p = P()
                spawn(em.spec, p, randomAge = true)
                em.alive.add(p)
            }
            for (p in em.alive) {
                p.age += step
                if (p.age >= p.life) spawn(em.spec, p, randomAge = false)
                advance(em.spec, p, step)
            }
        }
    }

    /** Writes interleaved [x, y, size, r, g, b, a] per particle into [buffer]. Returns particle count. */
    fun fill(buffer: FloatBuffer, screenHeightPx: Float, offsetX: Float, offsetY: Float): Int {
        buffer.clear()
        var count = 0
        val sizeScale = (screenHeightPx / 1280f).coerceIn(0.5f, 3f)
        for (em in emitters) {
            val baseColor = em.spec.color
            for (p in em.alive) {
                if (count >= MAX_TOTAL) break
                val t = (p.age / p.life).coerceIn(0f, 1f)
                val fade = min(1f, t * 6f) * (1f - t).coerceAtLeast(0f)
                var r = p.r; var g = p.g; var b = p.b
                if (em.spec.type == "fire") {
                    // Fire cools from the tint toward yellow-white as it rises.
                    val base = baseColor ?: 0xFFFF7A1A.toInt()
                    val br = ((base shr 16) and 0xFF) / 255f
                    val bg = ((base shr 8) and 0xFF) / 255f
                    val bb = (base and 0xFF) / 255f
                    r = br + (1f - br) * t * 0.5f
                    g = bg + (1f - bg) * t * 0.8f
                    b = bb + (1f - bb) * t * 0.6f
                } else if (baseColor != null) {
                    r = ((baseColor shr 16) and 0xFF) / 255f
                    g = ((baseColor shr 8) and 0xFF) / 255f
                    b = (baseColor and 0xFF) / 255f
                }
                val size = (p.size * (1f - 0.4f * t) * sizeScale).coerceIn(1f, 48f)
                buffer.put((p.x * 2f - 1f) + offsetX)
                buffer.put((1f - p.y * 2f) + offsetY)
                buffer.put(size)
                buffer.put(r.coerceIn(0f, 1f))
                buffer.put(g.coerceIn(0f, 1f))
                buffer.put(b.coerceIn(0f, 1f))
                buffer.put((fade * p.alpha).coerceIn(0f, 1f))
                count++
            }
        }
        buffer.flip()
        return count
    }

    private fun spawn(spec: ParticleSpec, p: P, randomAge: Boolean) {
        val rx = spec.regionX
        val ry = spec.regionY
        val rw = spec.regionW
        val rh = spec.regionH
        p.age = if (randomAge) random.nextFloat() * 2f else 0f
        p.alpha = 1f
        when (spec.type) {
            "fire" -> {
                p.x = rx + random.nextFloat() * rw
                p.y = ry + rh - random.nextFloat() * 0.03f
                p.vx = (random.nextFloat() - 0.5f) * 0.03f
                p.vy = -(0.10f + random.nextFloat() * 0.20f)
                p.life = 1.2f + random.nextFloat() * 1.2f
                p.size = 9f + random.nextFloat() * 14f
                p.alpha = 0.55f + random.nextFloat() * 0.45f
            }
            "sparks" -> {
                p.x = rx + random.nextFloat() * rw
                p.y = ry + random.nextFloat() * rh
                val a = random.nextFloat() * 6.2831853f
                val s = 0.08f + random.nextFloat() * 0.25f
                p.vx = cos(a) * s
                p.vy = sin(a) * s
                p.life = 0.5f + random.nextFloat() * 0.8f
                p.size = 2.5f + random.nextFloat() * 3f
            }
            "magic" -> {
                p.angle = random.nextFloat() * 6.2831853f
                p.orbit = 0.45f + random.nextFloat() * 0.55f
                p.spin = (if (random.nextBoolean()) 1f else -1f) * (0.25f + random.nextFloat() * 0.4f)
                p.life = 2.5f + random.nextFloat() * 1.5f
                p.size = 6f + random.nextFloat() * 8f
                p.x = rx + rw * 0.5f
                p.y = ry + rh * 0.5f
                if (spec.color == null) {
                    val purple = random.nextBoolean()
                    p.r = if (purple) 0.7f else 0.2f
                    p.g = if (purple) 0.3f else 0.9f
                    p.b = 1f
                }
            }
            else -> { // ambient dust
                p.x = rx + random.nextFloat() * rw
                p.y = ry + random.nextFloat() * rh
                p.vx = (random.nextFloat() - 0.5f) * 0.02f
                p.vy = -(0.005f + random.nextFloat() * 0.01f)
                p.life = 6f + random.nextFloat() * 4f
                p.size = 1.5f + random.nextFloat() * 2f
                p.alpha = 0.35f
                if (spec.color == null) { p.r = 0.9f; p.g = 0.95f; p.b = 1f }
            }
        }
        if (spec.type == "sparks" && spec.color == null) {
            p.r = 1f; p.g = 0.85f; p.b = 0.45f
        }
        if (spec.type == "fire") {
            // Fire colors are computed from the tint in fill(); reset here for clarity.
            p.r = 1f; p.g = 0.5f; p.b = 0.1f
        }
    }

    private fun advance(spec: ParticleSpec, p: P, dt: Float) {
        when (spec.type) {
            "fire" -> {
                p.x += p.vx * dt + sin((p.age + p.life) * 6f) * 0.0006f
                p.y += p.vy * dt
            }
            "sparks" -> {
                p.vy += 0.25f * dt
                p.x += p.vx * dt
                p.y += p.vy * dt
            }
            "magic" -> {
                p.angle += p.spin * dt
                val cx = spec.regionX + spec.regionW * 0.5f
                val cy = spec.regionY + spec.regionH * 0.5f
                p.x = cx + cos(p.angle) * p.orbit * spec.regionW * 0.5f
                p.y = cy + sin(p.angle) * p.orbit * spec.regionH * 0.5f
            }
            else -> {
                p.x += p.vx * dt
                p.y += p.vy * dt
                if (p.y < -0.02f) p.y += 1.04f
            }
        }
    }

    companion object {
        const val MAX_TOTAL = 600
        const val FLOATS_PER_PARTICLE = 7
    }
}
