package com.livevip.app.rtmp

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.URI
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.math.min

/**
 * Small direct RTMP/RTMPS client. It sends actual FLV H.264/AAC messages and has no relay mode.
 * The client is deliberately transport-only; the streaming engine owns lifecycle and retries.
 */
class RtmpClient(private val onFailure: (Throwable) -> Unit = {}) {
    private var socket: Socket? = null
    private var input: BufferedInputStream? = null
    private var output: BufferedOutputStream? = null
    private var streamId = 1
    private var connected = false
    private var sentPackets = 0L
    private var sentBytes = 0L
    private var serverUrl: String = ""

    @Synchronized
    fun connect(url: String, streamKey: String, timeoutMs: Int = 15_000) {
        if (connected) return
        val parsed = parseUrl(url) ?: throw IllegalArgumentException("RTMP_URL_INVALID")
        serverUrl = url
        val rawSocket = if (parsed.secure) {
            (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket()
        } else Socket()
        rawSocket.soTimeout = timeoutMs
        rawSocket.connect(InetSocketAddress(parsed.host, parsed.port), timeoutMs)
        socket = rawSocket
        input = BufferedInputStream(rawSocket.getInputStream())
        output = BufferedOutputStream(rawSocket.getOutputStream())
        handshake()
        sendCommand("connect", 1.0, 0, listOf(
            Amf.string("app"), Amf.string(parsed.app),
            Amf.string("type"), Amf.string("nonprivate"),
            Amf.string("tcUrl"), Amf.string(url),
            Amf.string("fpad"), Amf.boolean(false),
            Amf.string("capabilities"), Amf.number(15.0),
            Amf.string("audioCodecs"), Amf.number(4071.0),
            Amf.string("videoCodecs"), Amf.number(252.0),
            Amf.string("videoFunction"), Amf.number(1.0),
            Amf.string("objectEncoding"), Amf.number(0.0)
        ), 0)
        awaitCommandResult(1)
        sendCommand("releaseStream", 2.0, 0, listOf(Amf.nullValue(), Amf.string(streamKey)), 0)
        sendCommand("FCPublish", 3.0, 0, listOf(Amf.nullValue(), Amf.string(streamKey)), 0)
        sendCommand("createStream", 4.0, 0, emptyList(), 0)
        streamId = awaitCreateStreamResult(4)
        sendCommand("publish", 0.0, streamId, listOf(Amf.string(streamKey), Amf.string("live")), streamId)
        connected = true
    }

    @Synchronized
    fun sendVideo(ptsUs: Long, accessUnit: ByteArray, keyFrame: Boolean) {
        check(connected) { "RTMP not connected" }
        val avcc = annexBToAvcc(accessUnit)
        val body = ByteArrayOutputStream()
        body.write(if (keyFrame) 0x17 else 0x27)
        body.write(0) // AVC NALU packet
        writeInt24(body, 0) // composition time offset
        body.write(avcc)
        sendMessage(4, 9, (ptsUs / 1000L).coerceAtLeast(0), body.toByteArray(), streamId)
    }

    fun sendVideoConfig(ptsUs: Long, avcConfig: ByteArray) {
        check(connected) { "RTMP not connected" }
        val body = ByteArrayOutputStream()
        body.write(0x17)
        body.write(0x00)
        writeInt24(body, 0)
        body.write(avcConfig)
        sendMessage(4, 9, (ptsUs / 1000L).coerceAtLeast(0), body.toByteArray(), streamId)
    }

    fun sendAudioConfig(ptsUs: Long, audioSpecificConfig: ByteArray) {
        check(connected) { "RTMP not connected" }
        val body = ByteArrayOutputStream()
        body.write(0xAF)
        body.write(0x00)
        body.write(audioSpecificConfig)
        sendMessage(5, 8, (ptsUs / 1000L).coerceAtLeast(0), body.toByteArray(), streamId)
    }

    fun sendAudio(ptsUs: Long, aacAccessUnit: ByteArray, sampleRate: Int = 44_100, channels: Int = 2) {
        check(connected) { "RTMP not connected" }
        val rateCode = when {
            sampleRate >= 44_000 -> 3
            sampleRate >= 22_000 -> 2
            sampleRate >= 11_000 -> 1
            else -> 0
        }
        val soundHeader = (10 shl 4) or (rateCode shl 2) or (1 shl 1) or if (channels > 1) 1 else 0
        val body = ByteArrayOutputStream()
        body.write(soundHeader)
        body.write(0x01)
        body.write(aacAccessUnit)
        sendMessage(5, 8, (ptsUs / 1000L).coerceAtLeast(0), body.toByteArray(), streamId)
    }

    @Synchronized
    fun disconnect() {
        connected = false
        runCatching { output?.flush() }
        runCatching { socket?.close() }
        input = null
        output = null
        socket = null
    }

    fun isConnected(): Boolean = connected
    fun sentPackets(): Long = sentPackets
    fun sentBytes(): Long = sentBytes

    private fun handshake() {
        val out = output ?: error("RTMP output unavailable")
        val inputStream = input ?: error("RTMP input unavailable")
        val random = ByteArray(1528).also { SecureRandom().nextBytes(it) }
        val c1 = ByteArrayOutputStream().apply {
            write(intBytes((System.currentTimeMillis() / 1000L).toInt()))
            write(byteArrayOf(0, 0, 0, 0))
            write(random)
        }.toByteArray()
        out.write(3)
        out.write(c1)
        out.flush()
        val s0 = inputStream.read()
        if (s0 != 3) error("RTMP handshake version $s0")
        val s1 = readFully(inputStream, 1536)
        val s2 = readFully(inputStream, 1536)
        out.write(s1)
        out.flush()
        // A valid RTMP peer may send the entire S2 before our C2; it has already been consumed above.
        if (s2.size != 1536) error("RTMP handshake incomplete")
    }

    private fun awaitCommandResult(transaction: Int) {
        repeat(12) {
            val message = readMessage() ?: return@repeat
            val amf = Amf.decode(message.payload)
            if (amf.any { it is String && it == "_error" }) throw IllegalStateException("RTMP_SERVER_REJECTED")
            if (amf.any { it is String && it == "_result" } && amf.any { it is Double && it.toInt() == transaction }) return
        }
        throw IllegalStateException("RTMP_CONNECT_TIMEOUT")
    }

    private fun awaitCreateStreamResult(transaction: Int): Int {
        repeat(12) {
            val message = readMessage() ?: return@repeat
            val amf = Amf.decode(message.payload)
            if (amf.any { it is String && it == "_error" }) throw IllegalStateException("RTMP_SERVER_REJECTED")
            if (amf.any { it is String && it == "_result" } && amf.any { it is Double && it.toInt() == transaction }) {
                return amf.filterIsInstance<Double>().lastOrNull()?.toInt() ?: error("RTMP_SERVER_REJECTED")
            }
        }
        throw IllegalStateException("RTMP_CONNECT_TIMEOUT")
    }

    private fun sendCommand(name: String, transaction: Double, messageStreamId: Int, args: List<ByteArray>, stream: Int) {
        val body = ByteArrayOutputStream().apply {
            write(Amf.string(name))
            write(Amf.number(transaction))
            write(Amf.nullValue())
            args.forEach(::write)
        }.toByteArray()
        sendMessage(3, 20, 0, body, messageStreamId)
    }

    @Synchronized
    private fun sendMessage(chunkStreamId: Int, type: Int, timestampMs: Long, payload: ByteArray, messageStreamId: Int) {
        try {
            val out = output ?: error("RTMP output unavailable")
            val ts = timestampMs.coerceAtMost(0xFFFFFF)
            out.write(chunkStreamId and 0x3f)
            writeInt24(out, ts.toInt())
            writeInt24(out, payload.size)
            out.write(type)
            out.write(intBytesLE(messageStreamId))
            var offset = 0
            val first = min(128, payload.size)
            out.write(payload, 0, first)
            offset += first
            while (offset < payload.size) {
                out.write((chunkStreamId and 0x3f) or 0xC0)
                val count = min(128, payload.size - offset)
                out.write(payload, offset, count)
                offset += count
            }
            out.flush()
            sentPackets++
            sentBytes += payload.size.toLong()
        } catch (error: Throwable) {
            connected = false
            onFailure(error)
            throw error
        }
    }

    private fun readMessage(): RtmpMessage? {
        val inputStream = input ?: return null
        val basic = inputStream.read()
        if (basic < 0) return null
        val fmt = basic ushr 6
        val csid = when (val id = basic and 0x3f) {
            0 -> 64 + inputStream.read()
            1 -> 64 + inputStream.read() + inputStream.read() * 256
            else -> id
        }
        if (fmt == 3) return RtmpMessage(csid, 0, 0, 0, readChunkPayload(inputStream, csid, 0))
        val timestamp = readInt24(inputStream)
        val length = readInt24(inputStream)
        val type = inputStream.read()
        val messageStreamId = readIntLE(inputStream)
        val payload = readChunkPayload(inputStream, csid, length)
        return RtmpMessage(csid, type, timestamp, messageStreamId, payload)
    }

    private fun readChunkPayload(inputStream: BufferedInputStream, csid: Int, expectedLength: Int): ByteArray {
        if (expectedLength <= 0) return ByteArray(0)
        val payload = ByteArray(expectedLength)
        var offset = 0
        while (offset < expectedLength) {
            val count = min(128, expectedLength - offset)
            readFully(inputStream, count).copyInto(payload, offset)
            offset += count
            if (offset < expectedLength) {
                val continuation = inputStream.read()
                if (continuation < 0) throw EOFException()
            }
        }
        return payload
    }

    private data class ParsedUrl(val secure: Boolean, val host: String, val port: Int, val app: String)
    private data class RtmpMessage(val csid: Int, val type: Int, val timestamp: Int, val streamId: Int, val payload: ByteArray)

    private fun parseUrl(value: String): ParsedUrl? {
        return try {
            val uri = URI(value)
            val secure = uri.scheme.equals("rtmps", ignoreCase = true)
            val host = uri.host
            if ((!secure && !uri.scheme.equals("rtmp", ignoreCase = true)) || host.isNullOrBlank()) {
                null
            } else {
                val port = if (uri.port > 0) uri.port else if (secure) 443 else 1935
                val app = uri.path.trim('/').ifBlank { "live" }.substringBefore('/')
                ParsedUrl(secure, host, port, app)
            }
        } catch (_: Throwable) {
            null
        }
    }

    companion object {
        fun annexBToAvcc(bytes: ByteArray): ByteArray {
            if (bytes.isEmpty()) return bytes
            fun startCodeAt(index: Int): Int = when {
                index + 3 < bytes.size && bytes[index] == 0.toByte() && bytes[index + 1] == 0.toByte() && bytes[index + 2] == 0.toByte() && bytes[index + 3] == 1.toByte() -> 4
                index + 2 < bytes.size && bytes[index] == 0.toByte() && bytes[index + 1] == 0.toByte() && bytes[index + 2] == 1.toByte() -> 3
                else -> 0
            }
            var cursor = 0
            val firstLength = if (bytes.size >= 4) {
                ((bytes[0].toInt() and 0xff) shl 24) or ((bytes[1].toInt() and 0xff) shl 16) or
                    ((bytes[2].toInt() and 0xff) shl 8) or (bytes[3].toInt() and 0xff)
            } else -1
            // Some MediaCodec implementations already return length-prefixed access units.
            if (startCodeAt(0) == 0 && firstLength > 0) {
                var avccCursor = 0
                var valid = true
                while (avccCursor + 4 <= bytes.size) {
                    val length = ((bytes[avccCursor].toInt() and 0xff) shl 24) or ((bytes[avccCursor + 1].toInt() and 0xff) shl 16) or
                        ((bytes[avccCursor + 2].toInt() and 0xff) shl 8) or (bytes[avccCursor + 3].toInt() and 0xff)
                    avccCursor += 4
                    if (length <= 0 || avccCursor + length > bytes.size) { valid = false; break }
                    avccCursor += length
                }
                if (valid && avccCursor == bytes.size) return bytes
            }
            val out = ByteArrayOutputStream()
            var found = false
            while (cursor < bytes.size) {
                while (cursor < bytes.size && startCodeAt(cursor) == 0) cursor++
                if (cursor >= bytes.size) break
                found = true
                cursor += startCodeAt(cursor)
                val start = cursor
                while (cursor < bytes.size && startCodeAt(cursor) == 0) cursor++
                val length = cursor - start
                if (length > 0) {
                    writeIntBE(out, length)
                    out.write(bytes, start, length)
                }
            }
            // If there was no Annex-B delimiter, treat the complete access unit as one NAL.
            if (!found) {
                writeIntBE(out, bytes.size)
                out.write(bytes)
            }
            return out.toByteArray()
        }

        private fun readFully(input: BufferedInputStream, size: Int): ByteArray {
            val data = ByteArray(size)
            var offset = 0
            while (offset < size) {
                val read = input.read(data, offset, size - offset)
                if (read < 0) throw EOFException()
                offset += read
            }
            return data
        }

        private fun intBytes(value: Int): ByteArray = ByteBuffer.allocate(4).putInt(value).array()
        private fun intBytesLE(value: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
        private fun readInt24(input: BufferedInputStream): Int = (input.read() shl 16) or (input.read() shl 8) or input.read()
        private fun readIntLE(input: BufferedInputStream): Int = input.read() or (input.read() shl 8) or (input.read() shl 16) or (input.read() shl 24)
        private fun writeInt24(out: ByteArrayOutputStream, value: Int) { out.write(value ushr 16); out.write(value ushr 8); out.write(value) }
        private fun writeInt24(out: BufferedOutputStream, value: Int) { out.write(value ushr 16); out.write(value ushr 8); out.write(value) }
        private fun writeIntBE(out: ByteArrayOutputStream, value: Int) { out.write(value ushr 24); out.write(value ushr 16); out.write(value ushr 8); out.write(value) }
    }
}

private object Amf {
    fun string(value: String): ByteArray = ByteArrayOutputStream().apply {
        write(2); write((value.length ushr 8) and 0xff); write(value.length and 0xff); write(value.toByteArray(Charsets.UTF_8))
    }.toByteArray()
    fun number(value: Double): ByteArray = ByteArrayOutputStream().apply { write(0); write(ByteBuffer.allocate(8).putDouble(value).array()) }.toByteArray()
    fun boolean(value: Boolean): ByteArray = byteArrayOf(1, if (value) 1 else 0)
    fun nullValue(): ByteArray = byteArrayOf(5)

    fun decode(payload: ByteArray): List<Any> {
        val values = mutableListOf<Any>()
        var p = 0
        while (p < payload.size) {
            when (payload[p++].toInt() and 0xff) {
                0 -> { if (p + 8 > payload.size) break; values += ByteBuffer.wrap(payload, p, 8).double; p += 8 }
                1 -> { values += payload.getOrNull(p++)?.toInt() == 1 }
                2 -> { if (p + 2 > payload.size) break; val n = ((payload[p++].toInt() and 0xff) shl 8) or (payload[p++].toInt() and 0xff); if (p + n > payload.size) break; values += String(payload, p, n, Charsets.UTF_8); p += n }
                3 -> { // object: skip keys and recursively collect values
                    while (p + 3 <= payload.size) {
                        val keyLength = ((payload[p++].toInt() and 0xff) shl 8) or (payload[p++].toInt() and 0xff)
                        if (keyLength == 0 && payload.getOrNull(p)?.toInt() == 9) { p++; break }
                        p += keyLength
                        if (p >= payload.size) break
                        val start = p
                        val type = payload[p++].toInt() and 0xff
                        p = start
                        when (type) {
                            0 -> { p += 1 + 8; values += ByteBuffer.wrap(payload, p - 8, 8).double }
                            1 -> { p += 2; values += (payload[p - 1].toInt() == 1) }
                            2 -> { p++; val n = ((payload[p++].toInt() and 0xff) shl 8) or (payload[p++].toInt() and 0xff); values += String(payload, p, n, Charsets.UTF_8); p += n }
                            else -> p = payload.size
                        }
                    }
                }
                5, 6 -> Unit
                8 -> { if (p + 4 <= payload.size) { val count = ByteBuffer.wrap(payload, p, 4).int; p += 4; repeat(count) { p += 2; p++ } } }
                else -> break
            }
        }
        return values
    }
}
