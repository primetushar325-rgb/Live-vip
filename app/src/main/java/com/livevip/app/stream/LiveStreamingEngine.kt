package com.livevip.app.stream

import android.content.Context
import android.media.MediaFormat
import com.livevip.app.audio.AudioPipeline
import com.livevip.app.compositor.LiveCompositor
import com.livevip.app.encoder.VideoEncoderController
import com.livevip.app.model.EngineSnapshot
import com.livevip.app.model.StreamError
import com.livevip.app.model.StreamSettings
import com.livevip.app.model.StreamState
import com.livevip.app.network.NetworkController
import com.livevip.app.rtmp.RtmpClient
import com.livevip.app.video.VideoSourceController
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** Foreground-service-owned controller for the complete media lifecycle. */
class LiveStreamingEngine(
    private val context: Context,
    private val settings: StreamSettings,
    private val listener: Listener
) {
    interface Listener {
        fun onSnapshot(snapshot: EngineSnapshot)
    }

    private val stateMachine = StreamStateMachine()
    private val running = AtomicBoolean(false)
    private val reconnecting = AtomicBoolean(false)
    private val encodedFrames = AtomicLong(0)
    private val encodedBytes = AtomicLong(0)
    private val sentPackets = AtomicLong(0)
    private val sentBytes = AtomicLong(0)
    private val clock = MasterClock()
    private val reconnectBackoff = ReconnectBackoff()
    private val network = NetworkController(context, { /* socket-level state is authoritative */ }, { onTransportFailure(StreamError.NETWORK_LOST.code) })
    private var rtmp: RtmpClient? = null
    private var encoder: VideoEncoderController? = null
    private var compositor: LiveCompositor? = null
    private var videoSource: VideoSourceController? = null
    private var audio: AudioPipeline? = null
    private var streamThread: Thread? = null
    private var lastSnapshot = EngineSnapshot()
    private var lastError = "NONE"
    private var firstFrame = false
    private var audioReady = false
    private var encoderReady = false
    private var startWallMs = 0L
    private var loopCount = 0
    private var reconnectCount = 0
    private var decoderWidth = 0
    private var decoderHeight = 0
    private var pendingVideoConfig: ByteArray? = null
    private var pendingAudioConfig: ByteArray? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        streamThread = thread(start = true, name = "LiveVip-stream-engine") {
            startInternal()
        }
    }

    private fun startInternal() {
        try {
            transition(StreamState.PREPARING)
            validateSettings()
            clock.reset(System.nanoTime() / 1_000L)
            startWallMs = System.currentTimeMillis()

            // The surface chain is built before the network. Preview and encoder cannot depend on RTMP.
            val outputWidth = if (settings.outputFormat.name == "VERTICAL") settings.quality.height else settings.quality.width
            val outputHeight = if (settings.outputFormat.name == "VERTICAL") settings.quality.width else settings.quality.height
            val initialComposition = settings.composition.copy(outputWidth = outputWidth, outputHeight = outputHeight)
            val localEncoder = VideoEncoderController(
                outputWidth, outputHeight, settings.fps, settings.quality.bitrate,
                onFormat = { format ->
                    pendingVideoConfig = format.toAvcDecoderConfiguration()
                    sendVideoConfigIfConnected()
                },
                onAccessUnit = { data, ptsUs, key ->
                    val client = rtmp
                    if (client != null) {
                        try {
                            client.sendVideo(clock.videoPts(ptsUs), data, key)
                            encodedFrames.incrementAndGet()
                            encodedBytes.addAndGet(data.size.toLong())
                            sentPackets.set(client.sentPackets())
                            sentBytes.set(client.sentBytes())
                            if (stateMachine.current == StreamState.SENDING) transition(StreamState.STREAMING)
                            publishSnapshot()
                        } catch (_: Throwable) {
                            // RtmpClient has already marked the socket failed; reconnect is transport-only.
                        }
                    }
                },
                onError = { fail(StreamError.ENCODER_CONFIG_FAILED, it.message) }
            )
            val inputSurface = localEncoder.start()
            encoder = localEncoder
            encoderReady = true

            val localCompositor = LiveCompositor(
                initialComposition,
                inputSurface,
                onFrameRendered = { publishSnapshot() },
                onFirstFrame = { firstFrame = true; publishSnapshot() },
                onError = { fail(StreamError.VIDEO_DECODER_FAILED, it.message) }
            )
            compositor = localCompositor
            val decoderSurface = localCompositor.start()
            val localVideo = VideoSourceController(
                context,
                settings.videoUri,
                localCompositor,
                onReady = { width, height, _ ->
                    decoderWidth = width
                    decoderHeight = height
                    transition(StreamState.VIDEO_READY)
                    publishSnapshot()
                },
                onFirstFrame = { firstFrame = true; publishSnapshot() },
                onLoop = { loopCount = it; publishSnapshot() },
                onError = { error -> fail(error.toStreamError(), error) }
            )
            videoSource = localVideo
            localVideo.prepare(decoderSurface)
            // onReady has transitioned to VIDEO_READY; the encoder may have been prepared first.
            val localAudio = AudioPipeline(context, settings.videoUri, settings.videoAudio, { error ->
                if (settings.videoAudio) fail(StreamError.AUDIO_INIT_FAILED, error)
            }, clock)
            audio = localAudio
            audioReady = !settings.videoAudio || localAudio.prepare()
            pendingAudioConfig = if (audioReady && settings.videoAudio) localAudio.audioSpecificConfig() else null
            if (audioReady && settings.videoAudio && stateMachine.current == StreamState.VIDEO_READY) transition(StreamState.AUDIO_READY)
            if (stateMachine.current == StreamState.VIDEO_READY || stateMachine.current == StreamState.AUDIO_READY) transition(StreamState.ENCODER_READY)
            publishSnapshot()

            network.start()
            val client = RtmpClient { onTransportFailure(StreamError.NETWORK_LOST.code) }
            rtmp = client
            connectWithPolicy(client)
            if (!running.get()) return
            transition(StreamState.CONNECTED)
            sendVideoConfigIfConnected()
            sendAudioConfigIfConnected()
            transition(StreamState.SENDING)
            localEncoder.startDraining()
            localVideo.start()
            if (audioReady && settings.videoAudio) localAudio.start { pts, data, rate, channels ->
                try {
                    client.sendAudio(pts, data, rate, channels)
                    sentPackets.set(client.sentPackets())
                    sentBytes.set(client.sentBytes())
                } catch (_: Throwable) { /* transport callback schedules reconnect */ }
            }
            publishSnapshot()
        } catch (error: Throwable) {
            if (running.get()) fail(error.message?.toStreamError() ?: StreamError.UNKNOWN, error.message)
        }
    }

    private fun validateSettings() {
        if (!settings.videoUri.toString().isNotBlank()) error(StreamError.VIDEO_URI_INVALID.code)
        if (!settings.serverUrl.startsWith("rtmp://") && !settings.serverUrl.startsWith("rtmps://")) error(StreamError.RTMP_URL_INVALID.code)
        if (settings.streamKey.isBlank()) error(StreamError.RTMP_AUTH_FAILED.code)
        if (settings.microphone && settings.videoAudio) error(StreamError.UNSUPPORTED_AUDIO_MIX.code)
    }

    private fun connectWithPolicy(client: RtmpClient) {
        while (running.get()) {
            transitionIfPossible(StreamState.CONNECTING)
            try {
                client.connect(settings.serverUrl, settings.streamKey)
                reconnectBackoff.reset()
                return
            } catch (error: Throwable) {
                client.disconnect()
                if (!running.get()) return
                transitionIfPossible(StreamState.NETWORK_LOST)
                transitionIfPossible(StreamState.RECONNECTING)
                val delay = reconnectBackoff.nextDelayMs() ?: throw IllegalStateException(StreamError.RTMP_CONNECT_TIMEOUT.code)
                Thread.sleep(delay)
            }
        }
    }

    private fun onTransportFailure(reason: String) {
        if (!running.get() || !reconnecting.compareAndSet(false, true)) return
        thread(start = true, name = "LiveVip-rtmp-reconnect") {
            try {
                if (stateMachine.current != StreamState.STOPPING && stateMachine.current != StreamState.STOPPED) {
                    transitionIfPossible(StreamState.NETWORK_LOST)
                    rtmp?.disconnect()
                    transitionIfPossible(StreamState.RECONNECTING)
                    reconnectCount++
                    connectWithPolicy(rtmp ?: throw IllegalStateException(StreamError.RTMP_CONNECT_TIMEOUT.code))
                    if (running.get()) {
                        transitionIfPossible(StreamState.CONNECTED)
                        sendVideoConfigIfConnected()
                        sendAudioConfigIfConnected()
                        transitionIfPossible(StreamState.SENDING)
                        publishSnapshot()
                    }
                }
            } catch (error: Throwable) {
                fail(StreamError.NETWORK_LOST, reason.ifBlank { error.message })
            } finally {
                reconnecting.set(false)
            }
        }
    }

    private fun sendVideoConfigIfConnected() {
        val config = pendingVideoConfig ?: return
        val client = rtmp ?: return
        if (!client.isConnected()) return
        runCatching { client.sendVideoConfig(clock.lastVideoPts().coerceAtLeast(0), config) }
            .onFailure { onTransportFailure(StreamError.STREAM_SEND_FAILED.code) }
    }

    private fun sendAudioConfigIfConnected() {
        val config = pendingAudioConfig ?: return
        val client = rtmp ?: return
        if (!client.isConnected()) return
        runCatching { client.sendAudioConfig(clock.lastAudioPts().coerceAtLeast(0), config) }
            .onFailure { onTransportFailure(StreamError.STREAM_SEND_FAILED.code) }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        transitionIfPossible(StreamState.STOPPING)
        videoSource?.stop()
        audio?.stop()
        encoder?.stop()
        compositor?.stop()
        rtmp?.disconnect()
        network.stop()
        transitionIfPossible(StreamState.STOPPED)
        publishSnapshot()
    }

    private fun fail(error: StreamError, detail: String? = null) {
        if (!running.get()) return
        lastError = if (detail.isNullOrBlank()) error.code else "${error.code}: $detail"
        transitionIfPossible(StreamState.ERROR)
        publishSnapshot()
        stop()
    }

    private fun transition(next: StreamState) {
        stateMachine.transition(next)
        publishSnapshot()
    }

    private fun transitionIfPossible(next: StreamState) {
        if (stateMachine.canTransition(next)) {
            stateMachine.transition(next)
            publishSnapshot()
        }
    }

    private fun publishSnapshot() {
        val elapsedMs = (System.currentTimeMillis() - startWallMs).coerceAtLeast(1)
        val bitrate = sentBytes.get() * 8_000L / elapsedMs
        lastSnapshot = EngineSnapshot(
            state = stateMachine.current,
            lastError = lastError,
            decoderReady = decoderWidth > 0,
            firstFrameReceived = firstFrame,
            previewFrames = if (firstFrame) encodedFrames.get() else 0,
            encoderReady = encoderReady,
            encodedFrames = encodedFrames.get(),
            encodedBytes = encodedBytes.get(),
            audioReady = audioReady,
            packetsSent = sentPackets.get(),
            bytesSent = sentBytes.get(),
            bitrate = bitrate,
            actualFps = encodedFrames.get() * 1_000f / elapsedMs,
            audioVideoOffsetUs = clock.lastAudioPts() - clock.lastVideoPts(),
            loopCount = loopCount,
            reconnectCount = reconnectCount
        )
        listener.onSnapshot(lastSnapshot)
    }

    fun snapshot(): EngineSnapshot = lastSnapshot

    private fun String.toStreamError(): StreamError = StreamError.entries.firstOrNull { it.code == this || startsWith(it.code) } ?: StreamError.UNKNOWN

    private fun MediaFormat.toAvcDecoderConfiguration(): ByteArray? {
        val sps = getByteBuffer("csd-0")?.let { it.toByteArrayWithoutPadding() } ?: return null
        val pps = getByteBuffer("csd-1")?.let { it.toByteArrayWithoutPadding() } ?: return null
        val spsNal = stripStartCode(sps)
        val ppsNal = stripStartCode(pps)
        if (spsNal.size < 4 || ppsNal.isEmpty()) return null
        return byteArrayOf(1, spsNal[1], spsNal[2], spsNal[3], 0xff.toByte(), 0xe1.toByte(),
            ((spsNal.size ushr 8) and 0xff).toByte(), (spsNal.size and 0xff).toByte()) + spsNal +
            byteArrayOf(1, ((ppsNal.size ushr 8) and 0xff).toByte(), (ppsNal.size and 0xff).toByte()) + ppsNal
    }

    private fun java.nio.ByteBuffer.toByteArrayWithoutPadding(): ByteArray {
        val copy = duplicate()
        val data = ByteArray(copy.remaining())
        copy.get(data)
        return data
    }

    private fun stripStartCode(data: ByteArray): ByteArray = when {
        data.size >= 4 && data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 0.toByte() && data[3] == 1.toByte() -> data.copyOfRange(4, data.size)
        data.size >= 3 && data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 1.toByte() -> data.copyOfRange(3, data.size)
        else -> data
    }
}
