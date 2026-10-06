package com.livevip.app.core

import android.content.Context
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Build
import androidx.annotation.RequiresApi
import com.pedro.encoder.input.gl.render.filters.BaseFilterRender
import com.pedro.encoder.utils.gl.GlUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * THE COMPOSITION RENDERER — the single GL filter every frame passes through
 * before the encoder (and the preview, which draws the same filtered
 * texture):
 *
 *   glClear(black) → transformed video quad from [LiveCompositionState]
 *
 * ONE pipeline serves BOTH the preview surface and the encoded output
 * (RootEncoder draws the filter chain's final texture to each), so what the
 * user manipulates on screen is, pixel-for-pixel, the live stream.
 *
 * Updating the composition is a thread-safe buffer swap — no encoder
 * restart, no RTMP reconnect, no timestamp change.
 */
@RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
class CompositionRenderer : BaseFilterRender() {

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

    private val quadBuffer = ByteBuffer.allocateDirect(20 * FLOAT_SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    init {
        Matrix.setIdentityM(MVPMatrix, 0)
        Matrix.setIdentityM(STMatrix, 0)
        pushQuad()
    }

    /** Thread-safe live update — preview gestures and format switches. */
    fun update(state: LiveCompositionState) {
        quad = state.quadFor()
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
        // Output background fill — the letterbox/pillarbox color (black).
        GLES20.glClearColor(0f, 0f, 0f, 1f)
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
