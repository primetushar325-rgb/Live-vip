package com.livevip.app.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean

class VideoEncoderController(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrate: Int,
    private val onFormat: (MediaFormat) -> Unit,
    private val onAccessUnit: (data: ByteArray, ptsUs: Long, keyFrame: Boolean) -> Unit,
    private val onError: (Throwable) -> Unit
) {
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private val running = AtomicBoolean(false)
    private var drainThread: Thread? = null

    fun start(): Surface {
        if (width <= 0 || height <= 0) throw IllegalArgumentException("ENCODER_CONFIG_FAILED: invalid output size")
        val selected = try {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        } catch (error: Throwable) {
            onError(error)
            throw IllegalStateException("ENCODER_UNAVAILABLE", error)
        }
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        }
        try {
            selected.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = selected.createInputSurface()
            selected.start()
            codec = selected
            running.set(true)
            return inputSurface!!
        } catch (error: Throwable) {
            selected.release()
            onError(error)
            throw IllegalStateException("ENCODER_CONFIG_FAILED", error)
        }
    }

    fun startDraining() {
        if (drainThread != null) return
        drainThread = Thread({ drainLoop() }, "LiveVip-encoder-drain").also { it.start() }
    }

    private fun drainLoop() {
        val info = MediaCodec.BufferInfo()
        while (running.get()) {
            val current = codec ?: break
            try {
                when (val index = current.dequeueOutputBuffer(info, 100_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> onFormat(current.outputFormat)
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (index >= 0) {
                        val buffer = current.getOutputBuffer(index)
                        if (buffer != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val bytes = ByteArray(info.size)
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            buffer.get(bytes)
                            onAccessUnit(bytes, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
                        }
                        current.releaseOutputBuffer(index, false)
                    }
                }
            } catch (error: Throwable) {
                if (running.get()) onError(error)
                break
            }
        }
    }

    fun stop() {
        running.set(false)
        drainThread?.interrupt()
        drainThread = null
        runCatching { codec?.signalEndOfInputStream() }
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        runCatching { inputSurface?.release() }
        inputSurface = null
    }
}
