package com.livevip.app.model

enum class StreamState {
    IDLE,
    PREPARING,
    VIDEO_READY,
    AUDIO_READY,
    ENCODER_READY,
    CONNECTING,
    CONNECTED,
    SENDING,
    STREAMING,
    NETWORK_LOST,
    RECONNECTING,
    STOPPING,
    STOPPED,
    ERROR
}

enum class StreamError(val code: String) {
    VIDEO_URI_INVALID("VIDEO_URI_INVALID"),
    VIDEO_PERMISSION_DENIED("VIDEO_PERMISSION_DENIED"),
    VIDEO_DECODER_FAILED("VIDEO_DECODER_FAILED"),
    VIDEO_DECODER_TIMEOUT("VIDEO_DECODER_TIMEOUT"),
    NO_VIDEO_FRAME("NO_VIDEO_FRAME"),
    ENCODER_UNAVAILABLE("ENCODER_UNAVAILABLE"),
    ENCODER_CONFIG_FAILED("ENCODER_CONFIG_FAILED"),
    AUDIO_INIT_FAILED("AUDIO_INIT_FAILED"),
    RTMP_URL_INVALID("RTMP_URL_INVALID"),
    RTMP_CONNECT_TIMEOUT("RTMP_CONNECT_TIMEOUT"),
    RTMP_AUTH_FAILED("RTMP_AUTH_FAILED"),
    RTMP_SERVER_REJECTED("RTMP_SERVER_REJECTED"),
    NETWORK_TIMEOUT("NETWORK_TIMEOUT"),
    NETWORK_LOST("NETWORK_LOST"),
    STREAM_SEND_FAILED("STREAM_SEND_FAILED"),
    TIMESTAMP_ERROR("TIMESTAMP_ERROR"),
    YOUTUBE_INGEST_FAILED("YOUTUBE_INGEST_FAILED"),
    UNSUPPORTED_AUDIO_MIX("UNSUPPORTED_AUDIO_MIX"),
    UNKNOWN("UNKNOWN")
}

data class EngineSnapshot(
    val state: StreamState = StreamState.IDLE,
    val lastError: String = "NONE",
    val decoderReady: Boolean = false,
    val firstFrameReceived: Boolean = false,
    val previewFrames: Long = 0,
    val encoderReady: Boolean = false,
    val encodedFrames: Long = 0,
    val encodedBytes: Long = 0,
    val audioReady: Boolean = false,
    val packetsSent: Long = 0,
    val bytesSent: Long = 0,
    val bitrate: Long = 0,
    val actualFps: Float = 0f,
    val audioVideoOffsetUs: Long = 0,
    val loopCount: Int = 0,
    val reconnectCount: Int = 0
)
