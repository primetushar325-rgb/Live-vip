package com.livevip.app.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import android.view.Surface
import com.pedro.encoder.input.gl.render.filters.BaseFilterRender
import com.pedro.encoder.input.gl.render.filters.`object`.BaseObjectFilterRender
import com.pedro.encoder.input.gl.render.filters.`object`.ImageFilterRender
import com.pedro.encoder.input.gl.render.filters.`object`.SurfaceFilterRender
import com.pedro.encoder.input.gl.render.filters.`object`.TextFilterRender
import com.pedro.encoder.utils.gl.GlUtil
import com.pedro.encoder.utils.gl.ImageStreamObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * REAL STREAM COMPOSITOR RENDERS (Part 2).
 *
 * Everything in this file runs inside RootEncoder's GL filter chain — the
 * output of these renders IS the encoded frame. Nothing here is UI chrome.
 */

/**
 * CANVAS TRANSFORM — the first filter in the chain:
 *
 *   background color → transformed main-video quad → (later filters: layers)
 *
 * The input texture holds the source frame; this render draws it into the
 * canvas at the user's fit/fill/zoom/pan/rotate/crop transform. Updating the
 * transform live re-draws the next frame — no encoder restart, no RTMP
 * reconnect, no timestamp change.
 */
@RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
class CanvasVideoTransformRender : BaseFilterRender() {

    private var program = -1
    private var aPositionHandle = -1
    private var aTextureHandle = -1
    private var uMVPMatrixHandle = -1
    private var uSTMatrixHandle = -1
    private var uSamplerHandle = -1

    @Volatile private var quad: FloatArray = floatArrayOf(
        -1f, -1f, 0f, 0f, 0f,
        1f, -1f, 0f, 1f, 0f,
        -1f, 1f, 0f, 0f, 1f,
        1f, 1f, 0f, 1f, 1f
    )
    @Volatile private var backgroundR = 0f
    @Volatile private var backgroundG = 0f
    @Volatile private var backgroundB = 0f

    private val quadBuffer = ByteBuffer.allocateDirect(20 * FLOAT_SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    init {
        Matrix.setIdentityM(MVPMatrix, 0)
        Matrix.setIdentityM(STMatrix, 0)
        pushQuad()
    }

    /** Thread-safe live update — called from the editor (drag/pinch/rotate). */
    fun update(
        transform: VideoTransform,
        sourceW: Int,
        sourceH: Int,
        canvasW: Int,
        canvasH: Int,
        backgroundColor: Int
    ) {
        quad = transform.quadFor(sourceW, sourceH, canvasW, canvasH)
        backgroundR = Color.red(backgroundColor) / 255f
        backgroundG = Color.green(backgroundColor) / 255f
        backgroundB = Color.blue(backgroundColor) / 255f
        pushQuad()
    }

    private fun pushQuad() {
        // Copy into the buffer the GL thread reads — synchronized swap.
        synchronized(quadBuffer) {
            quadBuffer.clear()
            quadBuffer.put(quad)
            quadBuffer.position(0)
        }
    }

    override fun initGlFilter(context: Context) {
        val vertexShader = """
            attribute vec4 aPosition;
            attribute vec4 aTextureCoord;
            uniform mat4 uMVPMatrix;
            uniform mat4 uSTMatrix;
            varying vec2 vTextureCoord;
            void main() {
              gl_Position = uMVPMatrix * aPosition;
              vTextureCoord = (uSTMatrix * aTextureCoord).xy;
            }
        """.trimIndent()
        val fragmentShader = """
            precision mediump float;
            uniform sampler2D uSampler;
            varying vec2 vTextureCoord;
            void main() {
              gl_FragColor = texture2D(uSampler, vTextureCoord);
            }
        """.trimIndent()
        program = GlUtil.createProgram(vertexShader, fragmentShader)
        aPositionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        aTextureHandle = GLES20.glGetAttribLocation(program, "aTextureCoord")
        uMVPMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        uSTMatrixHandle = GLES20.glGetUniformLocation(program, "uSTMatrix")
        uSamplerHandle = GLES20.glGetUniformLocation(program, "uSampler")
    }

    override fun drawFilter() {
        // Canvas background fill — the letterbox/pillarbox color.
        GLES20.glClearColor(backgroundR, backgroundG, backgroundB, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(program)
        synchronized(quadBuffer) {
            quadBuffer.position(SQUARE_VERTEX_DATA_POS_OFFSET)
            GLES20.glVertexAttribPointer(
                aPositionHandle, 3, GLES20.GL_FLOAT, false,
                SQUARE_VERTEX_DATA_STRIDE_BYTES, quadBuffer
            )
            GLES20.glEnableVertexAttribArray(aPositionHandle)

            quadBuffer.position(SQUARE_VERTEX_DATA_UV_OFFSET)
            GLES20.glVertexAttribPointer(
                aTextureHandle, 2, GLES20.GL_FLOAT, false,
                SQUARE_VERTEX_DATA_STRIDE_BYTES, quadBuffer
            )
            GLES20.glEnableVertexAttribArray(aTextureHandle)
        }
        GLES20.glUniformMatrix4fv(uMVPMatrixHandle, 1, false, MVPMatrix, 0)
        GLES20.glUniformMatrix4fv(uSTMatrixHandle, 1, false, STMatrix, 0)
        GLES20.glUniform1i(uSamplerHandle, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, previousTexId)
    }

    override fun disableResources() {
        GlUtil.disableResources(aTextureHandle, aPositionHandle)
    }

    override fun release() {
        GLES20.glDeleteProgram(program)
    }
}

// ---------------------------------------------------------------------
// Layer animations — time-based modulation, composited into the stream.
// ---------------------------------------------------------------------

/** One animation's per-frame modulation. */
data class LayerModulation(
    val scaleFactor: Float,
    val alphaFactor: Float,
    val offsetPercentX: Float,
    val offsetPercentY: Float
) {
    companion object {
        val NONE = LayerModulation(1f, 1f, 0f, 0f)
    }
}

/** Pure animation math — unit tested. */
object LayerAnimations {

    const val FADE_IN_SEC = 1.2f
    const val PULSE_PERIOD_SEC = 1.6f
    const val BLINK_PERIOD_SEC = 1.0f
    const val FLOAT_PERIOD_SEC = 3.2f
    const val BOUNCE_PERIOD_SEC = 1.1f
    const val SLIDE_IN_SEC = 0.9f
    const val SLIDE_IN_DISTANCE_PERCENT = 30f

    fun modulate(mode: OverlayAnimation, elapsedSec: Float): LayerModulation =
        when (mode) {
            OverlayAnimation.NONE -> LayerModulation.NONE
            OverlayAnimation.FADE_IN -> LayerModulation(
                1f, (elapsedSec / FADE_IN_SEC).coerceIn(0f, 1f), 0f, 0f
            )
            OverlayAnimation.PULSE -> LayerModulation(
                1f + 0.08f * (0.5f + 0.5f * kotlin.math.sin(
                    2f * Math.PI.toFloat() * elapsedSec / PULSE_PERIOD_SEC
                )),
                1f, 0f, 0f
            )
            OverlayAnimation.BLINK -> LayerModulation(
                1f,
                if ((elapsedSec % BLINK_PERIOD_SEC) < BLINK_PERIOD_SEC * 0.6f) 1f else 0.15f,
                0f, 0f
            )
            OverlayAnimation.FLOAT -> LayerModulation(
                1f, 1f, 0f,
                -2f * kotlin.math.sin(2f * Math.PI.toFloat() * elapsedSec / FLOAT_PERIOD_SEC)
            )
            OverlayAnimation.BOUNCE -> LayerModulation(
                1f, 1f, 0f,
                -6f * kotlin.math.abs(
                    kotlin.math.sin(Math.PI.toFloat() * elapsedSec / BOUNCE_PERIOD_SEC)
                )
            )
            OverlayAnimation.SLIDE_IN_LEFT -> LayerModulation(
                1f, 1f,
                SLIDE_IN_DISTANCE_PERCENT * (1f - (elapsedSec / SLIDE_IN_SEC).coerceIn(0f, 1f)),
                0f
            )
            OverlayAnimation.SLIDE_IN_RIGHT -> LayerModulation(
                1f, 1f,
                -SLIDE_IN_DISTANCE_PERCENT * (1f - (elapsedSec / SLIDE_IN_SEC).coerceIn(0f, 1f)),
                0f
            )
        }
}

/**
 * Shared layer sprite layout: aspect-correct, percent-of-stream, from the
 * layer config. Used at build time AND for live in-place updates.
 */
internal fun layerSpriteLayout(
    render: BaseObjectFilterRender,
    config: OverlayConfig,
    bmpW: Int,
    bmpH: Int,
    streamWidth: Int,
    streamHeight: Int
) {
    val widthFrac = config.scale.coerceIn(0.01f, 1f)
    val heightFrac = widthFrac * (bmpH.toFloat() / bmpW.coerceAtLeast(1)) *
        (streamWidth.toFloat() / streamHeight)
    render.setScale(widthFrac * 100f, heightFrac * 100f)
    val leftFrac = config.positionX.coerceIn(0f, 1f) - widthFrac / 2f
    val topFrac = config.positionY.coerceIn(0f, 1f) - heightFrac / 2f
    render.setPosition(
        (leftFrac * 100f).coerceIn(-100f, 200f),
        (topFrac * 100f).coerceIn(-100f, 200f)
    )
    render.rotation = config.rotation.coerceIn(-359, 359)
    render.alpha = config.opacity
}

/**
 * Base + animation state for object renders. Position is stored absolutely
 * and re-applied after every scale() call so long-running animations can
 * never drift (10-hour stability).
 */
private class AnimatedState(
    @Volatile var baseScaleX: Float = 100f,
    @Volatile var baseScaleY: Float = 100f,
    @Volatile var baseX: Float = 0f,
    @Volatile var baseY: Float = 0f,
    @Volatile var baseAlpha: Float = 1f
)

/**
 * Shared animation application for object renders. Applies scale/position
 * modulation to the sprite (absolute base position is restored after every
 * scale() so long-running animations can never drift) and returns the
 * modulation so the caller can apply the alpha factor.
 */
private fun BaseObjectFilterRender.applyModulation(
    state: AnimatedState, mode: OverlayAnimation, startNanos: Long
): LayerModulation {
    if (mode == OverlayAnimation.NONE) return LayerModulation.NONE
    val elapsed = (System.nanoTime() - startNanos) / 1_000_000_000f
    val m = LayerAnimations.modulate(mode, elapsed)
    if (m.scaleFactor != 1f) {
        val sx = (state.baseScaleX * m.scaleFactor).coerceAtMost(400f)
        val sy = (state.baseScaleY * m.scaleFactor).coerceAtMost(400f)
        sprite.scale(sx, sy)
        // scale() re-anchors the position — restore the absolute base.
        sprite.translate(state.baseX + m.offsetPercentX, state.baseY + m.offsetPercentY)
    } else if (m.offsetPercentX != 0f || m.offsetPercentY != 0f) {
        sprite.translate(state.baseX + m.offsetPercentX, state.baseY + m.offsetPercentY)
    }
    return m
}

/** Animated image layer (logos, subscribe buttons, static media). */
@RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
class AnimatedImageFilterRender(
    private val animationMode: OverlayAnimation
) : ImageFilterRender() {

    private val state = AnimatedState()
    private val startNanos = System.nanoTime()

    fun setBase(scaleX: Float, scaleY: Float, x: Float, y: Float, alphaValue: Float) {
        state.baseScaleX = scaleX; state.baseScaleY = scaleY
        state.baseX = x; state.baseY = y; state.baseAlpha = alphaValue
        sprite.scale(scaleX, scaleY)
        sprite.translate(x, y)
    }

    override fun drawFilter() {
        val m = applyModulation(state, animationMode, startNanos)
        alpha = state.baseAlpha * m.alphaFactor
        super.drawFilter()
    }
}

/** Animated text layer. */
@RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
class AnimatedTextFilterRender(
    private val animationMode: OverlayAnimation
) : TextFilterRender() {

    private val state = AnimatedState()
    private val startNanos = System.nanoTime()

    fun setBase(scaleX: Float, scaleY: Float, x: Float, y: Float, alphaValue: Float) {
        state.baseScaleX = scaleX; state.baseScaleY = scaleY
        state.baseX = x; state.baseY = y; state.baseAlpha = alphaValue
        sprite.scale(scaleX, scaleY)
        sprite.translate(x, y)
    }

    override fun drawFilter() {
        val m = applyModulation(state, animationMode, startNanos)
        alpha = state.baseAlpha * m.alphaFactor
        super.drawFilter()
    }
}

/**
 * MEMORY-SAFE GIF LAYER — decodes with the (deprecated but functional)
 * android.graphics.Movie API into ONE reusable bitmap at a capped size, and
 * advances frames on the GL thread at the GIF's own timing. All frames are
 * never resident — unlike naive full-frame preload.
 */
@RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
class GifLayerRender(
    context: Context,
    uriString: String,
    private val maxEdge: Int = 512
) : BaseObjectFilterRender() {

    private val appContext = context.applicationContext
    private val gifUri = uriString
    private var movie: android.graphics.Movie? = null
    private var frameBitmap: Bitmap? = null
    private var frameCanvas: Canvas? = null
    private var movieDurationMs = 1000
    private var lastDrawWallMs = 0L
    private var lastFrameIndex = -1
    @Volatile private var loadFailed = false

    init {
        streamObject = ImageStreamObject()
        Thread {
            try {
                appContext.contentResolver.openInputStream(Uri.parse(gifUri))?.use { input ->
                    val m = android.graphics.Movie.decodeStream(input)
                    if (m != null && m.width() > 0) {
                        // Decode bitmap at a capped size (memory safety).
                        val scale = minOf(1f, maxEdge.toFloat() / maxOf(m.width(), m.height()))
                        val w = (m.width() * scale).toInt().coerceAtLeast(8)
                        val h = (m.height() * scale).toInt().coerceAtLeast(8)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        frameBitmap = bmp
                        frameCanvas = Canvas(bmp)
                        movieDurationMs = if (m.duration() <= 0) 1000 else m.duration()
                        movie = m
                    } else loadFailed = true
                } ?: run { loadFailed = true }
            } catch (_: Throwable) {
                loadFailed = true
            }
        }.apply { isDaemon = true; name = "gif-layer-load" }.start()
    }

    override fun drawFilter() {
        val m = movie
        val bmp = frameBitmap
        if (m != null && bmp != null) {
            val now = System.currentTimeMillis()
            if (lastDrawWallMs == 0L) lastDrawWallMs = now
            val t = (now - lastDrawWallMs).toInt() % movieDurationMs
            val frameIndex = (t * 25) / maxOf(1, movieDurationMs) // ~25fps max advance
            if (frameIndex != lastFrameIndex) {
                lastFrameIndex = frameIndex
                // Draw the current GIF frame into the reused bitmap.
                m.setTime(t)
                frameCanvas?.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
                frameCanvas?.save()
                val bmpW = bmp.width.toFloat()
                val bmpH = bmp.height.toFloat()
                frameCanvas?.scale(bmpW / m.width(), bmpH / m.height())
                m.draw(frameCanvas, 0f, 0f)
                frameCanvas?.restore()
                (streamObject as ImageStreamObject).load(bmp)
                shouldLoad = true
            }
        }
        super.drawFilter()
        val texId = streamObjectTextureId[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glUniform1f(uAlphaHandle, if (texId == -1 || loadFailed) 0f else alpha)
    }

    /** Live geometry update (editor drag) — sprite only, no re-decode. */
    fun updateLayout(config: OverlayConfig, streamWidth: Int, streamHeight: Int) {
        val bmp = frameBitmap
        val w = bmp?.width ?: 512
        val h = bmp?.height ?: 512
        layerSpriteLayout(this, config, w, h, streamWidth, streamHeight)
    }

    override fun release() {
        super.release()
        frameBitmap?.recycle()
        frameBitmap = null
        movie = null
    }
}

/**
 * VIDEO-IN-VIDEO LAYER — a MediaPlayer decoding into a SurfaceTexture that
 * the compositor draws as a layer. The video is REAL: decoded by hardware,
 * sampled as an external OES texture, composited into the encoded frame.
 *
 * PiP video layers are intentionally SILENT (volume 0) — the main video's
 * audio + mic continue through the audio mixer untouched.
 */
@RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
class VideoLayerRender(
    context: Context,
    uriString: String,
    private val bufferW: Int,
    private val bufferH: Int
) : SurfaceFilterRender(null), OverlayFilterFactory.LayerRender {

    private val appContext = context.applicationContext
    private val videoUri = uriString
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var player: MediaPlayer? = null
    @Volatile private var released = false

    /**
     * The GL surface is created asynchronously when the filter initializes.
     * Bounded retry from the main thread: waits for the surface (up to ~5s),
     * sizes the buffer to the PiP display size (memory: NOT encoder-size),
     * then attaches a looping, silent MediaPlayer.
     */
    fun startPlayback() {
        mainHandler.post(object : Runnable {
            override fun run() {
                if (released) return
                val st: SurfaceTexture = surfaceTexture ?: run {
                    if (System.currentTimeMillis() - startedAt < 5_000) {
                        mainHandler.postDelayed(this, 50)
                    }
                    return
                }
                try {
                    st.setDefaultBufferSize(
                        bufferW.coerceAtLeast(16), bufferH.coerceAtLeast(16)
                    )
                    val p = MediaPlayer()
                    p.setDataSource(appContext, Uri.parse(videoUri))
                    p.setSurface(surface)
                    p.isLooping = true
                    p.setVolume(0f, 0f) // silent PiP — main audio path is untouched
                    p.setOnPreparedListener { mp -> if (!released) mp.start() }
                    p.setOnErrorListener { _, _, _ -> true }
                    p.prepareAsync()
                    player = p
                } catch (_: Throwable) {
                }
            }
        })
    }

    private val startedAt = System.currentTimeMillis()

    /** Live geometry update (editor drag) — sprite only, player untouched. */
    fun updateLayout(config: OverlayConfig, streamWidth: Int, streamHeight: Int) {
        layerSpriteLayout(this, config, bufferW, bufferH, streamWidth, streamHeight)
    }

    /** Stop + release the decoder. Safe to call from any thread. */
    override fun releaseLayer() {
        released = true
        mainHandler.post {
            try {
                player?.setSurface(null)
            } catch (_: Throwable) {
            }
            try {
                player?.release()
            } catch (_: Throwable) {
            }
            player = null
        }
    }
}

/**
 * Built-in SUBSCRIBE animation artwork — drawn once with Android Canvas
 * (rounded pill + bell), then animated (pulse) by the compositor.
 */
object SubscribeArt {

    fun create(text: String, widthPx: Int = 560, heightPx: Int = 160): Bitmap {
        val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val density = widthPx / 560f

        // Pill.
        val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFE62117.toInt()
        }
        val radius = heightPx / 2f
        canvas.drawRoundRect(
            RectF(0f, 0f, widthPx.toFloat(), heightPx.toFloat()), radius, radius, pill
        )

        // Bell icon.
        val bell = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        val cx = heightPx / 2f
        val cy = heightPx / 2f
        val s = density
        // Bell body.
        val path = android.graphics.Path().apply {
            moveTo(cx - 14 * s, cy + 14 * s)
            lineTo(cx + 14 * s, cy + 14 * s)
            lineTo(cx + 14 * s, cy + 6 * s)
            // right side up
            cubicTo(cx + 14 * s, cy - 2 * s, cx + 8 * s, cy - 6 * s, cx + 7 * s, cy - 16 * s)
            // top
            cubicTo(cx + 6 * s, cy - 22 * s, cx - 6 * s, cy - 22 * s, cx - 7 * s, cy - 16 * s)
            // left side down
            cubicTo(cx - 8 * s, cy - 6 * s, cx - 14 * s, cy - 2 * s, cx - 14 * s, cy + 6 * s)
            close()
        }
        canvas.drawPath(path, bell)
        // Clapper.
        canvas.drawCircle(cx, cy + 18 * s, 5 * s, bell)

        // Text.
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 64f * s
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD
            )
            textAlign = Paint.Align.LEFT
        }
        val textX = heightPx * 0.95f
        val baseline = cy - (textPaint.ascent() + textPaint.descent()) / 2f
        canvas.drawText(text, textX, baseline, textPaint)
        return bmp
    }
}
