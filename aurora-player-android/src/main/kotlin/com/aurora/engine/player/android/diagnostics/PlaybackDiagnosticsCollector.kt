package com.aurora.engine.player.android.diagnostics

import com.aurora.engine.core.model.PlaybackError
import com.aurora.engine.core.model.PlaybackSource
import java.net.URI
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Diagnostic metrics snapshot representing the playback telemetry for a session or track.
 */
data class DiagnosticsSnapshot(
    val trackId: String?,
    val prepareLatencyMs: Long?,
    val startupLatencyMs: Long?,
    val totalBufferingDurationMs: Long,
    val bufferingCount: Int,
    val totalSeekLatencyMs: Long,
    val seekCount: Int,
    val recoveryAttemptCount: Int,
    val lastError: PlaybackError?,
    val events: List<DiagnosticEvent>
)

/**
 * Diagnostic event logged during playback.
 */
sealed interface DiagnosticEvent {
    val timestampMs: Long

    data class PrepareStarted(
        override val timestampMs: Long,
        val sanitizedSourceUri: String
    ) : DiagnosticEvent

    data class Ready(
        override val timestampMs: Long,
        val prepareDurationMs: Long
    ) : DiagnosticEvent

    data class FirstFrameRendered(
        override val timestampMs: Long,
        val startupDurationMs: Long
    ) : DiagnosticEvent

    data class BufferingStarted(
        override val timestampMs: Long,
        val positionMs: Long
    ) : DiagnosticEvent

    data class BufferingEnded(
        override val timestampMs: Long,
        val durationMs: Long
    ) : DiagnosticEvent

    data class SeekStarted(
        override val timestampMs: Long,
        val fromPositionMs: Long,
        val toPositionMs: Long
    ) : DiagnosticEvent

    data class SeekCompleted(
        override val timestampMs: Long,
        val durationMs: Long
    ) : DiagnosticEvent

    data class RecoveryAttempted(
        override val timestampMs: Long,
        val attemptNumber: Int,
        val errorCategory: String,
        val actionTaken: String
    ) : DiagnosticEvent

    data class ErrorOccurred(
        override val timestampMs: Long,
        val errorCode: String,
        val errorCategory: String,
        val isRecoverable: Boolean
    ) : DiagnosticEvent
}

/**
 * Thread-safe collector for playback telemetry and diagnostics.
 * Ensures zero leakage of auth tokens, cookies, or signed URLs.
 */
class PlaybackDiagnosticsCollector(
    private val clock: () -> Long = System::currentTimeMillis
) {
    private var currentTrackId: String? = null
    private var prepareStartTimeMs = AtomicLong(0L)
    private var prepareDurationMs = AtomicLong(-1L)
    private var startupDurationMs = AtomicLong(-1L)

    private var bufferingStartTimeMs = AtomicLong(0L)
    private var totalBufferingDurationMs = AtomicLong(0L)
    private var bufferingCount = AtomicInteger(0)

    private var seekStartTimeMs = AtomicLong(0L)
    private var totalSeekDurationMs = AtomicLong(0L)
    private var seekCount = AtomicInteger(0)

    private var recoveryCount = AtomicInteger(0)
    private var lastRecordedError: PlaybackError? = null

    private val eventLog = ConcurrentLinkedQueue<DiagnosticEvent>()

    fun onPrepareStarted(source: PlaybackSource) {
        val now = clock()
        currentTrackId = source.trackId
        prepareStartTimeMs.set(now)
        prepareDurationMs.set(-1L)
        startupDurationMs.set(-1L)

        val sanitizedUri = when (source) {
            is PlaybackSource.Progressive -> sanitizeUrl(source.url)
            is PlaybackSource.Hls -> sanitizeUrl(source.manifestUrl)
            is PlaybackSource.Sabr -> sanitizeUrl(source.serverEndpoint)
            is PlaybackSource.Local -> "[local_file]"
        }
        eventLog.add(DiagnosticEvent.PrepareStarted(now, sanitizedUri))
    }

    fun onPlayerReady() {
        val start = prepareStartTimeMs.get()
        if (start > 0 && prepareDurationMs.get() < 0) {
            val now = clock()
            val latency = now - start
            prepareDurationMs.set(latency)
            eventLog.add(DiagnosticEvent.Ready(now, latency))
        }
    }

    fun onPlaybackStarted() {
        val start = prepareStartTimeMs.get()
        if (start > 0 && startupDurationMs.get() < 0) {
            val now = clock()
            val latency = now - start
            startupDurationMs.set(latency)
            eventLog.add(DiagnosticEvent.FirstFrameRendered(now, latency))
        }
    }

    fun onFirstFrameRendered() {
        onPlaybackStarted()
    }

    fun onBufferingStarted(positionMs: Long) {
        val now = clock()
        bufferingStartTimeMs.set(now)
        bufferingCount.incrementAndGet()
        eventLog.add(DiagnosticEvent.BufferingStarted(now, positionMs))
    }

    fun onBufferingEnded() {
        val start = bufferingStartTimeMs.getAndSet(0L)
        if (start > 0) {
            val now = clock()
            val duration = (now - start).coerceAtLeast(0L)
            totalBufferingDurationMs.addAndGet(duration)
            eventLog.add(DiagnosticEvent.BufferingEnded(now, duration))
        }
    }

    fun onSeekStarted(fromPositionMs: Long, toPositionMs: Long) {
        val now = clock()
        seekStartTimeMs.set(now)
        seekCount.incrementAndGet()
        eventLog.add(DiagnosticEvent.SeekStarted(now, fromPositionMs, toPositionMs))
    }

    fun onSeekCompleted() {
        val start = seekStartTimeMs.getAndSet(0L)
        if (start > 0) {
            val now = clock()
            val duration = (now - start).coerceAtLeast(0L)
            totalSeekDurationMs.addAndGet(duration)
            eventLog.add(DiagnosticEvent.SeekCompleted(now, duration))
        }
    }

    fun onRecoveryAttempt(attemptNumber: Int, error: PlaybackError, action: String) {
        val now = clock()
        recoveryCount.incrementAndGet()
        eventLog.add(
            DiagnosticEvent.RecoveryAttempted(
                timestampMs = now,
                attemptNumber = attemptNumber,
                errorCategory = error.category.name,
                actionTaken = action
            )
        )
    }

    fun onError(error: PlaybackError) {
        val now = clock()
        lastRecordedError = error
        eventLog.add(
            DiagnosticEvent.ErrorOccurred(
                timestampMs = now,
                errorCode = error.code,
                errorCategory = error.category.name,
                isRecoverable = error.isRecoverable
            )
        )
    }

    fun getSnapshot(): DiagnosticsSnapshot {
        return DiagnosticsSnapshot(
            trackId = currentTrackId,
            prepareLatencyMs = prepareDurationMs.get().takeIf { it >= 0 },
            startupLatencyMs = startupDurationMs.get().takeIf { it >= 0 },
            totalBufferingDurationMs = totalBufferingDurationMs.get(),
            bufferingCount = bufferingCount.get(),
            totalSeekLatencyMs = totalSeekDurationMs.get(),
            seekCount = seekCount.get(),
            recoveryAttemptCount = recoveryCount.get(),
            lastError = lastRecordedError,
            events = eventLog.toList()
        )
    }

    fun reset() {
        currentTrackId = null
        prepareStartTimeMs.set(0L)
        prepareDurationMs.set(-1L)
        startupDurationMs.set(-1L)
        bufferingStartTimeMs.set(0L)
        totalBufferingDurationMs.set(0L)
        bufferingCount.set(0)
        seekStartTimeMs.set(0L)
        totalSeekDurationMs.set(0L)
        seekCount.set(0)
        recoveryCount.set(0)
        lastRecordedError = null
    }

    companion object {
        /**
         * Scrubs signatures, tokens, session IDs, and sensitive query parameters from URLs.
         * Returns only the scheme, host, and path with query parameters sanitized.
         */
        fun sanitizeUrl(url: String): String {
            return try {
                val uri = URI(url)
                val sanitizedQuery = if (uri.query != null) "?[redacted_query]" else ""
                "${uri.scheme}://${uri.host}${uri.path}$sanitizedQuery"
            } catch (_: Exception) {
                "[invalid_or_redacted_url]"
            }
        }

        /**
         * Scrubs sensitive headers such as Authorization, Cookie, Set-Cookie, and tokens.
         */
        fun sanitizeHeaders(headers: Map<String, String>): Map<String, String> {
            val sensitiveKeys = setOf("authorization", "cookie", "set-cookie", "token", "x-goog-visitor-id", "x-youtube-identity-token")
            return headers.mapValues { (key, value) ->
                if (sensitiveKeys.any { key.equals(it, ignoreCase = true) } ||
                    key.contains("token", ignoreCase = true) ||
                    key.contains("auth", ignoreCase = true) ||
                    key.contains("secret", ignoreCase = true) ||
                    key.contains("credential", ignoreCase = true)
                ) {
                    "[redacted]"
                } else {
                    value
                }
            }
        }

        /**
         * Scrubs personal identifiers (emails, auth tokens, signature values) from diagnostic messages.
         */
        fun sanitizeMessage(message: String): String {
            return message
                .replace(Regex("[a-zA-Z0-9_.+-]+@[a-zA-Z0-9-]+\\.[a-zA-Z0-9-.]+"), "[redacted_email]")
                .replace(Regex("(?i)\\b(Bearer|token|key|sig|signature)(\\s*[:=]\\s*|\\s+)[^\\s&,;]+"), "$1=[redacted]")
        }
    }
}
