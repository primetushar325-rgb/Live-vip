package com.livevip.app.audio

import android.content.ContentResolver
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.livevip.app.stream.MasterClock
import java.util.concurrent.atomic.AtomicBoolean

/** Reads the source AAC track directly, preserving it instead of silently dropping video audio. */
class AudioPipeline(
    private val resolver: ContentResolver,
    private val uri: Uri,
    private val enabled: Boolean,
    private val onError: (String) -> Unit,
    private val clock: MasterClock
) {
    private var format: MediaFormat? = null
    private var durationUs: Long = 0
    private var trackIndex = -1
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var startWallUs = 0L
    private var loopCount = 0

    fun prepare(): Boolean {
        if (!enabled) return false
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(resolver, uri, null)
            for (index in 0 until extractor.trackCount) {
                val trackFormat = extractor.getTrackFormat(index)
                if (trackFormat.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = index
                    format = trackFormat
                    durationUs = trackFormat.getLong(MediaFormat.KEY_DURATION)
                    break
                }
            }
        } catch (security: SecurityException) {
            onError("VIDEO_PERMISSION_DENIED")
            extractor.release()
            return false
        } catch (error: Throwable) {
            onError("AUDIO_INIT_FAILED: ${error.message ?: "cannot open audio"}")
            extractor.release()
            return false
        }
        extractor.release()
        if (trackIndex < 0 || format == null) {
            onError("AUDIO_INIT_FAILED: source has no AAC/audio track")
            return false
        }
        return true
    }

    fun mediaFormat(): MediaFormat? = format
    fun durationUs(): Long = durationUs

    fun audioSpecificConfig(): ByteArray {
        val audio = format ?: return byteArrayOf(0x12, 0x10)
        val sampleRate = audio.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = audio.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val profile = if (audio.containsKey(MediaFormat.KEY_AAC_PROFILE)) audio.getInteger(MediaFormat.KEY_AAC_PROFILE) else 2
        val rates = intArrayOf(96_000, 88_200, 64_000, 48_000, 44_100, 32_000, 24_000, 22_050, 16_000, 12_000, 11_025, 8_000, 7_350)
        val index = rates.indexOfFirst { it == sampleRate }.takeIf { it >= 0 } ?: 4
        return byteArrayOf(
            ((profile shl 3) or (index shr 1)).toByte(),
            (((index and 1) shl 7) or (channels shl 3)).toByte()
        )
    }

    fun start(send: (ptsUs: Long, data: ByteArray, sampleRate: Int, channels: Int) -> Unit) {
        if (!enabled || format == null || running.get()) return
        running.set(true)
        startWallUs = System.nanoTime() / 1_000L
        thread = Thread({ loop(send) }, "LiveVip-audio").also { it.start() }
    }

    private fun loop(send: (Long, ByteArray, Int, Int) -> Unit) {
        val localFormat = format ?: return
        val sampleRate = localFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = localFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        while (running.get()) {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(resolver, uri, null)
                extractor.selectTrack(trackIndex)
                while (running.get()) {
                    val buffer = java.nio.ByteBuffer.allocate(512 * 1024)
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    val sourcePts = extractor.sampleTime.coerceAtLeast(0)
                    val outputPts = clock.audioPts(sourcePts + loopCount * durationUs.coerceAtLeast(1))
                    val dueAt = startWallUs + outputPts
                    val waitUs = dueAt - System.nanoTime() / 1_000L
                    if (waitUs > 0) Thread.sleep(waitUs / 1_000L, (waitUs % 1_000L).toInt() * 1_000)
                    val data = ByteArray(size)
                    buffer.position(0)
                    buffer.get(data)
                    send(outputPts, data, sampleRate, channels)
                    extractor.advance()
                }
            } catch (error: Throwable) {
                if (running.get()) onError("AUDIO_INIT_FAILED: ${error.message ?: "audio read failed"}")
                break
            } finally {
                extractor.release()
            }
            if (running.get()) loopCount++
        }
    }

    fun stop() {
        running.set(false)
        thread?.interrupt()
        thread = null
    }
}
