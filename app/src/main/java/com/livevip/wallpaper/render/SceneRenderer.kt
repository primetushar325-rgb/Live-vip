package com.livevip.wallpaper.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES20
import com.livevip.wallpaper.project.EffectSettings
import com.livevip.wallpaper.project.LayerSpec
import com.livevip.wallpaper.project.ProjectManifest
import com.livevip.wallpaper.project.ProjectTuning
import com.livevip.wallpaper.project.QualityMode
import java.io.File
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/** Settings that can change while a scene is on screen. */
class RenderSettings(
    val tuning: ProjectTuning = ProjectTuning(),
    val quality: QualityMode = QualityMode.BALANCED,
)

/** A validated project directory to load on the GL thread. */
class SceneRequest(val dir: File, val manifest: ProjectManifest)

/**
 * The shared OpenGL scene renderer used by both the live wallpaper and the in-app preview.
 *
 * Threading: [init], [drawFrame], [release] and texture work must run on the thread that owns the GL
 * context. [requestScene], [updateSettings], [setTarget] and [setHomeOffset] are thread-safe.
 *
 * Pipeline per frame: background (zoom + depth parallax + edge fill + grading) -> character layers
 * (depth parallax, rig sway, glow) -> particles (additive). With render scale < 1 the scene goes to
 * an offscreen target and is scaled up in a final blit.
 */
class SceneRenderer {

    private class GpuLayer(val spec: LayerSpec, val tex: Int, val mask: Int, val glow: Int) {
        /** soft_body only: the tilt offset this layer has caught up to (lags the device tilt). */
        var lagX = 0f
        var lagY = 0f
    }
    private class GpuScene(val canvasW: Int, val canvasH: Int, val bg: Int, val depth: Int, val layers: List<GpuLayer>)
    private class Cover(val offsetX: Float, val offsetY: Float, val scale: Float, val drawnW: Float, val drawnH: Float)

    private val pendingLoad = AtomicReference<Any?>(NO_CHANGE)
    @Volatile private var lastRequest: SceneRequest? = null
    @Volatile private var settings = RenderSettings()
    @Volatile private var targetX = 0f
    @Volatile private var targetY = 0f
    @Volatile private var homeOffsetX = 0f
    @Volatile var lastError: String? = null
        private set

    private var initialized = false
    private var bgProgram = 0
    private var layerProgram = 0
    private var particleProgram = 0
    private var blitProgram = 0
    private var dummyTex = 0
    private var fbo = 0
    private var fboTex = 0
    private var fboW = 0
    private var fboH = 0
    private var surfaceW = 1
    private var surfaceH = 1
    private var scene: GpuScene? = null
    private val owned = ArrayList<Int>()
    private val particles = ParticleSystem()
    private val particleBuffer: FloatBuffer = GlUtil.floatBuffer(ParticleSystem.MAX_TOTAL * ParticleSystem.FLOATS_PER_PARTICLE)
    private val quadBuffer: FloatBuffer = GlUtil.floatBuffer(16)
    private val locations = HashMap<String, Int>()
    private var curX = 0f
    private var curY = 0f
    private var startNs = 0L
    private var lastNs = 0L

    /**
     * Call on the GL thread after the context is current. Safe to call again for a new context:
     * IDs from the previous context are forgotten (not deleted) and the last scene is reloaded.
     */
    fun init() {
        owned.clear()
        scene = null
        fbo = 0; fboTex = 0; fboW = 0; fboH = 0
        dummyTex = 0
        locations.clear()
        particles.configure(emptyList())
        bgProgram = GlUtil.program(Shaders.QUAD_VS, Shaders.BACKGROUND_FS, "aPos", "aUv")
        layerProgram = GlUtil.program(Shaders.QUAD_VS, Shaders.LAYER_FS, "aPos", "aUv")
        particleProgram = GlUtil.program(Shaders.PARTICLE_VS, Shaders.PARTICLE_FS, "aPos", "aSize", "aColor")
        blitProgram = GlUtil.program(Shaders.QUAD_VS, Shaders.BLIT_FS, "aPos", "aUv")
        dummyTex = GlUtil.createDummyTexture()
        initialized = true
        lastRequest?.let { pendingLoad.set(it) }
    }

    fun resize(width: Int, height: Int) {
        surfaceW = max(1, width)
        surfaceH = max(1, height)
    }

    fun requestScene(request: SceneRequest?) {
        lastRequest = request
        pendingLoad.set(request)
    }

    fun updateSettings(newSettings: RenderSettings) {
        settings = newSettings
    }

    /** Normalized tilt target in -1..1 (sensor, or touch when no sensor). */
    fun setTarget(x: Float, y: Float) {
        targetX = x.coerceIn(-1f, 1f)
        targetY = y.coerceIn(-1f, 1f)
    }

    /** Home-screen page offset (0..1) used as a small extra horizontal parallax. */
    fun setHomeOffset(xOffset: Float) {
        homeOffsetX = ((xOffset - 0.5f) * 2f).coerceIn(-1f, 1f)
    }

    fun drawFrame(nowNs: Long) {
        if (!initialized) return
        val req = pendingLoad.getAndSet(NO_CHANGE)
        if (req !== NO_CHANGE) applyScene(req as SceneRequest?)

        val s = settings
        val q = s.quality
        val motion = s.tuning.motion
        val fx = s.tuning.effects
        if (startNs == 0L) startNs = nowNs
        val dt = if (lastNs == 0L) 0f else ((nowNs - lastNs) / 1e9f).coerceIn(0f, 0.1f)
        lastNs = nowNs
        val t = (nowNs - startNs) / 1e9f

        // Damped tilt follows the target so sensor noise and sudden changes never jump the scene.
        val targetXNow = (targetX + homeOffsetX * 0.25f).coerceIn(-1f, 1f)
        curX = Motion.damp(curX, targetXNow, dt, motion.smoothing)
        curY = Motion.damp(curY, targetY, dt, motion.smoothing)
        val idle = motion.idleAmount * 0.12f
        val idleX = (sin(t * 0.31f) * 0.6f + sin(t * 0.73f + 1.3f) * 0.4f) * idle
        val idleY = (sin(t * 0.23f + 0.7f) * 0.6f + sin(t * 0.61f) * 0.4f) * idle
        val sx = if (motion.invertX) -1f else 1f
        val sy = if (motion.invertY) -1f else 1f
        val ox = Motion.limit(Motion.limit(curX * motion.strength, motion.motionLimit) + idleX, 1f) * sx
        val oy = Motion.limit(Motion.limit(curY * motion.strength, motion.motionLimit) + idleY, 1f) * sy

        val useTarget = q.renderScale < 0.999f
        val rw = max(1, (surfaceW * q.renderScale).roundToInt())
        val rh = max(1, (surfaceH * q.renderScale).roundToInt())
        if (useTarget) ensureTarget(rw, rh)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, if (useTarget) fbo else 0)
        GLES20.glViewport(0, 0, if (useTarget) rw else surfaceW, if (useTarget) rh else surfaceH)
        GLES20.glClearColor(0.04f, 0.04f, 0.07f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val sc = scene
        if (sc != null) {
            val cover = cover(sc.canvasW, sc.canvasH)
            drawBackground(sc, cover, ox, oy, s.tuning)
            // Tilt that soft_body layers follow with a lag. Same sign as the parallax offset ox/oy.
            val tiltX = Motion.limit(curX * motion.strength, motion.motionLimit) * sx
            val tiltY = Motion.limit(curY * motion.strength, motion.motionLimit) * sy
            for (layer in sc.layers) {
                stepSoftBody(layer, tiltX, tiltY, dt, s.tuning.effects)
                drawLayer(sc, cover, layer, ox, oy, t, s.tuning, q)
            }
            drawParticles(q, s.tuning.effects, dt, ox, oy)
        }

        if (useTarget) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, surfaceW, surfaceH)
            drawBlit()
        }
    }

    fun release() {
        if (!initialized) return
        releaseScene()
        if (fbo != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
            GLES20.glDeleteTextures(1, intArrayOf(fboTex), 0)
            fbo = 0; fboTex = 0
        }
        GlUtil.deleteTexture(dummyTex)
        dummyTex = 0
        listOf(bgProgram, layerProgram, particleProgram, blitProgram).forEach { if (it != 0) GLES20.glDeleteProgram(it) }
        bgProgram = 0; layerProgram = 0; particleProgram = 0; blitProgram = 0
        locations.clear()
        initialized = false
    }

    // ---- scene loading -------------------------------------------------------------------------

    private fun applyScene(req: SceneRequest?) {
        releaseScene()
        particles.configure(emptyList())
        if (req == null) return
        try {
            val m = req.manifest
            val bg = loadTexture(File(req.dir, m.background))
            val depth = m.depth?.let { loadTexture(File(req.dir, it)) } ?: 0
            val layers = m.layers.sortedBy { it.depth }.map { spec ->
                GpuLayer(
                    spec = spec,
                    tex = loadTexture(File(req.dir, spec.file)),
                    mask = spec.mask?.let { loadTexture(File(req.dir, it)) } ?: 0,
                    glow = spec.glowMask?.let { loadTexture(File(req.dir, it)) } ?: 0,
                )
            }
            scene = GpuScene(m.canvasWidth, m.canvasHeight, bg, depth, layers)
            particles.configure(m.particles)
            lastError = null
        } catch (e: Exception) {
            releaseScene()
            lastError = e.message ?: "Could not load project"
        }
    }

    private fun loadTexture(file: File): Int {
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bitmap = BitmapFactory.decodeFile(file.path, options) ?: throw GlException("Could not decode ${file.name}")
        try {
            val id = GlUtil.uploadBitmap(bitmap)
            owned.add(id)
            return id
        } finally {
            bitmap.recycle()
        }
    }

    private fun releaseScene() {
        owned.forEach { GlUtil.deleteTexture(it) }
        owned.clear()
        scene = null
    }

    private fun ensureTarget(w: Int, h: Int) {
        if (fbo != 0 && fboW == w && fboH == h) return
        if (fbo != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
            GLES20.glDeleteTextures(1, intArrayOf(fboTex), 0)
        }
        val (f, tex) = GlUtil.createRenderTarget(w, h)
        fbo = f; fboTex = tex; fboW = w; fboH = h
    }

    // ---- passes --------------------------------------------------------------------------------

    private fun cover(cw: Int, ch: Int): Cover {
        val sw = surfaceW.toFloat()
        val sh = surfaceH.toFloat()
        val s = max(sw / cw, sh / ch)
        val dw = cw * s
        val dh = ch * s
        return Cover((sw - dw) / 2f, (sh - dh) / 2f, s, dw, dh)
    }

    private fun drawBackground(sc: GpuScene, cover: Cover, ox: Float, oy: Float, tuning: ProjectTuning) {
        val e = tuning.effects
        // Visible portion of the canvas, in canvas UV space.
        val u0 = -cover.offsetX / cover.drawnW
        val u1 = (surfaceW - cover.offsetX) / cover.drawnW
        val v0 = -cover.offsetY / cover.drawnH
        val v1 = (surfaceH - cover.offsetY) / cover.drawnH
        fillQuad(-1f, 1f, 1f, -1f, u0, v0, u1, v1)
        bindQuad()

        GLES20.glUseProgram(bgProgram)
        GLES20.glDisable(GLES20.GL_BLEND)
        bindTexture(0, sc.bg)
        bindTexture(1, if (sc.depth != 0) sc.depth else dummyTex)
        uniform1i(bgProgram, "uBg", 0)
        uniform1i(bgProgram, "uDepth", 1)
        uniform1f(bgProgram, "uHasDepth", if (sc.depth != 0) 1f else 0f)
        uniform2f(bgProgram, "uBgOffset", -ox * 0.03f, -oy * 0.03f)
        uniform1f(bgProgram, "uZoom", 1.1f)
        uniform1f(bgProgram, "uBlur", e.bgBlur)
        uniform1f(bgProgram, "uEdgeFill", 1f)
        uniform1f(bgProgram, "uBrightness", e.bgBrightness)
        uniform1f(bgProgram, "uContrast", e.bgContrast)
        uniform1f(bgProgram, "uSaturation", e.bgSaturation)
        val tint = e.bgTintColor
        uniform3f(bgProgram, "uTint", channel(tint, 16), channel(tint, 8), channel(tint, 0))
        uniform1f(bgProgram, "uTintAmount", e.bgTintAmount)
        setQuadUniforms(bgProgram)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    /** Moves a soft_body layer's lagged tilt toward the current tilt. Other layers are left alone. */
    private fun stepSoftBody(layer: GpuLayer, tiltX: Float, tiltY: Float, dt: Float, fx: EffectSettings) {
        val rig = layer.spec.rig ?: return
        if (rig.mode != "soft_body") return
        val rate = Motion.softBodyRate(rig.damping * fx.softBodyDamping)
        layer.lagX = Motion.damp(layer.lagX, tiltX, dt, rate)
        layer.lagY = Motion.damp(layer.lagY, tiltY, dt, rate)
    }

    private fun drawLayer(
        sc: GpuScene, cover: Cover, layer: GpuLayer, ox: Float, oy: Float, t: Float,
        tuning: ProjectTuning, q: QualityMode,
    ) {
        val spec = layer.spec
        val e: EffectSettings = tuning.effects
        val depthWeight = (spec.depth * tuning.motion.depthScale).coerceIn(0f, 2f)
        val dx = -ox * 0.09f * depthWeight
        val dy = -oy * 0.09f * depthWeight
        val scale = 1f + tuning.motion.perspective * 0.03f * depthWeight

        // Layer rectangle in canvas pixels -> screen NDC.
        val left = cover.offsetX + spec.x * cover.scale
        val top = cover.offsetY + spec.y * cover.scale
        val right = left + spec.width * cover.scale
        val bottom = top + spec.height * cover.scale
        val nx0 = left / surfaceW * 2f - 1f
        val nx1 = right / surfaceW * 2f - 1f
        val ny0 = 1f - top / surfaceH * 2f
        val ny1 = 1f - bottom / surfaceH * 2f
        fillQuad(nx0, ny0, nx1, ny1, 0f, 0f, 1f, 1f)
        bindQuad()

        GLES20.glUseProgram(layerProgram)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        bindTexture(0, layer.tex)
        bindTexture(1, if (layer.mask != 0) layer.mask else dummyTex)
        bindTexture(2, if (layer.glow != 0) layer.glow else dummyTex)
        uniform1i(layerProgram, "uTex", 0)
        uniform1i(layerProgram, "uMask", 1)
        uniform1i(layerProgram, "uGlow", 2)
        val rig = spec.rig
        uniform1f(layerProgram, "uHasMask", if (layer.mask != 0) 1f else 0f)
        // Layers with a rig but no mask get a gentle whole-layer sway; layers without a rig stay still.
        uniform1f(layerProgram, "uFallbackWeight", if (rig != null && layer.mask == 0) 0.35f else 0f)
        uniform1f(layerProgram, "uHasGlow", if (layer.glow != 0) 1f else 0f)
        uniform2f(layerProgram, "uRigDir", rig?.dirX ?: 1f, rig?.dirY ?: 0f)
        uniform1f(layerProgram, "uRigAmp", rig?.amplitude ?: 0f)
        // soft_body uses its own speed and strength multipliers from the Effects screen. Other modes are unchanged.
        val soft = rig?.mode == "soft_body"
        val gain = e.rigStrength * (if (soft) e.softBodyStrength else 1f)
        uniform1f(layerProgram, "uRigFreq", (rig?.frequency ?: 0f) * (if (soft) e.softBodySpeed else 1f))
        uniform1f(layerProgram, "uRigPhase", rig?.phase ?: 0f)
        uniform2f(layerProgram, "uRigPivot", rig?.pivotX ?: 0.5f, rig?.pivotY ?: 0f)
        uniform1f(
            layerProgram, "uRigMode",
            when (rig?.mode) { "ripple" -> 1f; "flutter" -> 2f; "soft_body" -> 3f; else -> 0f },
        )
        uniform1f(layerProgram, "uRigStrength", gain)
        // The shader moves the content opposite to the displacement, so the negative lag trails the parallax.
        uniform2f(
            layerProgram, "uSoftTilt",
            if (soft) -layer.lagX * Motion.SOFT_TILT_UV * gain else 0f,
            if (soft) -layer.lagY * Motion.SOFT_TILT_UV * gain else 0f,
        )
        uniform1f(layerProgram, "uTime", t)
        uniform1f(layerProgram, "uGlowSamples", q.glowSamples.toFloat())
        // Glow radius is a fraction of canvas height, converted to this layer's UV space so the halo is round.
        val radius = e.outerGlowRadius * sc.canvasH
        uniform2f(layerProgram, "uGlowRadius", radius / spec.width, radius / spec.height)
        uniform1f(layerProgram, "uOuterOn", if (e.outerGlowEnabled) 1f else 0f)
        uniform3f(layerProgram, "uOuterColor", channel(e.outerGlowColor, 16), channel(e.outerGlowColor, 8), channel(e.outerGlowColor, 0))
        uniform1f(layerProgram, "uOuterIntensity", e.outerGlowIntensity)
        uniform1f(layerProgram, "uInnerOn", if (e.innerGlowEnabled) 1f else 0f)
        uniform3f(layerProgram, "uInnerColor", channel(e.innerGlowColor, 16), channel(e.innerGlowColor, 8), channel(e.innerGlowColor, 0))
        uniform1f(layerProgram, "uInnerIntensity", e.innerGlowIntensity)
        uniform1f(layerProgram, "uPulseSpeed", e.glowPulseSpeed)
        uniform2f(layerProgram, "uOffset", dx, dy)
        uniform2f(layerProgram, "uCenter", 0f, 0f)
        uniform1f(layerProgram, "uScale", scale)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun drawParticles(q: QualityMode, e: EffectSettings, dt: Float, ox: Float, oy: Float) {
        val amounts = mapOf(
            "fire" to e.fireAmount * 2f * q.particleScale,
            "sparks" to e.sparksAmount * 2f * q.particleScale,
            "magic" to e.magicAmount * 2f * q.particleScale,
            "ambient" to e.ambientAmount * 2f * q.particleScale,
        )
        particles.update(dt, amounts)
        val n = particles.fill(particleBuffer, surfaceH.toFloat(), -ox * 0.02f, -oy * 0.02f)
        if (n == 0) return
        GLES20.glUseProgram(particleProgram)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE)
        val stride = ParticleSystem.FLOATS_PER_PARTICLE * 4
        particleBuffer.position(0)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, stride, particleBuffer)
        particleBuffer.position(2)
        GLES20.glVertexAttribPointer(1, 1, GLES20.GL_FLOAT, false, stride, particleBuffer)
        particleBuffer.position(3)
        GLES20.glVertexAttribPointer(2, 4, GLES20.GL_FLOAT, false, stride, particleBuffer)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glEnableVertexAttribArray(1)
        GLES20.glEnableVertexAttribArray(2)
        uniform2f(particleProgram, "uOffset", 0f, 0f)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, n)
        GLES20.glDisableVertexAttribArray(2)
        GLES20.glDisableVertexAttribArray(1)
    }

    private fun drawBlit() {
        // Offscreen texture row 0 is the bottom of the image, so top vertices sample v = 1.
        fillQuad(-1f, 1f, 1f, -1f, 0f, 1f, 1f, 0f)
        bindQuad()
        GLES20.glUseProgram(blitProgram)
        GLES20.glDisable(GLES20.GL_BLEND)
        bindTexture(0, fboTex)
        uniform1i(blitProgram, "uTex", 0)
        setQuadUniforms(blitProgram)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** Interleaved (x, y, u, v) for a TRIANGLE_STRIP quad. Top edge is passed as (x0, yTop) and bottom as (x1, yBottom). */
    private fun fillQuad(x0: Float, yTop: Float, x1: Float, yBottom: Float, u0: Float, v0: Float, u1: Float, v1: Float) {
        quadBuffer.clear()
        quadBuffer.put(floatArrayOf(
            x0, yTop, u0, v0,
            x1, yTop, u1, v0,
            x0, yBottom, u0, v1,
            x1, yBottom, u1, v1,
        ))
        quadBuffer.flip()
    }

    private fun bindQuad() {
        quadBuffer.position(0)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 16, quadBuffer)
        quadBuffer.position(2)
        GLES20.glVertexAttribPointer(1, 2, GLES20.GL_FLOAT, false, 16, quadBuffer)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glEnableVertexAttribArray(1)
    }

    private fun setQuadUniforms(program: Int) {
        uniform2f(program, "uOffset", 0f, 0f)
        uniform2f(program, "uCenter", 0f, 0f)
        uniform1f(program, "uScale", 1f)
    }

    private fun bindTexture(unit: Int, id: Int) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
    }

    private fun loc(program: Int, name: String): Int {
        val key = "$program:$name"
        return locations.getOrPut(key) { GLES20.glGetUniformLocation(program, name) }
    }

    private fun uniform1i(p: Int, name: String, v: Int) = GLES20.glUniform1i(loc(p, name), v)
    private fun uniform1f(p: Int, name: String, v: Float) = GLES20.glUniform1f(loc(p, name), v)
    private fun uniform2f(p: Int, name: String, x: Float, y: Float) = GLES20.glUniform2f(loc(p, name), x, y)
    private fun uniform3f(p: Int, name: String, x: Float, y: Float, z: Float) = GLES20.glUniform3f(loc(p, name), x, y, z)

    private fun channel(argb: Int, shift: Int): Float = ((argb shr shift) and 0xFF) / 255f

    companion object {
        private val NO_CHANGE = Any()
    }
}
