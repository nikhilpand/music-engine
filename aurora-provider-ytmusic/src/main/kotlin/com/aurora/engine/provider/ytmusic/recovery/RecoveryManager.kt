package com.aurora.engine.provider.ytmusic.recovery

import com.aurora.engine.provider.ytmusic.transport.TransportType

/**
 * Manages playback recovery from errors (403, timeout, network change)
 * with byte/position-aware checkpointing.
 *
 * ## Problem
 *
 * When a 403 error occurs mid-stream, the player needs to:
 * 1. Remember the current playback position (time + byte offset).
 * 2. Resolve a new stream URL (possibly from a different client).
 * 3. Seek to the saved position to resume seamlessly.
 *
 * Without checkpointing, recovery restarts from the beginning of the track.
 *
 * ## Recovery Strategies
 *
 * - **REAUTH**: Re-resolve the stream URL with the same client. Used for token expiry.
 * - **ROTATE_CLIENT**: Try the next client in the ladder. Used for persistent 403s.
 * - **ROTATE_TRANSPORT**: Switch transport type (SABR ↔ Progressive). Used when one transport is failing.
 * - **BACKOFF**: Wait before retrying. Used after multiple consecutive failures.
 * - **GIVE_UP**: All recovery attempts exhausted. Report error to user.
 */
class RecoveryManager(
    private val maxRecoveryAttempts: Int = MAX_RECOVERY_ATTEMPTS,
    private val maxConsecutiveErrors: Int = MAX_CONSECUTIVE_ERRORS,
    private val backoffBaseMs: Long = BACKOFF_BASE_MS,
    private val backoffMaxMs: Long = BACKOFF_MAX_MS
) {
    private val activeRecoveries = mutableMapOf<String, RecoveryState>()

    /**
     * Record a playback checkpoint for a media item.
     * Call this periodically during normal playback (e.g., every 5 seconds).
     */
    fun checkpoint(
        mediaId: String,
        positionMs: Long,
        byteOffset: Long,
        durationMs: Long,
        currentTransport: TransportType,
        currentClientName: String
    ) {
        val state = activeRecoveries.getOrPut(mediaId) { RecoveryState(mediaId) }
        state.lastPositionMs = positionMs
        state.lastByteOffset = byteOffset
        state.durationMs = durationMs
        state.lastTransportType = currentTransport
        state.lastClientName = currentClientName
    }

    /**
     * Determine the recovery strategy for an error on the given media item.
     *
     * @return A [RecoveryAction] describing what to do next, or `null` if no
     *         recovery state exists for this media ID (first playback attempt).
     */
    fun onError(
        mediaId: String,
        errorType: PlaybackErrorType,
        currentClientName: String,
        currentTransport: TransportType,
        availableClients: Int,
        currentClientIndex: Int
    ): RecoveryAction {
        val state = activeRecoveries.getOrPut(mediaId) { RecoveryState(mediaId) }
        state.consecutiveErrors++
        state.totalErrors++

        // Check if we've exhausted all recovery attempts
        if (state.consecutiveErrors > maxRecoveryAttempts) {
            return RecoveryAction(
                strategy = RecoveryStrategy.GIVE_UP,
                resumePositionMs = state.lastPositionMs,
                resumeByteOffset = state.lastByteOffset,
                backoffMs = 0,
                reason = "Exhausted $maxRecoveryAttempts recovery attempts for $mediaId"
            )
        }

        return when (errorType) {
            PlaybackErrorType.HTTP_403 -> handle403(state, currentClientName, currentTransport, availableClients, currentClientIndex)
            PlaybackErrorType.STREAM_EXPIRED -> handleExpiry(state)
            PlaybackErrorType.NETWORK_CHANGE -> handleNetworkChange(state)
            PlaybackErrorType.TIMEOUT -> handleTimeout(state, currentTransport)
            PlaybackErrorType.CIPHER_FAILURE -> handleCipherFailure(state, availableClients, currentClientIndex)
            PlaybackErrorType.SABR_PROTOCOL_ERROR -> handleSabrError(state)
            PlaybackErrorType.UNKNOWN -> handleUnknown(state)
        }
    }

    /**
     * Record a successful recovery — resets consecutive error counter.
     */
    fun onRecoverySuccess(mediaId: String) {
        activeRecoveries[mediaId]?.consecutiveErrors = 0
    }

    /**
     * Get the last checkpoint for a media item.
     */
    fun getCheckpoint(mediaId: String): RecoveryCheckpoint? {
        val state = activeRecoveries[mediaId] ?: return null
        return RecoveryCheckpoint(
            positionMs = state.lastPositionMs,
            byteOffset = state.lastByteOffset,
            durationMs = state.durationMs
        )
    }

    /**
     * Clear recovery state for a media item (e.g., when switching tracks).
     */
    fun clear(mediaId: String) {
        activeRecoveries.remove(mediaId)
    }

    /**
     * Clear all recovery state.
     */
    fun clearAll() {
        activeRecoveries.clear()
    }

    // ── Strategy Handlers ───────────────────────────────────────────────────

    private fun handle403(
        state: RecoveryState,
        currentClientName: String,
        currentTransport: TransportType,
        availableClients: Int,
        currentClientIndex: Int
    ): RecoveryAction {
        // First 403: try re-resolving with same client (token may have just expired)
        if (state.consecutiveErrors == 1) {
            return RecoveryAction(
                strategy = RecoveryStrategy.REAUTH,
                resumePositionMs = state.lastPositionMs,
                resumeByteOffset = state.lastByteOffset,
                backoffMs = 0,
                reason = "First 403 for $currentClientName — re-resolving stream URL"
            )
        }

        // Second 403: try rotating to next client
        if (state.consecutiveErrors == 2 && currentClientIndex + 1 < availableClients) {
            return RecoveryAction(
                strategy = RecoveryStrategy.ROTATE_CLIENT,
                resumePositionMs = state.lastPositionMs,
                resumeByteOffset = state.lastByteOffset,
                backoffMs = 0,
                reason = "Persistent 403 on $currentClientName — rotating client"
            )
        }

        // Third 403: try switching transport
        if (state.consecutiveErrors == 3) {
            return RecoveryAction(
                strategy = RecoveryStrategy.ROTATE_TRANSPORT,
                resumePositionMs = state.lastPositionMs,
                resumeByteOffset = state.lastByteOffset,
                backoffMs = 0,
                reason = "403 persists across clients — switching transport from $currentTransport"
            )
        }

        // Beyond that: backoff
        return RecoveryAction(
            strategy = RecoveryStrategy.BACKOFF,
            resumePositionMs = state.lastPositionMs,
            resumeByteOffset = state.lastByteOffset,
            backoffMs = calculateBackoff(state.consecutiveErrors),
            reason = "Multiple 403s — backing off (attempt ${state.consecutiveErrors})"
        )
    }

    private fun handleExpiry(state: RecoveryState): RecoveryAction {
        return RecoveryAction(
            strategy = RecoveryStrategy.REAUTH,
            resumePositionMs = state.lastPositionMs,
            resumeByteOffset = state.lastByteOffset,
            backoffMs = 0,
            reason = "Stream URL expired — re-resolving"
        )
    }

    private fun handleNetworkChange(state: RecoveryState): RecoveryAction {
        // Network changed — re-resolve with same client (new network may have different IP)
        return RecoveryAction(
            strategy = RecoveryStrategy.REAUTH,
            resumePositionMs = state.lastPositionMs,
            resumeByteOffset = state.lastByteOffset,
            backoffMs = 500, // Brief delay for network to stabilize
            reason = "Network changed — re-resolving after stabilization"
        )
    }

    private fun handleTimeout(state: RecoveryState, currentTransport: TransportType): RecoveryAction {
        if (state.consecutiveErrors <= 2) {
            return RecoveryAction(
                strategy = RecoveryStrategy.REAUTH,
                resumePositionMs = state.lastPositionMs,
                resumeByteOffset = state.lastByteOffset,
                backoffMs = calculateBackoff(state.consecutiveErrors),
                reason = "Timeout on $currentTransport — retrying with backoff"
            )
        }

        return RecoveryAction(
            strategy = RecoveryStrategy.ROTATE_TRANSPORT,
            resumePositionMs = state.lastPositionMs,
            resumeByteOffset = state.lastByteOffset,
            backoffMs = 0,
            reason = "Persistent timeouts — switching transport"
        )
    }

    private fun handleCipherFailure(state: RecoveryState, availableClients: Int, currentClientIndex: Int): RecoveryAction {
        // Cipher failure: try a client that doesn't require cipher
        if (currentClientIndex + 1 < availableClients) {
            return RecoveryAction(
                strategy = RecoveryStrategy.ROTATE_CLIENT,
                resumePositionMs = state.lastPositionMs,
                resumeByteOffset = state.lastByteOffset,
                backoffMs = 0,
                reason = "Cipher failure — rotating to non-cipher client"
            )
        }

        return RecoveryAction(
            strategy = RecoveryStrategy.GIVE_UP,
            resumePositionMs = state.lastPositionMs,
            resumeByteOffset = state.lastByteOffset,
            backoffMs = 0,
            reason = "Cipher failure — no alternative clients available"
        )
    }

    private fun handleSabrError(state: RecoveryState): RecoveryAction {
        return RecoveryAction(
            strategy = RecoveryStrategy.ROTATE_TRANSPORT,
            resumePositionMs = state.lastPositionMs,
            resumeByteOffset = state.lastByteOffset,
            backoffMs = 0,
            reason = "SABR protocol error — falling back to Progressive"
        )
    }

    private fun handleUnknown(state: RecoveryState): RecoveryAction {
        return RecoveryAction(
            strategy = RecoveryStrategy.BACKOFF,
            resumePositionMs = state.lastPositionMs,
            resumeByteOffset = state.lastByteOffset,
            backoffMs = calculateBackoff(state.consecutiveErrors),
            reason = "Unknown error — retrying with backoff (attempt ${state.consecutiveErrors})"
        )
    }

    private fun calculateBackoff(attempt: Int): Long {
        // Exponential backoff: 1s, 2s, 4s, 8s, capped at backoffMaxMs
        val backoff = backoffBaseMs * (1L shl (attempt - 1).coerceIn(0, 5))
        return backoff.coerceAtMost(backoffMaxMs)
    }

    companion object {
        const val MAX_RECOVERY_ATTEMPTS = 5
        const val MAX_CONSECUTIVE_ERRORS = 5
        const val BACKOFF_BASE_MS = 1000L
        const val BACKOFF_MAX_MS = 16_000L
    }
}

// ── Data Classes ────────────────────────────────────────────────────────────

data class RecoveryAction(
    val strategy: RecoveryStrategy,
    val resumePositionMs: Long,
    val resumeByteOffset: Long,
    val backoffMs: Long,
    val reason: String
)

data class RecoveryCheckpoint(
    val positionMs: Long,
    val byteOffset: Long,
    val durationMs: Long
)

enum class RecoveryStrategy {
    REAUTH,
    ROTATE_CLIENT,
    ROTATE_TRANSPORT,
    BACKOFF,
    GIVE_UP
}

enum class PlaybackErrorType {
    HTTP_403,
    STREAM_EXPIRED,
    NETWORK_CHANGE,
    TIMEOUT,
    CIPHER_FAILURE,
    SABR_PROTOCOL_ERROR,
    UNKNOWN
}

private class RecoveryState(
    val mediaId: String,
    var lastPositionMs: Long = 0,
    var lastByteOffset: Long = 0,
    var durationMs: Long = 0,
    var lastTransportType: TransportType? = null,
    var lastClientName: String? = null,
    var consecutiveErrors: Int = 0,
    var totalErrors: Int = 0
)
