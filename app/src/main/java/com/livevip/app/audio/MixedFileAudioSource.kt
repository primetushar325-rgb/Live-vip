package com.livevip.app.audio

import android.content.Context
import android.net.Uri
import com.pedro.encoder.Frame
import com.pedro.encoder.input.audio.GetMicrophoneData
import com.pedro.encoder.input.audio.MicrophoneManager
import com.pedro.encoder.input.decoder.AudioDecoder
import com.pedro.encoder.input.decoder.AudioDecoderInterface
import com.pedro.encoder.input.decoder.DecoderInterface
import com.pedro.encoder.input.sources.audio.AudioSource

/**
 * LIVE VIP audio mixer source.
 *
 * Two fully independent audio lines, mixed into one PCM stream:
 *
 *   VIDEO FILE AUDIO  ──(volume)──┐
 *                                 ├──► MIX ──► AAC encoder ──► RTMP
 *   MICROPHONE       ──(volume)──┘
 *
 * - Mic OFF  → only the video's original audio keeps streaming.  ✅
 * - Video audio OFF → only the microphone streams.
 * - Both ON  → mixed with per-source volume (e.g. 70% / 30%).
 *
 * The video-file decoder is the master clock (its PCM cadence drives the
 * encoder timeline), microphone PCM is buffered and mixed in, so A/V sync
 * follows the video and loops never reset the encoder timestamps.
 */
class MixedFileAudioSource(
    private val context: Context,
    private val uri: Uri,
    loopMode: Boolean = true,
    private val onFinish: (isLoop: Boolean) -> Unit = {}
) : AudioSource(), GetMicrophoneData {

    @Volatile var videoVolume: Float = 1f
    @Volatile var micVolume: Float = 1f

    @Volatile private var videoAudioEnabled = true
    @Volatile private var micEnabled = false
    @Volatile private var micCreated = false

    private val decoderInterface = object : DecoderInterface {
        override fun onLoop() {
            onFinish(true)
        }
    }
    private val fileDataCallback = object : GetMicrophoneData {
        override fun inputPCMData(frame: Frame) {
            mixInto(frame.buffer, frame.offset, frame.size)
            getMicrophoneData?.inputPCMData(frame)
        }
    }
    private var audioDecoder = AudioDecoder(
        fileDataCallback,
        AudioDecoderInterface { onFinish(false) },
        decoderInterface
    )
    private val microphone = MicrophoneManager(this)
    private val micBuffer = PcmRingBuffer(DEFAULT_BUFFER_CAPACITY)
    private var running = false

    init {
        audioDecoder.isLoopMode = loopMode
    }

    // ------------------------------------------------------------------
    // AudioSource contract
    // ------------------------------------------------------------------

    override fun create(
        sampleRate: Int,
        isStereo: Boolean,
        echoCanceler: Boolean,
        noiseSuppressor: Boolean
    ): Boolean {
        val result = audioDecoder.initExtractor(context, uri)
        if (!result) {
            throw IllegalArgumentException("Audio file track not found")
        }
        if (audioDecoder.sampleRate != sampleRate) {
            throw IllegalArgumentException(
                "Audio file sample rate (${audioDecoder.sampleRate}) differs from configured: $sampleRate"
            )
        }
        if (audioDecoder.isStereo != isStereo) {
            throw IllegalArgumentException(
                "Audio file channels (stereo=${audioDecoder.isStereo}) differ from configured: $isStereo"
            )
        }
        return true
    }

    override fun start(getMicrophoneData: GetMicrophoneData) {
        this.getMicrophoneData = getMicrophoneData
        if (isRunning()) return
        audioDecoder.prepareAudio()
        audioDecoder.start()
        running = true
        if (micEnabled) startMicInternal()
    }

    override fun stop() {
        getMicrophoneData = null
        running = false
        try {
            audioDecoder.stop(false)
        } catch (_: Throwable) {
        }
        stopMicInternal()
    }

    override fun isRunning(): Boolean = running

    override fun release() {
        try {
            audioDecoder.releaseExtractor()
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------------
    // Microphone line (fully independent of video audio)
    // ------------------------------------------------------------------

    /** Mic PCM arrives here on the microphone thread. */
    override fun inputPCMData(frame: Frame) {
        if (micEnabled) {
            micBuffer.write(frame.buffer, frame.offset, frame.size)
        }
    }

    /**
     * Enable/disable the mic line. Returns false when the microphone
     * could not be created (missing permission / hardware busy) —
     * video audio keeps streaming regardless.
     */
    fun setMicEnabled(enabled: Boolean): Boolean {
        if (enabled == micEnabled) return true
        return if (enabled) {
            micEnabled = true
            if (running) startMicInternal() else true
        } else {
            micEnabled = false
            stopMicInternal()
            true
        }
    }

    fun isMicEnabled(): Boolean = micEnabled

    fun setVideoAudioEnabled(enabled: Boolean) {
        videoAudioEnabled = enabled
    }

    fun isVideoAudioEnabled(): Boolean = videoAudioEnabled

    fun getDuration() = audioDecoder.duration

    fun moveTo(time: Double) {
        try {
            audioDecoder.moveTo(time)
        } catch (_: Throwable) {
        }
    }

    private fun startMicInternal(): Boolean = try {
        if (!micCreated) {
            micCreated = microphone.createMicrophone(
                sampleRate, isStereo, echoCanceler, noiseSuppressor
            )
        }
        if (micCreated && !microphone.isRunning) {
            micBuffer.clear()
            microphone.start()
        }
        micCreated
    } catch (_: Throwable) {
        micEnabled = false
        false
    }

    private fun stopMicInternal() {
        try {
            if (microphone.isRunning) microphone.stop()
            micCreated = false
        } catch (_: Throwable) {
        }
        micBuffer.clear()
    }

    // ------------------------------------------------------------------
    // PCM16 mixing
    // ------------------------------------------------------------------

    private var micChunk = ByteArray(0)

    private fun mixInto(buffer: ByteArray, offset: Int, size: Int) {
        val vVol = if (videoAudioEnabled) videoVolume else 0f
        val mVol = micVolume
        val micActive = micEnabled && micCreated

        if (micActive) {
            if (micChunk.size < size) micChunk = ByteArray(size)
            micBuffer.read(micChunk, size)
        }

        if (!micActive && vVol >= 0.999f) return // fast path: untouched

        var i = offset
        var j = 0
        val end = offset + size
        while (i + 1 < end) {
            val fileSample =
                (((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort()
                    .toInt() * vVol)
            var mixed = fileSample
            if (micActive && j + 1 < micChunk.size) {
                val micSample =
                    ((micChunk[j + 1].toInt() shl 8) or (micChunk[j].toInt() and 0xFF)).toShort()
                        .toInt()
                mixed += micSample * mVol
            }
            val clamped = mixed.toInt().coerceIn(-32768, 32767)
            buffer[i] = (clamped and 0xFF).toByte()
            buffer[i + 1] = ((clamped shr 8) and 0xFF).toByte()
            i += 2
            j += 2
        }
    }

    companion object {
        /** ~1 second of 48kHz stereo 16-bit PCM. */
        private const val DEFAULT_BUFFER_CAPACITY = 48000 * 2 * 2
    }
}

/**
 * Thread-safe byte FIFO for mic PCM. Oldest data is dropped on overflow
 * (bounded latency); reads zero-fill when the mic is behind.
 */
class PcmRingBuffer(private val capacity: Int) {
    private val data = ByteArray(capacity)
    private var head = 0 // read position
    private var count = 0

    @Synchronized
    fun write(src: ByteArray, offset: Int, len: Int) {
        var srcPos = offset
        var remaining = len.coerceAtMost(capacity)
        // Drop oldest when overflowing to keep latency bounded.
        val overflow = count + remaining - capacity
        if (overflow > 0) {
            head = (head + overflow) % capacity
            count -= overflow
        }
        var tail = (head + count) % capacity
        while (remaining > 0) {
            val chunk = minOf(remaining, capacity - tail)
            System.arraycopy(src, srcPos, data, tail, chunk)
            tail = (tail + chunk) % capacity
            srcPos += chunk
            remaining -= chunk
            count += chunk
        }
    }

    /** Fills [dest] with up to [len] bytes; missing bytes become silence. */
    @Synchronized
    fun read(dest: ByteArray, len: Int) {
        val toRead = minOf(len, count, dest.size)
        var destPos = 0
        var remaining = toRead
        while (remaining > 0) {
            val chunk = minOf(remaining, capacity - head)
            System.arraycopy(data, head, dest, destPos, chunk)
            head = (head + chunk) % capacity
            destPos += chunk
            remaining -= chunk
            count -= chunk
        }
        // Zero-fill underrun.
        if (destPos < len) {
            java.util.Arrays.fill(dest, destPos, minOf(len, dest.size), 0)
        }
    }

    @Synchronized
    fun clear() {
        head = 0
        count = 0
    }
}
