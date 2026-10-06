package com.livevip.app.video

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.view.Surface
import com.livevip.app.compositor.LiveCompositor
import java.util.concurrent.atomic.AtomicBoolean

/** Decoder-only source controller. It owns extractor/decoder and loops only the source. */
class VideoSourceController(
    private val context: Context,
    private val uri: Uri,
    private val compositor: LiveCompositor,
    private val onReady: (width: Int, height: Int, durationUs: Long) -> Unit,
    private val onFirstFrame: () -> Unit,
    private val onLoop: (Int) -> Unit,
    private val onError: (String) -> Unit
) {
    private var extractor: MediaExtractor? = null
    private var decoder: MediaCodec? = null
    private var videoTrack = -1
    private var durationUs = 0L
    private var sourceWidth = 0
    private var sourceHeight = 0
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    @Volatile private var firstFrame = false
    @Volatile private var loopCount = 0
    @Volatile private var outputOffsetUs = 0L

    fun prepare(decoderSurface: Surface) {
        val localExtractor = MediaExtractor()
        try {
            localExtractor.setDataSource(context, uri, null)
        } catch (security: SecurityException) {
            localExtractor.release()
            onError("VIDEO_PERMISSION_DENIED")
            throw security
        } catch (error: Throwable) {
            localExtractor.release()
            onError("VIDEO_URI_INVALID: ${error.message ?: "cannot open source"}")
            throw error
        }
        for (index in 0 until localExtractor.trackCount) {
            val format = localExtractor.getTrackFormat(index)
            if (format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                videoTrack = index
                sourceWidth = format.getInteger(MediaFormat.KEY_WIDTH)
                sourceHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
                durationUs = format.getLong(MediaFormat.KEY_DURATION)
                break
            }
        }
        if (videoTrack < 0 || sourceWidth <= 0 || sourceHeight <= 0) {
            localExtractor.release()
            onError("VIDEO_DECODER_FAILED: no video track")
            throw IllegalStateException("VIDEO_DECODER_FAILED")
        }
        val format = localExtractor.getTrackFormat(videoTrack)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: throw IllegalStateException("VIDEO_DECODER_FAILED")
        val localDecoder = try { MediaCodec.createDecoderByType(mime) } catch (error: Throwable) {
            localExtractor.release()
            onError("VIDEO_DECODER_FAILED: decoder unavailable")
            throw error
        }
        try {
            localExtractor.selectTrack(videoTrack)
            localDecoder.configure(format, decoderSurface, null, 0)
            localDecoder.start()
        } catch (error: Throwable) {
            localDecoder.release()
            localExtractor.release()
            onError("VIDEO_DECODER_FAILED: ${error.message ?: "configure failed"}")
            throw error
        }
        extractor = localExtractor
        decoder = localDecoder
        compositor.setSourceSize(sourceWidth, sourceHeight)
        onReady(sourceWidth, sourceHeight, durationUs)
    }

    fun start() {
        check(decoder != null && extractor != null) { "VIDEO_DECODER_FAILED: not prepared" }
        if (thread != null) return
        running.set(true)
        thread = Thread({ decodeLoop() }, "LiveVip-video-decoder").also { it.start() }
    }

    private fun decodeLoop() {
        val info = MediaCodec.BufferInfo()
        var inputEnded = false
        var lastFrameAt = System.currentTimeMillis()
        var timeoutReported = false
        while (running.get()) {
            val localExtractor = extractor ?: break
            val localDecoder = decoder ?: break
            if (!inputEnded) {
                val inputIndex = localDecoder.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    val input = localDecoder.getInputBuffer(inputIndex) ?: continue
                    val size = localExtractor.readSampleData(input, 0)
                    if (size < 0) {
                        localDecoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputEnded = true
                    } else {
                        localDecoder.queueInputBuffer(inputIndex, 0, size, localExtractor.sampleTime, 0)
                        localExtractor.advance()
                    }
                }
            }
            when (val outputIndex = localDecoder.dequeueOutputBuffer(info, 10_000)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!firstFrame && !timeoutReported && System.currentTimeMillis() - lastFrameAt > 8_000) {
                        timeoutReported = true
                        onError("VIDEO_DECODER_TIMEOUT")
                    }
                }
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                else -> if (outputIndex >= 0) {
                    val hasFrame = info.size > 0
                    localDecoder.releaseOutputBuffer(outputIndex, hasFrame)
                    if (hasFrame) {
                        if (!firstFrame) {
                            firstFrame = true
                            onFirstFrame()
                        }
                        lastFrameAt = System.currentTimeMillis()
                        val pts = info.presentationTimeUs.coerceAtLeast(0) + outputOffsetUs
                        compositor.submitDecodedFrame(pts)
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        localDecoder.flush()
                        localExtractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                        inputEnded = false
                        outputOffsetUs += durationUs.coerceAtLeast(1)
                        loopCount++
                        onLoop(loopCount)
                    }
                }
            }
        }
    }

    fun isFirstFrameReceived(): Boolean = firstFrame
    fun sourceDurationUs(): Long = durationUs
    fun loopCount(): Int = loopCount

    fun stop() {
        running.set(false)
        thread?.interrupt()
        thread = null
        runCatching { decoder?.stop() }
        runCatching { decoder?.release() }
        runCatching { extractor?.release() }
        decoder = null
        extractor = null
    }
}
