package com.livevip.app.ui

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import com.livevip.app.model.CompositionState
import com.livevip.app.model.FitMode
import kotlin.math.max

/** Offline-first preview. It has no dependency on RTMP, YouTube, or encoder state. */
class LivePreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : TextureView(context, attrs), TextureView.SurfaceTextureListener {
    var onFirstFrame: ((width: Int, height: Int) -> Unit)? = null
    var onPreviewError: ((String) -> Unit)? = null

    private var player: MediaPlayer? = null
    private var uri: Uri? = null
    private var composition = CompositionState()
    private var sourceWidth = 0
    private var sourceHeight = 0
    private var surface: Surface? = null
    private var firstFrame = false
    private var lastFrameAt = 0L
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            composition = composition.zoomBy(detector.scaleFactor)
            applyTransform()
            return true
        }
    })
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            resetTransform()
            return true
        }
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            composition = composition.dragBy(-dx, -dy)
            applyTransform()
            return true
        }
    })

    init {
        surfaceTextureListener = this
        isOpaque = false
    }

    fun setVideo(source: Uri) {
        uri = source
        firstFrame = false
        lastFrameAt = 0L
        releasePlayer()
        if (isAvailable) prepare(source, Surface(surfaceTexture))
    }

    fun fit() {
        composition = composition.fit()
        applyTransform()
    }

    fun fill() {
        composition = composition.fill()
        applyTransform()
    }

    fun resetTransform() {
        composition = composition.reset()
        applyTransform()
    }

    fun currentComposition(): CompositionState = composition

    fun stop() {
        releasePlayer()
        surface?.release()
        surface = null
    }

    private fun prepare(source: Uri, target: Surface) {
        surface = target
        val mediaPlayer = MediaPlayer()
        player = mediaPlayer
        try {
            mediaPlayer.setDataSource(context, source)
            mediaPlayer.setSurface(target)
            mediaPlayer.setOnPreparedListener { mp ->
                sourceWidth = mp.videoWidth
                sourceHeight = mp.videoHeight
                if (sourceWidth <= 0 || sourceHeight <= 0) {
                    onPreviewError?.invoke("VIDEO_DECODER_FAILED: decoder returned no dimensions")
                    return@setOnPreparedListener
                }
                composition = composition.copy(sourceWidth = sourceWidth, sourceHeight = sourceHeight)
                applyTransform()
                mp.isLooping = true
                mp.start()
            }
            mediaPlayer.setOnInfoListener { _, what, _ ->
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    firstFrame = true
                    lastFrameAt = System.currentTimeMillis()
                    onFirstFrame?.invoke(sourceWidth, sourceHeight)
                }
                false
            }
            mediaPlayer.setOnErrorListener { _, what, extra ->
                onPreviewError?.invoke("VIDEO_DECODER_FAILED: MediaPlayer error $what/$extra")
                true
            }
            mediaPlayer.prepareAsync()
        } catch (security: SecurityException) {
            onPreviewError?.invoke("VIDEO_PERMISSION_DENIED")
        } catch (error: Exception) {
            onPreviewError?.invoke("VIDEO_URI_INVALID: ${error.message ?: "cannot open source"}")
        }
    }

    private fun applyTransform() {
        if (width <= 0 || height <= 0 || sourceWidth <= 0 || sourceHeight <= 0) return
        val sourceAspect = sourceWidth.toFloat() / sourceHeight
        val viewAspect = width.toFloat() / height
        val base = if (composition.fitMode == FitMode.FILL) {
            max(width.toFloat() / sourceWidth, height.toFloat() / sourceHeight)
        } else {
            minOf(width.toFloat() / sourceWidth, height.toFloat() / sourceHeight)
        } * composition.scale
        val matrix = Matrix()
        matrix.setScale(base, base)
        matrix.postTranslate((width - sourceWidth * base) / 2f + composition.translationX, (height - sourceHeight * base) / 2f + composition.translationY)
        matrix.postRotate(composition.rotation, width / 2f, height / 2f)
        setTransform(matrix)
        invalidate()
    }

    private fun releasePlayer() {
        player?.runCatching { stop() }
        player?.release()
        player = null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        uri?.let { prepare(it, Surface(texture)) }
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = applyTransform()
    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) { lastFrameAt = System.currentTimeMillis() }
    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        releasePlayer()
        surface = null
        return true
    }
}
