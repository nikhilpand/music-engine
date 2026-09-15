package com.aurora.engine.transport.progressive.validation

import com.aurora.engine.core.model.ErrorCategory
import com.aurora.engine.core.model.PlaybackError
import com.aurora.engine.core.model.PlaybackSource

class UrlExpiredException(
    val trackId: String,
    val expiresAtMs: Long?,
    message: String
) : Exception(message)

class UrlExpirationValidator(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val safetyMarginMs: Long = 0L
) {
    /**
     * Validates whether the given PlaybackSource has expired.
     * Treats expiresAtMs as metadata, checking if now + safetyMarginMs >= expiresAtMs.
     *
     * @return Result.success(Unit) if valid, or Result.failure(UrlExpiredException) if expired.
     */
    fun validate(source: PlaybackSource): Result<Unit> {
        val expiry = source.expiresAtMs ?: return Result.success(Unit)
        val now = clock()
        return if (now + safetyMarginMs >= expiry) {
            Result.failure(
                UrlExpiredException(
                    trackId = source.trackId,
                    expiresAtMs = expiry,
                    message = "Playback source for track '${source.trackId}' has expired (expired at $expiry, current time $now)"
                )
            )
        } else {
            Result.success(Unit)
        }
    }

    /**
     * Translates an expiration failure into Aurora's structured PlaybackError model.
     */
    fun toPlaybackError(source: PlaybackSource, exception: Throwable? = null): PlaybackError {
        return PlaybackError(
            code = "SOURCE_URL_EXPIRED",
            message = exception?.message ?: "Playback source for track '${source.trackId}' has expired",
            category = ErrorCategory.SOURCE_EXPIRED,
            isRecoverable = true,
            timestampMs = clock()
        )
    }
}
