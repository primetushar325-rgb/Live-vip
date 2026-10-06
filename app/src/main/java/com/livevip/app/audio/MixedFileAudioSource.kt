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
 *        or SILENCE CLOCK (videos without an audio track — the mic still
 *        mixes in and the AAC timeline keeps running)
 *
 * - Mic OFF  → only the video's original audio keeps streaming.  ✅
 * - Video audio OFF → only the microphone streams.
 * - Both ON  → mixed with per-source volume (e.g. 70% / 30%).
 *
 * The video-file decoder is the master clock (its PCM cadence drives the
 * encoder timeline), microphone PCM is buffered and mixed in, so A/V sync
 * follows the video and loops never reset the encoder timestamps.
 *
 * PLAYLIST SUPPORT: [replaceFile] swaps the underlying decoder (or the
 * silence clock) WITHOUT touching the AAC encoder — playlist boundaries
 * are transparent to the output timeline.
 *
 * METERING: [videoLevel] / [micLevel] expose smoothed 0..1 RMS levels for
 * the UI's audio meters (cheap — computed during mixing, no extra reads).
 */
class MixedFileAudioSource(
    private val context: Context,
    uri: Uri,
    loopMode: Boolean = true,
    private val onLoop: () -> Unit = {},
    private val onAudioEnded: () -> Unit = {}
) : AudioSource(), GetMicrophoneData {

    @Volatile var videoVolume: Float = 1f
    @Volatile var micVolume: Float = 1f

    /** Smoothed audio level meters (0..1) for the UI. */
    @Volatile var videoLevel: Float = 0f
        private set
    @Volatile var micLevel: Float = 0f
        private set

    @Volatile private var videoAudioEnabled = true
    @Volatile private var micEnabled = false
    @Volatile private var micCreated = false

    /** True when the current file provides a decodable audio track. */
    @Volatile private var fileHasAudio = false
    @Volatile private var currentUri: Uri = uri

    /** Configured AAC format (stored for loop resync restarts). */
    @Volatile private var configuredSampleRate = 44100
    @Volatile private var configuredStereo = true

    private val decoderInterface = object : DecoderInterface {
        override fun onLoop() {
            // Audio decoder looped internally — informational only.
            // Program sequencing is driven by the VIDEO decoder.
            onLoop()
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
        AudioDecoderInterface {
            // Audio track EOF — informational. In playlist mode the next file
            // arrives on the video EOF boundary; until then keep silence.
            onAudioEnded()
        },
        decoderInterface
    )
    private val microphone = MicrophoneManager(this)
    private val micBuffer = PcmRingBuffer(DEFAULT_BUFFER_CAPACITY)
    private var running = false

    // Silence clock (used for videos WITHOUT an audio track so the AAC
    // timeline continues and the microphone can still be mixed in).
    @Volatile private var silenceEnabled = false
    private var silenceThread: Thread? = null
    private var silenceChunk = ByteArray(0)
    private var silencePeriodMs = 40L

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
        configuredSampleRate = sampleRate
        configuredStereo = isStereo
        val result = audioDecoder.initExtractor(context, currentUri)
        fileHasAudio = result
        if (result) {
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
            silenceEnabled = false
        } else {
            // No audio track in this file — drive the encoder with a silence
            // clock in the configured format (mic still mixes in).
            silenceEnabled = true
            prepareSilenceClock(sampleRate, isStereo)
        }
        return true
    }

    override fun start(getMicrophoneData: GetMicrophoneData) {
        this.getMicrophoneData = getMicrophoneData
        if (isRunning()) return
        running = true
        if (silenceEnabled) {
            startSilenceClock()
        } else {
            audioDecoder.prepareAudio()
            audioDecoder.start()
        }
        if (micEnabled) startMicInternal()
    }

    override fun stop() {
        getMicrophoneData = null
        running = false
        stopSilenceClock()
        try {
            audioDecoder.stop(false)
        } catch (_: Throwable) {
        }
        stopMicInternal()
        videoLevel = 0f
        micLevel = 0f
    }

    override fun isRunning(): Boolean = running

    override fun release() {
        try {
            audioDecoder.releaseExtractor()
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------------
    // Playlist support — swap files without touching the encoder
    // ------------------------------------------------------------------

    /**
     * Replace the playing file (playlist boundary). The AAC encoder, its
     * format and the output timeline are untouched.
     *
     * Must be called from a NON-decoder thread (the playlist transition
     * executor), not from the decoder's own callbacks.
     *
     * @return false when the new file's audio format is incompatible.
     */
    fun replaceFile(context: Context, uri: Uri, sampleRate: Int, isStereo: Boolean): Boolean {
        val appContext = context.applicationContext
        val newDecoder = AudioDecoder(
            fileDataCallback,
            AudioDecoderInterface { onAudioEnded() },
            decoderInterface
        )
        val ok = try {
            newDecoder.initExtractor(appContext, uri)
        } catch (_: Throwable) {
            false
        }
        if (ok) {
            if (newDecoder.sampleRate != sampleRate || newDecoder.isStereo != isStereo) {
                try {
                    newDecoder.releaseExtractor()
                } catch (_: Throwable) {
                }
                return false
            }
        }
        // Stop whatever is currently feeding PCM.
        stopSilenceClock()
        try {
            audioDecoder.stop(false)
        } catch (_: Throwable) {
        }
        val oldDecoder = audioDecoder
        audioDecoder = newDecoder
        fileHasAudio = ok
        silenceEnabled = !ok
        currentUri = uri
        if (!ok) prepareSilenceClock(sampleRate, isStereo)
        try {
            oldDecoder.releaseExtractor()
        } catch (_: Throwable) {
        }
        // Resume feeding with the new source if we are running.
        if (running) {
            if (silenceEnabled) {
                startSilenceClock()
            } else {
                try {
                    audioDecoder.prepareAudio()
                    audioDecoder.start()
                } catch (_: Throwable) {
                    // Decoder failed to start — fall back to silence clock so
                    // the broadcast keeps audio continuity.
                    silenceEnabled = true
                    startSilenceClock()
                }
            }
        }
        return true
    }

    /**
     * VIDEO LOOP BOUNDARY RESYNC (Part 4): the video decoder just looped.
     * If the audio decoder's file position is not near the loop start (its
     * track length differs from the video's), restart it from 0 so audio
     * content stays aligned with video content. The mixed output timeline,
     * encoder, muxer and RTMP are untouched — only this source re-seeks.
     *
     * Must be called from a non-decoder thread (main/transition executor).
     */
    fun resyncToLoopStart() {
        if (!running || silenceEnabled || !fileHasAudio) return
        val audioTimeSec = try {
            audioDecoder.time
        } catch (_: Throwable) {
            return
        }
        if (!AudioLoopSync.shouldResyncAtVideoLoop(audioTimeSec)) return
        android.util.Log.w(
            "MixedFileAudioSource",
            "audio resync at video loop: audio was at ${audioTimeSec.toInt()}s"
        )
        try {
            replaceFile(context, currentUri, configuredSampleRate, configuredStereo)
        } catch (_: Throwable) {
            // Keep the current decoder — worst case the drift persists until
            // the next boundary retry.
        }
    }

    // ------------------------------------------------------------------
    // Microphone line (fully independent of video audio)
    // ------------------------------------------------------------------

    /** Mic PCM arrives here on the microphone thread. */
    override fun inputPCMData(frame: Frame) {
        if (micEnabled) {
            updateLevel(frame, isMic = true)
            micBuffer.write(frame.buffer, frame.offset, frame.size)
        } else {
            micLevel = 0f
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

    fun getDuration() = if (fileHasAudio) audioDecoder.duration else 0.0

    /** Loop flag must be re-applied after [replaceFile] (new decoder object). */
    fun setLoopMode(enabled: Boolean) {
        try {
            audioDecoder.isLoopMode = enabled
        } catch (_: Throwable) {
        }
    }

    fun moveTo(time: Double) {
        if (fileHasAudio) {
            try {
                audioDecoder.moveTo(time)
            } catch (_: Throwable) {
            }
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
        micLevel = 0f
    }

    // ------------------------------------------------------------------
    // Silence clock (no-audio videos)
    // ------------------------------------------------------------------

    private fun prepareSilenceClock(sampleRate: Int, isStereo: Boolean) {
        val channels = if (isStereo) 2 else 1
        val bytesPerMs = sampleRate * channels * 2 / 1000
        silencePeriodMs = 40
        silenceChunk = ByteArray(bytesPerMs * silencePeriodMs.toInt())
    }

    private fun startSilenceClock() {
        if (silenceThread?.isAlive == true) return
        val chunk = silenceChunk
        if (chunk.isEmpty()) return
        silenceThread = Thread({
            while (running && silenceEnabled) {
                val callback = getMicrophoneData ?: break
                val frame = Frame(chunk, 0, chunk.size, System.nanoTime() / 1000)
                mixInto(frame.buffer, frame.offset, frame.size, meterVideo = false)
                try {
                    callback.inputPCMData(frame)
                } catch (_: Throwable) {
                    break
                }
                try {
                    Thread.sleep(silencePeriodMs)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "livevip-silence-clock").apply {
            isDaemon = true
            start()
        }
    }

    private fun stopSilenceClock() {
        silenceEnabled = false
        silenceThread?.interrupt()
        silenceThread = null
    }

    // ------------------------------------------------------------------
    // PCM16 mixing + level metering
    // ------------------------------------------------------------------

    private var micChunk = ByteArray(0)

    private fun mixInto(buffer: ByteArray, offset: Int, size: Int, meterVideo: Boolean = true) {
        val vVol = if (videoAudioEnabled) videoVolume else 0f
        val mVol = micVolume
        val micActive = micEnabled && micCreated

        if (micActive) {
            if (micChunk.size < size) micChunk = ByteArray(size)
            micBuffer.read(micChunk, size)
        }

        if (meterVideo && vVol > 0f) {
            updateLevelRaw(buffer, offset, size, vVol, isMic = false)
        } else if (meterVideo) {
            videoLevel = 0f
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

    /** Update the smoothed meter for a raw frame (mic input or mixed video PCM). */
    private fun updateLevel(frame: Frame, isMic: Boolean) {
        updateLevelRaw(frame.buffer, frame.offset, frame.size, 1f, isMic)
    }

    private fun updateLevelRaw(buffer: ByteArray, offset: Int, size: Int, gain: Float, isMic: Boolean) {
        var sum = 0.0
        var count = 0
        var i = offset
        val end = offset + size
        // Sample at most every 4th frame pair for speed.
        val step = if (size > 4096) 8 else 2
        while (i + 1 < end) {
            val sample = (((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort().toInt()) * gain
            sum += (sample * sample).toDouble()
            count++
            i += step
        }
        if (count == 0) return
        val rms = kotlin.math.sqrt(sum / count).toFloat() / 32768f
        val normalized = (rms * 2.5f).coerceIn(0f, 1f) // perceptual boost for meters
        if (isMic) {
            micLevel = if (normalized >= micLevel) normalized
            else micLevel * 0.80f + normalized * 0.20f // decay
        } else {
            videoLevel = if (normalized >= videoLevel) normalized
            else videoLevel * 0.80f + normalized * 0.20f
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
        var remaining = len
        // Drop the OLDEST data when overflowing to keep latency bounded:
        // first from what is buffered, then from the incoming chunk's head —
        // the buffer always ends up holding the NEWEST capacity bytes.
        val overflow = count + len - capacity
        if (overflow > 0) {
            val dropOld = minOf(overflow, count)
            head = (head + dropOld) % capacity
            count -= dropOld
            val dropFromSrc = overflow - dropOld
            srcPos += dropFromSrc
            remaining = len - dropFromSrc
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
    fun available(): Int = count

    @Synchronized
    fun clear() {
        head = 0
        count = 0
    }
}
