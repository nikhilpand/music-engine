package com.aurora.engine.player.android.recovery

import com.aurora.engine.core.model.ErrorCategory
import com.aurora.engine.core.model.PlaybackError
import com.aurora.engine.core.model.PlaybackSource
import kotlinx.coroutines.delay

enum class RecoveryAction {
    RETRY_SAME_SOURCE,       // NETWORK_FAILURE, TIMEOUT with bounded exponential backoff
    COOLDOWN_RETRY,          // RATE_LIMITED with cooldown backoff
    PREPARE_NEW_SOURCE,      // Generic new source preparation (backward compatibility)
    RE_RESOLVE_SOURCE,       // SOURCE_EXPIRED: fresh resolution
    FALLBACK_RESOLUTION,     // PROVIDER_REJECTION, BOT_DETECTION: candidate invalidation + fallback resolution
    FORMAT_FALLBACK,         // DECODER_FAILURE, UNSUPPORTED_FORMAT: compatible format fallback
    TERMINAL_AUTH_REQUIRED,  // AUTHENTICATION_REQUIRED: terminal auth-required result
    FAIL                     // UNPLAYABLE, MALFORMED_MEDIA, UNKNOWN_FORBIDDEN, or max retries exceeded
}

data class RecoveryDecision(
    val action: RecoveryAction,
    val delayMs: Long = 0L,
    val newSource: PlaybackSource? = null,
    val resumePositionMs: Long = 0L,
    val reason: String
)

interface PlaybackRecoveryHandler {
    /**
     * Called when a source has expired or was rejected with 403 and requires fresh URL re-resolution.
     */
    suspend fun reResolveSource(expiredSource: PlaybackSource): PlaybackSource?

    /**
     * Called when the provider rejects the candidate (PROVIDER_REJECTION, BOT_DETECTION).
     * Must invalidate the rejected candidate and resolve an alternative strategy/source.
     */
    suspend fun onProviderRejection(failedSource: PlaybackSource, error: PlaybackError): PlaybackSource? {
        return fallbackSource(failedSource, error)
    }

    /**
     * Called when the provider or format fails and an alternative source/strategy should be used.
     */
    suspend fun fallbackSource(failedSource: PlaybackSource, error: PlaybackError): PlaybackSource?

    /**
     * Called when a decoder or format crash occurs and a compatible audio codec is needed.
     */
    suspend fun fallbackFormat(failedSource: PlaybackSource, error: PlaybackError): PlaybackSource? {
        return fallbackSource(failedSource, error)
    }
}

/**
 * Coordinates failure-specific recovery from playback errors:
 * - NETWORK_FAILURE -> bounded retry with exponential backoff
 * - TIMEOUT -> bounded retry with exponential backoff
 * - SOURCE_EXPIRED -> fresh resolution
 * - PROVIDER_REJECTION -> candidate invalidation + fallback resolution
 * - RATE_LIMITED -> cooldown/backoff
 * - DECODER_FAILURE / UNSUPPORTED_FORMAT -> compatible format fallback
 * - UNPLAYABLE / MALFORMED_MEDIA / UNKNOWN_FORBIDDEN -> terminal failure
 * - AUTHENTICATION_REQUIRED -> terminal/auth-required result
 */
class PlaybackRecoveryCoordinator(
    private val maxRetries: Int = 3,
    private val initialBackoffMs: Long = 200L,
    private val backoffMultiplier: Double = 2.0,
    private val recoveryHandler: PlaybackRecoveryHandler? = null
) {
    private var retryCount = 0
    private var lastFailedTrackId: String? = null
    private var preservedPositionMs: Long = 0L

    /**
     * Evaluates a playback failure and decides the failure-specific recovery path.
     */
    suspend fun handlePlaybackError(
        error: PlaybackError,
        currentSource: PlaybackSource,
        playbackPositionMs: Long
    ): RecoveryDecision {
        // Track position to resume from (preserve the highest valid position)
        if (playbackPositionMs > preservedPositionMs) {
            preservedPositionMs = playbackPositionMs
        }

        // Reset retry count if track changed
        if (lastFailedTrackId != currentSource.trackId) {
            lastFailedTrackId = currentSource.trackId
            retryCount = 0
            preservedPositionMs = playbackPositionMs
        }

        if (!error.isRecoverable) {
            return if (error.category == ErrorCategory.AUTHENTICATION_REQUIRED) {
                RecoveryDecision(
                    action = RecoveryAction.TERMINAL_AUTH_REQUIRED,
                    resumePositionMs = preservedPositionMs,
                    reason = "Authentication required to stream track '${currentSource.trackId}'; terminal action"
                )
            } else {
                RecoveryDecision(
                    action = RecoveryAction.FAIL,
                    resumePositionMs = preservedPositionMs,
                    reason = "Error category ${error.category} marked unrecoverable: ${error.message}"
                )
            }
        }

        return when (error.category) {
            // 1. SOURCE_EXPIRED -> fresh resolution
            ErrorCategory.SOURCE_EXPIRED -> {
                val freshSource = recoveryHandler?.reResolveSource(currentSource)
                if (freshSource != null) {
                    retryCount = 0 // Fresh source resets retry budget
                    RecoveryDecision(
                        action = RecoveryAction.RE_RESOLVE_SOURCE,
                        newSource = freshSource,
                        resumePositionMs = preservedPositionMs,
                        reason = "Source expired; successfully re-resolved fresh stream URL"
                    )
                } else {
                    RecoveryDecision(
                        action = RecoveryAction.FAIL,
                        resumePositionMs = preservedPositionMs,
                        reason = "Source expired and re-resolution failed or was unavailable"
                    )
                }
            }

            // 2. PROVIDER_REJECTION & BOT_DETECTION -> candidate invalidation + fallback resolution
            ErrorCategory.PROVIDER_REJECTION, ErrorCategory.BOT_DETECTION, ErrorCategory.PROVIDER_BOT_DETECTION -> {
                val fallbackSource = recoveryHandler?.onProviderRejection(currentSource, error)
                if (fallbackSource != null) {
                    retryCount = 0
                    RecoveryDecision(
                        action = RecoveryAction.FALLBACK_RESOLUTION,
                        newSource = fallbackSource,
                        resumePositionMs = preservedPositionMs,
                        reason = "Provider rejection (${error.code}); candidate invalidated, falling back to alternative strategy"
                    )
                } else {
                    RecoveryDecision(
                        action = RecoveryAction.FAIL,
                        resumePositionMs = preservedPositionMs,
                        reason = "Provider rejected stream (${error.code}) with no available fallback candidate"
                    )
                }
            }

            // 3. RATE_LIMITED -> cooldown/backoff
            ErrorCategory.RATE_LIMITED -> {
                if (retryCount >= maxRetries) {
                    RecoveryDecision(
                        action = RecoveryAction.FAIL,
                        resumePositionMs = preservedPositionMs,
                        reason = "Max rate limit retries ($maxRetries) exceeded"
                    )
                } else {
                    val cooldownDelay = 1000L * (retryCount + 1)
                    retryCount++
                    RecoveryDecision(
                        action = RecoveryAction.COOLDOWN_RETRY,
                        delayMs = cooldownDelay,
                        resumePositionMs = preservedPositionMs,
                        reason = "Rate limited; applying cooldown delay of ${cooldownDelay}ms (attempt $retryCount/$maxRetries)"
                    )
                }
            }

            // 4. DECODER_FAILURE & UNSUPPORTED_FORMAT -> compatible format fallback
            ErrorCategory.DECODER_FAILURE, ErrorCategory.UNSUPPORTED_FORMAT, ErrorCategory.DECODING -> {
                val fallbackSource = recoveryHandler?.fallbackFormat(currentSource, error)
                if (fallbackSource != null) {
                    retryCount = 0
                    RecoveryDecision(
                        action = RecoveryAction.FORMAT_FALLBACK,
                        newSource = fallbackSource,
                        resumePositionMs = preservedPositionMs,
                        reason = "Decoder/format failure; switching to compatible audio format: ${fallbackSource.audioFormat.codec}"
                    )
                } else {
                    RecoveryDecision(
                        action = RecoveryAction.FAIL,
                        resumePositionMs = preservedPositionMs,
                        reason = "Decoder/format failure and no compatible fallback format available"
                    )
                }
            }

            // 5. NETWORK_FAILURE & TIMEOUT -> bounded retry with exponential backoff
            ErrorCategory.NETWORK_FAILURE, ErrorCategory.NETWORK, ErrorCategory.TIMEOUT, ErrorCategory.IO -> {
                if (retryCount >= maxRetries) {
                    RecoveryDecision(
                        action = RecoveryAction.FAIL,
                        resumePositionMs = preservedPositionMs,
                        reason = "Max retries ($maxRetries) exceeded for network failure: ${error.message}"
                    )
                } else {
                    val delay = (initialBackoffMs * Math.pow(backoffMultiplier, retryCount.toDouble())).toLong()
                    retryCount++
                    RecoveryDecision(
                        action = RecoveryAction.RETRY_SAME_SOURCE,
                        delayMs = delay,
                        resumePositionMs = preservedPositionMs,
                        reason = "Transient network failure (attempt $retryCount/$maxRetries); backoff ${delay}ms"
                    )
                }
            }

            // 6. AUTHENTICATION_REQUIRED -> terminal/auth-required result
            ErrorCategory.AUTHENTICATION_REQUIRED -> {
                RecoveryDecision(
                    action = RecoveryAction.TERMINAL_AUTH_REQUIRED,
                    resumePositionMs = preservedPositionMs,
                    reason = "Authentication required for playback: ${error.message}"
                )
            }

            // 7. UNPLAYABLE, MALFORMED_MEDIA, UNKNOWN_FORBIDDEN -> terminal failure
            ErrorCategory.UNPLAYABLE, ErrorCategory.MALFORMED_MEDIA, ErrorCategory.UNKNOWN_FORBIDDEN,
            ErrorCategory.CONTENT_RESTRICTION, ErrorCategory.INVALID_RESPONSE,
            ErrorCategory.TRANSFORMATION_REQUIRED, ErrorCategory.TOKEN_FAILURE,
            ErrorCategory.HTTP_CLIENT, ErrorCategory.HTTP_SERVER, ErrorCategory.UNKNOWN -> {
                RecoveryDecision(
                    action = RecoveryAction.FAIL,
                    resumePositionMs = preservedPositionMs,
                    reason = "Terminal unrecoverable error: ${error.category} (${error.code})"
                )
            }
        }
    }

    /**
     * Executes the decision delay if any.
     */
    suspend fun applyDecisionDelay(decision: RecoveryDecision) {
        if (decision.delayMs > 0) {
            delay(decision.delayMs)
        }
    }

    fun reset() {
        retryCount = 0
        lastFailedTrackId = null
        preservedPositionMs = 0L
    }

    val currentRetryCount: Int
        get() = retryCount

    val lastPreservedPositionMs: Long
        get() = preservedPositionMs
}
