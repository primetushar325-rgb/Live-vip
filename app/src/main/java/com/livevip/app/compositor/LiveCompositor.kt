package com.livevip.app.compositor

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.view.Surface
import com.livevip.app.model.CompositionState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * Surface-to-surface GPU compositor. Decoder frames are never copied through Bitmap/CPU memory.
 * The encoder and offline preview consume the same CompositionState transform model.
 */
class LiveCompositor(
    private val composition: CompositionState,
    private val encoderInputSurface: Surface,
    private val onFrameRendered: (ptsUs: Long) -> Unit,
    private val onFirstFrame: () -> Unit,
    private val onError: (Throwable) -> Unit
) {
    private val ready = CountDownLatch(1)
    private val running = AtomicBoolean(true)
    private lateinit var thread: HandlerThreadLike
    private var eglDisplay: Any? = null
    private var eglContext: Any? = null
    private var eglSurface: Any? = null
    private var decoderTexture: SurfaceTexture? = null
    private var decoderSurface: Surface? = null
    private var program = 0
    private var textureId = 0
    private var firstFrame = false
    private var pendingPtsUs = 0L
    private var framePending = false
    private var sourceWidth = composition.sourceWidth
    private var sourceHeight = composition.sourceHeight

    fun start(): Surface {
        thread = HandlerThreadLike("LiveVip-compositor")
        thread.start {
            try {
                setupGl()
                ready.countDown()
            } catch (error: Throwable) {
                onError(error)
                ready.countDown()
            }
        }
        ready.await()
        return decoderSurface ?: throw IllegalStateException("VIDEO_DECODER_FAILED: compositor surface unavailable")
    }

    fun setSourceSize(width: Int, height: Int) {
        sourceWidth = width
        sourceHeight = height
    }

    fun submitDecodedFrame(ptsUs: Long) {
        if (!running.get()) return
        // The SurfaceTexture callback performs updateTexImage on the GL thread. Keeping the PTS
        // here prevents a decoder callback from touching EGL or full-resolution pixels.
        thread.post {
            pendingPtsUs = ptsUs
            framePending = true
        }
    }

    fun stop() {
        running.set(false)
        thread.post { tearDownGl() }
        thread.quit()
        runCatching { decoderSurface?.release() }
        decoderSurface = null
        decoderTexture = null
    }

    private fun setupGl() {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "EGL_INITIALIZATION_FAILED" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "EGL_INITIALIZATION_FAILED" }
        val configAttrs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, configAttrs, 0, configs, 0, 1, count, 0) && count[0] > 0) { "EGL_CONFIG_FAILED" }
        val contextAttrs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        val context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, contextAttrs, 0)
        check(context != null && context != EGL14.EGL_NO_CONTEXT) { "EGL_CONTEXT_FAILED" }
        val surface = EGL14.eglCreateWindowSurface(display, configs[0], encoderInputSurface, intArrayOf(EGL14.EGL_NONE), 0)
        check(surface != null && surface != EGL14.EGL_NO_SURFACE) { "EGL_SURFACE_FAILED" }
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "EGL_MAKE_CURRENT_FAILED" }
        eglDisplay = display
        eglContext = context
        eglSurface = surface

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        textureId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        decoderTexture = SurfaceTexture(textureId).also { texture ->
            texture.setOnFrameAvailableListener {
                runCatching {
                    if (framePending) {
                        texture.updateTexImage()
                        drawFrame(pendingPtsUs)
                    }
                }.onFailure(onError)
            }
        }
        decoderSurface = Surface(decoderTexture)
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "EGL_INITIALIZATION_FAILED" }
    }

    private fun drawFrame(ptsUs: Long) {
        if (!framePending || eglDisplay == null) return
        framePending = false
        val display = eglDisplay as android.opengl.EGLDisplay
        val surface = eglSurface as android.opengl.EGLSurface
        GLES20.glViewport(0, 0, composition.outputWidth, composition.outputHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        val texMatrix = FloatArray(16)
        decoderTexture?.getTransformMatrix(texMatrix)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTexMatrix"), 1, false, texMatrix, 0)
        val vertex = vertexData()
        val vertexBuffer = java.nio.ByteBuffer.allocateDirect(vertex.size * 4).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
        vertexBuffer.put(vertex).position(0)
        val position = GLES20.glGetAttribLocation(program, "aPosition")
        val texCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
        vertexBuffer.position(2)
        GLES20.glEnableVertexAttribArray(texCoord)
        GLES20.glVertexAttribPointer(texCoord, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(texCoord)
        EGLExt.eglPresentationTimeANDROID(display, surface, ptsUs * 1000L)
        check(EGL14.eglSwapBuffers(display, surface)) { "EGL_SWAP_FAILED" }
        if (!firstFrame) {
            firstFrame = true
            onFirstFrame()
        }
        onFrameRendered(ptsUs)
    }

    private fun vertexData(): FloatArray {
        val outAspect = composition.outputWidth.toFloat() / composition.outputHeight
        val srcAspect = max(1, sourceWidth).toFloat() / max(1, sourceHeight)
        val fill = composition.fitMode.name == "FILL"
        val width: Float
        val height: Float
        if ((srcAspect > outAspect) xor fill) {
            width = 1f
            height = outAspect / srcAspect
        } else {
            width = srcAspect / outAspect
            height = 1f
        }
        val zoom = composition.scale
        val tx = composition.translationX / composition.outputWidth * 2f
        val ty = composition.translationY / composition.outputHeight * 2f
        val l = -width * zoom + tx
        val r = width * zoom + tx
        val t = height * zoom + ty
        val b = -height * zoom + ty
        return floatArrayOf(l, t, 0f, 0f, r, t, 1f, 0f, l, b, 0f, 1f, r, b, 1f, 1f)
    }

    private fun tearDownGl() {
        runCatching { decoderSurface?.release() }
        decoderSurface = null
        decoderTexture?.release()
        decoderTexture = null
        if (program != 0) GLES20.glDeleteProgram(program)
        val display = eglDisplay as? android.opengl.EGLDisplay
        val surface = eglSurface as? android.opengl.EGLSurface
        val context = eglContext as? android.opengl.EGLContext
        if (display != null && display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != null) EGL14.eglDestroySurface(display, surface)
            if (context != null) EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
        eglDisplay = null
        eglSurface = null
        eglContext = null
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        fun compile(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "GL_SHADER_COMPILE_FAILED: ${GLES20.glGetShaderInfoLog(shader)}" }
            return shader
        }
        val vertex = compile(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val handle = GLES20.glCreateProgram()
        GLES20.glAttachShader(handle, vertex)
        GLES20.glAttachShader(handle, fragment)
        GLES20.glLinkProgram(handle)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(handle, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "GL_PROGRAM_LINK_FAILED: ${GLES20.glGetProgramInfoLog(handle)}" }
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        return handle
    }

    private class HandlerThreadLike(name: String) {
        private val worker = android.os.HandlerThread(name)
        private lateinit var handler: android.os.Handler
        fun start(initializer: () -> Unit) {
            worker.start()
            handler = android.os.Handler(worker.looper)
            handler.post(initializer)
        }
        fun post(block: () -> Unit) { if (::handler.isInitialized) handler.post(block) }
        fun quit() { worker.quitSafely() }
    }

    companion object {
        private const val VERTEX_SHADER = """
            uniform mat4 uTexMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            varying vec2 vTexCoord;
            void main() { gl_Position = aPosition; vTexCoord = (uTexMatrix * aTexCoord).xy; }
        """
        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES sTexture;
            varying vec2 vTexCoord;
            void main() { gl_FragColor = texture2D(sTexture, vTexCoord); }
        """
    }
}
