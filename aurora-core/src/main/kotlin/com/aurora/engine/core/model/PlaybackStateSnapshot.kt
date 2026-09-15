package com.aurora.engine.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class ErrorCategory {
    AUTHENTICATION_REQUIRED,
    PROVIDER_REJECTION,
    BOT_DETECTION,
    RATE_LIMITED,
    NETWORK_FAILURE,
    TIMEOUT,
    UNPLAYABLE,
    CONTENT_RESTRICTION,
    INVALID_RESPONSE,
    TRANSFORMATION_REQUIRED,
    TOKEN_FAILURE,

    // Playback & Format categories
    SOURCE_EXPIRED,
    UNKNOWN_FORBIDDEN,
    DECODER_FAILURE,
    UNSUPPORTED_FORMAT,
    MALFORMED_MEDIA,

    // Backward compatibility aliases
    PROVIDER_BOT_DETECTION,
    NETWORK,
    HTTP_CLIENT,
    HTTP_SERVER,
    DECODING,
    IO,
    UNKNOWN
}

@Serializable
data class PlaybackError(
    val code: String,
    val message: String,
    val category: ErrorCategory,
    val isRecoverable: Boolean,
    val httpStatusCode: Int? = null,
    val timestampMs: Long = System.currentTimeMillis()
)

@Serializable
enum class EngineState {
    IDLE,
    RESOLVING,
    PREPARING,
    READY,
    BUFFERING,
    PLAYING,
    PAUSED,
    STALLED,
    RECOVERING,
    ENDED,
    ERROR
}

@Serializable
data class PlaybackStateSnapshot(
    val state: EngineState,
    val currentTrack: Track? = null,
    val currentSource: PlaybackSource? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val playbackSpeed: Float = 1.0f,
    val isPlaying: Boolean = false,
    val error: PlaybackError? = null,
    val timestampMs: Long = System.currentTimeMillis()
) {
    val progressFraction: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    val isTerminal: Boolean
        get() = state == EngineState.ENDED || state == EngineState.ERROR
}
