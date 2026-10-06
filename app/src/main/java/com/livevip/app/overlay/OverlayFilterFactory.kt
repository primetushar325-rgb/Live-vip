package com.livevip.app.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import com.pedro.encoder.input.gl.render.filters.BaseFilterRender
import com.pedro.encoder.input.gl.render.filters.`object`.BaseObjectFilterRender
import com.pedro.encoder.input.gl.render.filters.`object`.ImageFilterRender
import com.pedro.encoder.input.gl.render.filters.`object`.TextFilterRender
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.roundToInt

/**
 * Builds GL filter renders from [OverlayConfig]s. Every render produced here
 * is composited into the ENCODED frames through RootEncoder's filter chain
 * (GlStreamInterface → encoder surface) — not Android UI chrome.
 */
object OverlayFilterFactory {

    /** A config expanded into one or more GL renders. */
    class BuiltOverlay(
        val configId: Long,
        val renders: List<BaseFilterRender>,
        var sourceConfig: OverlayConfig
    )

    /** Renders that own non-GL resources (MediaPlayers, bitmaps) implement this. */
    interface LayerRender {
        /** Stop/release decoders etc. Called when the layer leaves the composition. */
        fun releaseLayer()
    }

    /**
     * Live text updater for CLOCK / COUNTDOWN overlays (1 Hz, main thread).
     * Each entry owns a closure that re-styles its render when text changes —
     * no stopping of the stream, no filter chain rebuild.
     */
    class TextTicker {
        private val handler = Handler(Looper.getMainLooper())
        private val entries = CopyOnWriteArrayList<Entry>()

        private class Entry(
            val provider: () -> String,
            val apply: (String) -> Unit,
            var lastText: String
        )

        private val tick = object : Runnable {
            override fun run() {
                entries.forEach { entry ->
                    val text = try {
                        entry.provider()
                    } catch (_: Throwable) {
                        entry.lastText
                    }
                    if (text != entry.lastText) {
                        entry.lastText = text
                        try {
                            entry.apply(text)
                        } catch (_: Throwable) {
                        }
                    }
                }
                if (entries.isNotEmpty()) handler.postDelayed(this, 1000)
            }
        }

        fun start() {
            handler.removeCallbacks(tick)
            if (entries.isNotEmpty()) handler.post(tick)
        }

        fun stop() {
            handler.removeCallbacks(tick)
            entries.clear()
        }

        fun register(initial: String, provider: () -> String, apply: (String) -> Unit) {
            entries.removeAll { it.provider === provider }
            entries += Entry(provider, apply, initial)
            start()
        }
    }

    /** Measure a text string the same way TextStreamObject renders it. */
    fun measureText(text: String, textSizePx: Float): Pair<Int, Int> {
        if (text.isEmpty()) return 1 to 1
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.textSize = textSizePx
            textAlign = Paint.Align.LEFT
        }
        val baseline = -paint.ascent()
        val width = (paint.measureText(text) + 0.5f).roundToInt()
        val height = (baseline + paint.descent() + 0.5f).roundToInt()
        return maxOf(1, width) to maxOf(1, height)
    }

    /**
     * @param streamWidth/streamHeight encoder dimensions (aspect-correct scaling).
     * @param imageLoader async bitmap loader so the UI never blocks on decode.
     */
    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
    fun build(
        context: Context,
        config: OverlayConfig,
        streamWidth: Int,
        streamHeight: Int,
        ticker: TextTicker?,
        imageLoader: ((uri: String, into: (Bitmap?) -> Unit) -> Unit)? = null
    ): BuiltOverlay {
        val renders = mutableListOf<BaseFilterRender>()
        when (config.type) {
            OverlayType.TEXT -> {
                val render = if (config.animation != OverlayAnimation.NONE) {
                    AnimatedTextFilterRender(config.animation)
                } else TextFilterRender()
                renders += render
                textStyle(render, config, streamWidth, streamHeight)(config.text)
                if (render is AnimatedTextFilterRender) {
                    applyAnimatedBase(render, config, streamWidth, streamHeight, config.text)
                }
            }

            OverlayType.WATERMARK,
            OverlayType.IMAGE -> {
                val render = if (config.animation != OverlayAnimation.NONE) {
                    AnimatedImageFilterRender(config.animation)
                } else ImageFilterRender()
                renders += render
                val uri = config.imageUri
                if (uri != null && imageLoader != null) {
                    imageLoader(uri) { bmp ->
                        if (bmp != null) applyImage(render, config, bmp, streamWidth, streamHeight)
                    }
                }
            }

            OverlayType.VIDEO -> {
                // Picture-in-picture video: MediaPlayer → SurfaceTexture →
                // composited OES layer in the encoded stream (silent).
                val widthPx = (config.scale.coerceIn(0.03f, 1f) * streamWidth)
                    .toInt().coerceIn(64, 1280)
                val heightPx = (widthPx * 9f / 16f).toInt().coerceIn(64, 1280)
                val render = VideoLayerRender(context, config.imageUri.orEmpty(), widthPx, heightPx)
                renders += render
                render.updateLayout(config, streamWidth, streamHeight)
                // PiP starts once the GL surface exists (startLayer is invoked
                // by the manager after addFilter).
            }

            OverlayType.GIF -> {
                val render = GifLayerRender(context, config.imageUri.orEmpty(), maxEdge = 512)
                renders += render
                render.updateLayout(config, streamWidth, streamHeight)
            }

            OverlayType.SUBSCRIBE -> {
                val render = AnimatedImageFilterRender(
                    if (config.animation == OverlayAnimation.NONE)
                        OverlayAnimation.PULSE else config.animation
                )
                renders += render
                val art = SubscribeArt.create(
                    config.text.ifEmpty { context.getString(
                        com.livevip.app.R.string.subscribe_default_text
                    ) }
                )
                applyImage(render, config, art, streamWidth, streamHeight)
            }

            OverlayType.BACKGROUND -> {
                // Full-canvas background: solid color or image behind the video.
                val render = ImageFilterRender()
                renders += render
                render.alpha = 1f
                if (config.imageUri != null && imageLoader != null) {
                    imageLoader(config.imageUri!!) { bmp ->
                        if (bmp != null) {
                            render.setImage(bmp)
                            render.setScale(100f, 100f)
                            render.setPosition(0f, 0f)
                        }
                    }
                } else {
                    val colorBmp = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
                    colorBmp.eraseColor(config.background)
                    render.setImage(colorBmp)
                    render.setScale(100f, 100f)
                    render.setPosition(0f, 0f)
                }
            }

            OverlayType.LOWER_THIRD -> {
                // Two stacked label renders: title + subtitle, bottom-anchored.
                val title = TextFilterRender()
                val subtitle = TextFilterRender()
                renders += title
                renders += subtitle
                textStyle(
                    render = title,
                    config = config.copy(background = 0xCC101018.toInt()),
                    streamWidth = streamWidth,
                    streamHeight = streamHeight,
                    yCenterOverride = 0.925f
                )(config.text)
                textStyle(
                    render = subtitle,
                    config = config.copy(
                        background = 0x88000000.toInt(),
                        fontSize = config.fontSize * 0.7f
                    ),
                    streamWidth = streamWidth,
                    streamHeight = streamHeight,
                    yCenterOverride = 0.968f
                )(config.secondaryText)
            }

            OverlayType.CLOCK -> {
                val render = TextFilterRender()
                renders += render
                val style = textStyle(render, config, streamWidth, streamHeight)
                val now: () -> String = {
                    java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                        .format(java.util.Date())
                }
                style(now())
                ticker?.register(now(), now, style)
            }

            OverlayType.COUNTDOWN -> {
                val render = TextFilterRender()
                renders += render
                val style = textStyle(render, config, streamWidth, streamHeight)
                val remaining: () -> String = {
                    val diff = (config.countdownTargetEpochMs - System.currentTimeMillis()) / 1000
                    if (diff <= 0) "00:00:00" else String.format(
                        java.util.Locale.US, "%02d:%02d:%02d",
                        diff / 3600, (diff % 3600) / 60, diff % 60
                    )
                }
                style(remaining())
                ticker?.register(remaining(), remaining, style)
            }

            OverlayType.SCROLLING_TEXT -> {
                val render = ScrollingTextFilterRender()
                renders += render
                render.speedPercentPerSec = config.scrollSpeedPercentPerSec
                render.centerYPercent = config.positionY * 100f
                render.alpha = config.opacity
                // Scrolling: scale only — position animates every frame.
                val applyScroll: (String) -> Unit = { text ->
                    val textSizePx = textSizeFor(config, streamWidth)
                    render.setText(text, textSizePx, config.color, Color.TRANSPARENT, Typeface.DEFAULT_BOLD)
                    val (w, h) = measureText(text, textSizePx)
                    scaleSprite(render, config, w, h, streamWidth, streamHeight)
                }
                applyScroll(config.text)
            }

            OverlayType.BORDER -> {
                val render = BorderFrameFilterRender()
                renders += render
                render.thickness = config.borderThickness.coerceIn(0f, 0.5f)
                render.frameColor = config.color
                render.opacity = config.opacity
            }
        }
        // Rotation applies to every object render (sprite transform).
        renders.forEach { render ->
            if (render is BaseObjectFilterRender) {
                render.rotation = config.rotation.coerceIn(-359, 359)
            }
        }
        return BuiltOverlay(config.id, renders, config)
    }

    /**
     * True when the config change requires a filter rebuild (vs in-place).
     * CLOCK/COUNTDOWN tickers and stacked LOWER_THIRD renders capture their
     * build-time config in per-second closures — any geometry change for
     * them must rebuild (cost ≈ their normal 1 Hz text refresh).
     */
    fun needsRebuild(old: OverlayConfig, newConfig: OverlayConfig): Boolean {
        if (old.type == OverlayType.CLOCK || old.type == OverlayType.COUNTDOWN ||
            old.type == OverlayType.LOWER_THIRD
        ) {
            if (old.positionX != newConfig.positionX ||
                old.positionY != newConfig.positionY ||
                old.scale != newConfig.scale ||
                old.rotation != newConfig.rotation ||
                old.opacity != newConfig.opacity
            ) return true
        }
        return old.type != newConfig.type ||
            old.imageUri != newConfig.imageUri ||
            old.animation != newConfig.animation ||
            old.text != newConfig.text ||
            old.secondaryText != newConfig.secondaryText ||
            old.fontSize != newConfig.fontSize ||
            old.color != newConfig.color ||
            old.background != newConfig.background ||
            old.scrollSpeedPercentPerSec != newConfig.scrollSpeedPercentPerSec ||
            old.borderThickness != newConfig.borderThickness ||
            old.countdownTargetEpochMs != newConfig.countdownTargetEpochMs
    }

    /**
     * IN-PLACE live update: position / scale / rotation / opacity change
     * WITHOUT rebuilding the filter — smooth dragging during a live stream,
     * zero RTMP/encoder impact, no bitmap re-decode.
     */
    fun updateInPlace(
        built: BuiltOverlay,
        config: OverlayConfig,
        streamWidth: Int,
        streamHeight: Int
    ) {
        built.sourceConfig = config
        built.renders.forEach { render ->
            // Video/GIF layers own their decoders — geometry updates go
            // through their own live layout (player/frames untouched).
            if (render is VideoLayerRender) {
                render.updateLayout(config, streamWidth, streamHeight)
                return@forEach
            }
            if (render is GifLayerRender) {
                render.updateLayout(config, streamWidth, streamHeight)
                return@forEach
            }
            if (render is BaseObjectFilterRender) {
                // Sprite geometry (aspect-correct percent-of-stream).
                val bmpW: Int
                val bmpH: Int
                when (render) {
                    is TextFilterRender -> {
                        val textSizePx = textSizeFor(config, streamWidth)
                        val (w, h) = measureText(config.text, textSizePx)
                        bmpW = w; bmpH = h
                    }
                    is AnimatedImageFilterRender -> {
                        // Subscribe art ratio.
                        bmpW = 560; bmpH = 160
                    }
                    else -> { bmpW = 512; bmpH = 512 } // GIF square assumption
                }
                val widthFrac = config.scale.coerceIn(0.01f, 1f)
                val heightFrac = widthFrac * (bmpH.toFloat() / bmpW) *
                    (streamWidth.toFloat() / streamHeight)
                val leftFrac = config.positionX.coerceIn(0f, 1f) - widthFrac / 2f
                val topFrac = config.positionY.coerceIn(0f, 1f) - heightFrac / 2f
                val x = (leftFrac * 100f).coerceIn(-100f, 200f)
                val y = (topFrac * 100f).coerceIn(-100f, 200f)
                render.rotation = config.rotation.coerceIn(-359, 359)
                when (render) {
                    is AnimatedTextFilterRender ->
                        render.setBase(widthFrac * 100f, heightFrac * 100f, x, y, config.opacity)
                    is AnimatedImageFilterRender ->
                        render.setBase(widthFrac * 100f, heightFrac * 100f, x, y, config.opacity)
                    else -> {
                        render.setScale(widthFrac * 100f, heightFrac * 100f)
                        render.setPosition(x, y)
                        render.alpha = config.opacity
                    }
                }
            } else if (render is BorderFrameFilterRender) {
                render.opacity = config.opacity
                render.thickness = config.borderThickness.coerceIn(0f, 0.5f)
                render.frameColor = config.color
            }
        }
    }

    /** Start decoders of layers that need the GL surface (video PiP). */
    fun startLayer(render: BaseFilterRender) {
        if (render is VideoLayerRender) render.startPlayback()
    }

    /** Release a layer's non-GL resources (called on removal). */
    fun releaseLayer(render: BaseFilterRender) {
        if (render is LayerRender) render.releaseLayer()
    }

    /** Position helper that stores the ABSOLUTE base for animated renders. */
    private fun applyAnimatedBase(
        render: AnimatedTextFilterRender,
        config: OverlayConfig,
        streamWidth: Int,
        streamHeight: Int,
        text: String
    ) {
        val textSizePx = textSizeFor(config, streamWidth)
        val (w, h) = measureText(text, textSizePx)
        val widthFrac = config.scale.coerceIn(0.01f, 1f)
        val heightFrac = widthFrac * (h.toFloat() / w) * (streamWidth.toFloat() / streamHeight)
        val leftFrac = config.positionX.coerceIn(0f, 1f) - widthFrac / 2f
        val topFrac = config.positionY.coerceIn(0f, 1f) - heightFrac / 2f
        render.setBase(
            widthFrac * 100f, heightFrac * 100f,
            (leftFrac * 100f).coerceIn(-100f, 200f),
            (topFrac * 100f).coerceIn(-100f, 200f),
            config.opacity
        )
    }

    /** GIF/PiP sprite layout using an assumed square source (aspect-corrected at draw). */
    private fun spriteLayout(
        render: BaseObjectFilterRender,
        config: OverlayConfig,
        bmpW: Int,
        bmpH: Int,
        streamWidth: Int,
        streamHeight: Int
    ) {
        layerSpriteLayout(render, config, bmpW, bmpH, streamWidth, streamHeight)
    }

    // ------------------------------------------------------------------
    // Positioning helpers (aspect-correct, percent-of-stream)
    // ------------------------------------------------------------------

    private fun textSizeFor(config: OverlayConfig, streamWidth: Int): Float =
        config.fontSize * (streamWidth / 1920f).coerceAtLeast(0.6f) * 2f

    /** Returns an apply(text) closure that styles + positions a text render. */
    private fun textStyle(
        render: TextFilterRender,
        config: OverlayConfig,
        streamWidth: Int,
        streamHeight: Int,
        yCenterOverride: Float? = null
    ): (String) -> Unit = { text ->
        val textSizePx = textSizeFor(config, streamWidth)
        render.setText(text, textSizePx, config.color, config.background, Typeface.DEFAULT_BOLD)
        render.alpha = config.opacity
        val (w, h) = measureText(text, textSizePx)
        placeSprite(render, config, w, h, streamWidth, streamHeight, yCenterOverride)
    }

    private fun scaleSprite(
        render: BaseFilterRender,
        config: OverlayConfig,
        bmpW: Int,
        bmpH: Int,
        streamWidth: Int,
        streamHeight: Int
    ) {
        val widthFrac = config.scale.coerceIn(0.01f, 1f)
        val heightFrac = widthFrac * (bmpH.toFloat() / bmpW) * (streamWidth.toFloat() / streamHeight)
        if (render is TextFilterRender || render is ImageFilterRender) {
            render.setScale(widthFrac * 100f, heightFrac * 100f)
        }
    }

    private fun placeSprite(
        render: BaseFilterRender,
        config: OverlayConfig,
        bmpW: Int,
        bmpH: Int,
        streamWidth: Int,
        streamHeight: Int,
        yCenterOverride: Float? = null
    ) {
        val widthFrac = config.scale.coerceIn(0.01f, 1f)
        val heightFrac = widthFrac * (bmpH.toFloat() / bmpW) * (streamWidth.toFloat() / streamHeight)
        if (render is TextFilterRender || render is ImageFilterRender) {
            render.setScale(widthFrac * 100f, heightFrac * 100f)
            val cx = config.positionX.coerceIn(0f, 1f)
            val cy = (yCenterOverride ?: config.positionY).coerceIn(0f, 1f)
            val leftFrac = cx - widthFrac / 2f
            val topFrac = cy - heightFrac / 2f
            render.sprite.translate(
                (leftFrac * 100f).coerceIn(-100f, 200f),
                (topFrac * 100f).coerceIn(-100f, 200f)
            )
        }
    }

    private fun applyImage(
        render: ImageFilterRender,
        config: OverlayConfig,
        bmp: Bitmap,
        streamWidth: Int,
        streamHeight: Int
    ) {
        render.alpha = config.opacity
        render.setImage(bmp)
        placeSprite(render, config, bmp.width, bmp.height, streamWidth, streamHeight)
        if (render is AnimatedImageFilterRender) {
            val widthFrac = config.scale.coerceIn(0.01f, 1f)
            val heightFrac = widthFrac * (bmp.height.toFloat() / bmp.width) *
                (streamWidth.toFloat() / streamHeight)
            val leftFrac = config.positionX.coerceIn(0f, 1f) - widthFrac / 2f
            val topFrac = config.positionY.coerceIn(0f, 1f) - heightFrac / 2f
            render.setBase(
                widthFrac * 100f, heightFrac * 100f,
                (leftFrac * 100f).coerceIn(-100f, 200f),
                (topFrac * 100f).coerceIn(-100f, 200f),
                config.opacity
            )
        }
    }

    /**
     * Decode helper for content-URI images (background executor). Memory-safe:
     * images are downsampled to at most [maxEdge] px per side BEFORE any bitmap
     * allocation — a 4K photo never fully enters RAM for a small logo layer.
     */
    fun decodeImage(context: Context, uriString: String, maxEdge: Int = 2048): Bitmap? = try {
        val uri = Uri.parse(uriString)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        var sample = 1
        var w = bounds.outWidth
        var h = bounds.outHeight
        if (w > 0 && h > 0) {
            while (w / (sample * 2) >= maxEdge / 2 && h / (sample * 2) >= maxEdge / 2) {
                sample *= 2
            }
            // Never upscale-decode: target the layer display size class.
            w /= sample; h /= sample
            if (maxOf(w, h) > maxEdge) sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, opts)
        }
    } catch (_: Throwable) {
        null
    }
}
