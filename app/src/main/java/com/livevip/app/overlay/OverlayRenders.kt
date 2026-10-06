package com.livevip.app.overlay

import android.content.Context
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Build
import androidx.annotation.RequiresApi
import com.pedro.encoder.input.gl.render.filters.BaseFilterRender
import com.pedro.encoder.input.gl.render.filters.`object`.TextFilterRender
import com.pedro.encoder.utils.gl.GlUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Scrolling text overlay — a [TextFilterRender] whose position animates across
 * the frame every drawn frame (marquee). Runs entirely on the GL thread; no
 * timers, no stream interruption, works while LIVE.
 */
@RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
class ScrollingTextFilterRender : TextFilterRender() {

    private val startNanos = System.nanoTime()

    /** Percent of screen width per second (sprite coordinate space 0..100). */
    var speedPercentPerSec = 12f

    /** Vertical center of the strip (percent, 0 top … 100 bottom). */
    var centerYPercent = 88f

    override fun drawFilter() {
        val elapsedSec = (System.nanoTime() - startNanos) / 1_000_000_000f
        val width = sprite.scale.x
        // Travel from just off-screen right to just off-screen left, then wrap.
        val span = 100f + width * 2f
        val x = 100f + width - ((elapsedSec * speedPercentPerSec) % span)
        sprite.translate(x, centerYPercent - sprite.scale.y / 2f)
        super.drawFilter()
    }
}

/**
 * Border / frame overlay — full-frame fragment shader that draws a colored
 * frame of configurable thickness around the video. Composited into the
 * encoded stream like every other overlay.
 */
@RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
class BorderFrameFilterRender : BaseFilterRender() {

    private val squareVertexDataFilter = floatArrayOf( // X, Y, Z, U, V
        -1f, -1f, 0f, 0f, 0f,  // bottom left
        1f, -1f, 0f, 1f, 0f,   // bottom right
        -1f, 1f, 0f, 0f, 1f,   // top left
        1f, 1f, 0f, 1f, 1f     // top right
    )

    private var program = -1
    private var aPositionHandle = -1
    private var aTextureHandle = -1
    private var uMVPMatrixHandle = -1
    private var uSTMatrixHandle = -1
    private var uSamplerHandle = -1
    private var uThicknessHandle = -1
    private var uColorHandle = -1
    private var uAlphaHandle = -1

    /** 0..1 fraction of the smaller frame dimension. */
    var thickness = 0.025f

    /** ARGB color of the frame. */
    var frameColor = 0xFFFFFFFF.toInt()

    var opacity = 1f

    init {
        squareVertex = ByteBuffer.allocateDirect(squareVertexDataFilter.size * FLOAT_SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        squareVertex.put(squareVertexDataFilter).position(0)
        Matrix.setIdentityM(MVPMatrix, 0)
        Matrix.setIdentityM(STMatrix, 0)
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
            uniform float uThickness;
            uniform vec4 uColor;
            uniform float uAlpha;
            varying vec2 vTextureCoord;
            void main() {
              vec4 pixel = texture2D(uSampler, vTextureCoord);
              float t = clamp(uThickness, 0.0, 0.5);
              float border = step(vTextureCoord.x, t) + step(1.0 - t, vTextureCoord.x)
                           + step(vTextureCoord.y, t) + step(1.0 - t, vTextureCoord.y);
              border = clamp(border, 0.0, 1.0);
              vec4 framed = mix(pixel, vec4(uColor.rgb, 1.0), border * uColor.a * uAlpha);
              gl_FragColor = vec4(framed.rgb, pixel.a);
            }
        """.trimIndent()
        program = GlUtil.createProgram(vertexShader, fragmentShader)
        aPositionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        aTextureHandle = GLES20.glGetAttribLocation(program, "aTextureCoord")
        uMVPMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        uSTMatrixHandle = GLES20.glGetUniformLocation(program, "uSTMatrix")
        uSamplerHandle = GLES20.glGetUniformLocation(program, "uSampler")
        uThicknessHandle = GLES20.glGetUniformLocation(program, "uThickness")
        uColorHandle = GLES20.glGetUniformLocation(program, "uColor")
        uAlphaHandle = GLES20.glGetUniformLocation(program, "uAlpha")
    }

    override fun drawFilter() {
        GLES20.glUseProgram(program)
        squareVertex.position(SQUARE_VERTEX_DATA_POS_OFFSET)
        GLES20.glVertexAttribPointer(
            aPositionHandle, 3, GLES20.GL_FLOAT, false,
            SQUARE_VERTEX_DATA_STRIDE_BYTES, squareVertex
        )
        GLES20.glEnableVertexAttribArray(aPositionHandle)
        squareVertex.position(SQUARE_VERTEX_DATA_UV_OFFSET)
        GLES20.glVertexAttribPointer(
            aTextureHandle, 2, GLES20.GL_FLOAT, false,
            SQUARE_VERTEX_DATA_STRIDE_BYTES, squareVertex
        )
        GLES20.glEnableVertexAttribArray(aTextureHandle)
        GLES20.glUniformMatrix4fv(uMVPMatrixHandle, 1, false, MVPMatrix, 0)
        GLES20.glUniformMatrix4fv(uSTMatrixHandle, 1, false, STMatrix, 0)
        GLES20.glUniform1f(uThicknessHandle, thickness)
        val alpha = (android.graphics.Color.alpha(frameColor) / 255f) * opacity
        GLES20.glUniform4f(
            uColorHandle,
            android.graphics.Color.red(frameColor) / 255f,
            android.graphics.Color.green(frameColor) / 255f,
            android.graphics.Color.blue(frameColor) / 255f,
            1f
        )
        GLES20.glUniform1f(uAlphaHandle, alpha)
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
