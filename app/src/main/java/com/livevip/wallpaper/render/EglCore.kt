package com.livevip.wallpaper.render

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.view.Surface

/**
 * Owns an EGL display, context and window surface for a live wallpaper render thread.
 * Requests OpenGL ES 3.0 first and falls back to ES 2.0 when the device or driver refuses it.
 */
class EglCore(private val window: Surface) {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    /** 3 for ES 3.0, 2 for ES 2.0 fallback. Valid after [create]. */
    var glesVersion: Int = 0
        private set

    fun create() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) throw IllegalStateException("No EGL display")
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) throw IllegalStateException("eglInitialize failed")

        for (requested in intArrayOf(3, 2)) {
            val config = chooseConfig(requested) ?: continue
            val ctx = EGL14.eglCreateContext(
                display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, requested, EGL14.EGL_NONE), 0,
            )
            if (ctx == null || ctx == EGL14.EGL_NO_CONTEXT) continue
            val surface = EGL14.eglCreateWindowSurface(display, config, window, intArrayOf(EGL14.EGL_NONE), 0)
            if (surface == null || surface == EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroyContext(display, ctx)
                continue
            }
            context = ctx
            eglSurface = surface
            glesVersion = requested
            makeCurrent()
            return
        }
        throw IllegalStateException("Could not create an OpenGL ES 3.0 or 2.0 context on this device")
    }

    private fun chooseConfig(version: Int): EGLConfig? {
        val renderable = if (version == 3) EGLExt.EGL_OPENGL_ES3_BIT_KHR else EGL14.EGL_OPENGL_ES2_BIT
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 0,
            EGL14.EGL_STENCIL_SIZE, 0,
            EGL14.EGL_RENDERABLE_TYPE, renderable,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) || count[0] == 0) return null
        return configs[0]
    }

    fun makeCurrent() {
        if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) {
            throw IllegalStateException("eglMakeCurrent failed: ${EGL14.eglGetError()}")
        }
    }

    /** Returns false when the window surface is lost (the caller should stop rendering). */
    fun swap(): Boolean = EGL14.eglSwapBuffers(display, eglSurface)

    /** Releases everything. Call on the same thread that made the context current. */
    fun destroy() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
        eglSurface = EGL14.EGL_NO_SURFACE
        context = EGL14.EGL_NO_CONTEXT
        display = EGL14.EGL_NO_DISPLAY
    }
}
