package com.livevip.wallpaper.service

import android.content.SharedPreferences
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import com.livevip.wallpaper.project.AppPrefs
import com.livevip.wallpaper.project.ProjectStore
import com.livevip.wallpaper.project.QualityMode
import com.livevip.wallpaper.render.EglCore
import com.livevip.wallpaper.render.RenderSettings
import com.livevip.wallpaper.render.SceneRenderer
import com.livevip.wallpaper.render.SceneRequest
import com.livevip.wallpaper.sensor.TiltSensor
import kotlin.math.max

/**
 * Live wallpaper entry point. Rendering runs on a dedicated thread that owns its own EGL context.
 *
 *  - The render thread exists only while a valid surface exists (created in onSurfaceCreated,
 *    joined in onSurfaceDestroyed), so GL resources are always released before the surface goes away.
 *  - Rendering pauses while the wallpaper is not visible; sensors are registered only while visible.
 *  - The active project and its tuning are reloaded when they change in the app.
 */
class LiveWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = LiveEngine()

    private inner class LiveEngine : WallpaperService.Engine(), SharedPreferences.OnSharedPreferenceChangeListener {
        private val prefs = AppPrefs(applicationContext)
        private val store = ProjectStore(applicationContext)
        private var renderThread: RenderThread? = null
        private var visible = false
        private var loadedProjectId: String? = null

        private val tilt = TiltSensor(applicationContext) { x, y -> renderThread?.setTarget(x, y) }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(true)
            prefs.registerListener(this)
        }

        override fun onDestroy() {
            prefs.unregisterListener(this)
            tilt.stop()
            stopRenderThread()
            super.onDestroy()
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            startRenderThread(holder.surface)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            renderThread?.resize(width, height)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            stopRenderThread()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            renderThread?.setVisible(visible)
            if (visible) {
                if (!tilt.start()) Log.i(TAG, "No motion sensor; using touch input")
            } else {
                tilt.stop()
            }
        }

        override fun onOffsetsChanged(
            xOffset: Float, yOffset: Float, xStep: Float, yStep: Float, xPixels: Int, yPixels: Int,
        ) {
            super.onOffsetsChanged(xOffset, yOffset, xStep, yStep, xPixels, yPixels)
            renderThread?.setHomeOffset(xOffset)
        }

        override fun onTouchEvent(event: MotionEvent?) {
            super.onTouchEvent(event)
            if (event == null || tilt.isRunning) return
            val frame = surfaceHolder.surfaceFrame
            val w = max(1, frame.width()).toFloat()
            val h = max(1, frame.height()).toFloat()
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                    renderThread?.setTarget(event.x / w * 2f - 1f, event.y / h * 2f - 1f)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> renderThread?.setTarget(0f, 0f)
            }
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
            when (key) {
                AppPrefs.KEY_ACTIVE -> pushScene(force = true)
                AppPrefs.KEY_REVISION, AppPrefs.KEY_QUALITY -> pushScene(force = false)
            }
        }

        private fun startRenderThread(surface: Surface) {
            stopRenderThread()
            val thread = RenderThread(surface, SceneRenderer()) { message ->
                Log.e(TAG, "Renderer stopped: $message")
            }
            renderThread = thread
            loadedProjectId = null
            pushScene(force = true)
            thread.start()
            thread.setVisible(visible)
        }

        private fun stopRenderThread() {
            renderThread?.stopAndJoin()
            renderThread = null
        }

        /**
         * Loads the active project from disk (on the calling thread; the files are small) and hands the
         * scene and tuning to the render thread. Texture upload happens on the render thread.
         */
        private fun pushScene(force: Boolean) {
            val thread = renderThread ?: return
            val quality = prefs.quality
            thread.setFrameRate(quality.maxFps)
            val id = prefs.activeProjectId
            if (id == null) {
                thread.requestScene(null)
                thread.updateSettings(RenderSettings(quality = quality))
                loadedProjectId = null
                return
            }
            try {
                val loaded = store.load(id)
                if (force || id != loadedProjectId) {
                    thread.requestScene(SceneRequest(store.projectDir(id), loaded.manifest))
                    loadedProjectId = id
                }
                thread.updateSettings(RenderSettings(loaded.tuning, quality))
            } catch (e: Exception) {
                Log.e(TAG, "Could not load active project", e)
                thread.requestScene(null)
                loadedProjectId = null
            }
        }
    }

    companion object {
        private const val TAG = "LiveVipWallpaper"
    }
}

/**
 * Owns the EGL context and runs the frame loop. Frames are only produced while [visible] is true.
 */
private class RenderThread(
    private val surface: Surface,
    private val renderer: SceneRenderer,
    private val onFailure: (String) -> Unit,
) : Thread("LiveVipRender") {

    private val lock = Object()
    @Volatile private var running = true
    @Volatile private var visible = false
    @Volatile private var maxFps = 60
    @Volatile private var width = 0
    @Volatile private var height = 0
    @Volatile private var appliedWidth = 0
    @Volatile private var appliedHeight = 0

    fun setVisible(value: Boolean) {
        synchronized(lock) {
            visible = value
            lock.notifyAll()
        }
    }

    fun setTarget(x: Float, y: Float) = renderer.setTarget(x, y)
    fun setHomeOffset(x: Float) = renderer.setHomeOffset(x)
    fun requestScene(request: SceneRequest?) = renderer.requestScene(request)
    fun updateSettings(settings: RenderSettings) = renderer.updateSettings(settings)
    fun setFrameRate(fps: Int) { maxFps = fps.coerceIn(10, 60) }

    fun resize(w: Int, h: Int) {
        width = w
        height = h
    }

    fun stopAndJoin() {
        running = false
        synchronized(lock) { lock.notifyAll() }
        try {
            join(3000)
        } catch (_: InterruptedException) {
            currentThread().interrupt()
        }
    }

    override fun run() {
        var egl: EglCore? = null
        try {
            val core = EglCore(surface)
            egl = core
            core.create()
            renderer.init()
            while (running) {
                synchronized(lock) {
                    while (running && !visible) lock.wait()
                }
                if (!running) break

                val w = width
                val h = height
                if (w > 0 && h > 0 && (w != appliedWidth || h != appliedHeight)) {
                    renderer.resize(w, h)
                    appliedWidth = w
                    appliedHeight = h
                }

                val start = System.nanoTime()
                renderer.drawFrame(start)
                if (!core.swap()) break

                val frameNs = 1_000_000_000L / maxFps
                val sleepNs = frameNs - (System.nanoTime() - start)
                if (sleepNs > 0) {
                    sleep(sleepNs / 1_000_000L, (sleepNs % 1_000_000L).toInt())
                }
            }
        } catch (e: InterruptedException) {
            // Shutdown requested while sleeping.
        } catch (e: Throwable) {
            onFailure(e.message ?: e.javaClass.simpleName)
        } finally {
            try {
                egl?.makeCurrent()
                renderer.release()
            } catch (_: Throwable) {
                // Context may already be lost; nothing else to free.
            }
            egl?.destroy()
        }
    }
}
