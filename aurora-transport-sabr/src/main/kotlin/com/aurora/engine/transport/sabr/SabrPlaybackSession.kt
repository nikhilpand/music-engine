package com.aurora.engine.transport.sabr

import com.aurora.engine.core.model.PlaybackSource
import com.aurora.engine.core.transport.PlaybackSession
import com.aurora.engine.transport.sabr.buffer.SabrDataSource
import com.aurora.engine.transport.sabr.buffer.SabrMediaBuffer
import com.aurora.engine.transport.sabr.config.SabrTransportConfig
import com.aurora.engine.transport.sabr.model.SabrEvent
import com.aurora.engine.transport.sabr.model.SabrSessionState
import com.aurora.engine.transport.sabr.protocol.SabrMessageDecoder
import com.aurora.engine.transport.sabr.protocol.UmpFrameDecoder
import com.aurora.engine.transport.sabr.protocol.UmpPart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * SABR playback session.
 *
 * Manages the full lifecycle of a SABR streaming session:
 * 1. Initial connection (POST request with protobuf body)
 * 2. UMP frame decoding → message decoding → event dispatch
 * 3. Demand-driven continuation requests
 * 4. Seek handling with state reset
 * 5. Error recovery with retry logic
 *
 * ## Responsibilities
 *
 * - Owns the [UmpFrameDecoder], [SabrMessageDecoder], [SabrMediaBuffer]
 * - Manages the [SabrSessionState] state machine
 * - Exposes a [SabrDataSource] for ExoPlayer to read from
 * - Reports events via [SabrSessionListener]
 *
 * ## Security
 *
 * - Signed URLs, tokens, and cookies are NEVER logged
 * - Only format IDs, byte counts, and state transitions appear in logs
 */
class SabrPlaybackSession(
    override val source: PlaybackSource.Sabr,
    private val config: SabrTransportConfig = SabrTransportConfig(),
    private val listener: SabrSessionListener? = null,
    private val httpClient: SabrHttpClient? = null
) : PlaybackSession {

    override val sessionId: String = "sabr-${UUID.randomUUID().toString().take(8)}"

    private val _state = AtomicReference(SabrSessionState.CREATED)
    val state: SabrSessionState get() = _state.get()

    override val isPrepared: Boolean get() = state == SabrSessionState.STREAMING

    // Protocol components
    private val frameDecoder = UmpFrameDecoder(
        maxPartSize = config.maxUmpPartSize,
        maxReassemblySize = config.maxReassemblyBufferSize
    )
    private val messageDecoder = SabrMessageDecoder()

    // Buffer
    private val buffer = SabrMediaBuffer(config.memoryBufferCapacityBytes)
    val dataSource: SabrDataSource = SabrDataSource(buffer, config.bufferReadTimeoutMs)

    // Coroutine management
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var feedJob: Job? = null

    // Continuation state
    private val stateMutex = Mutex()
    private var playbackCookie: ByteArray? = null
    private var continuationRetryCount = 0

    // Format tracking
    private val receivedFormatInits = mutableSetOf<Int>()

    override suspend fun prepare() {
        stateMutex.withLock {
            if (state != SabrSessionState.CREATED) {
                throw IllegalStateException("Cannot prepare session in state $state")
            }
            transitionTo(SabrSessionState.CONNECTING)
        }

        try {
            // Start the streaming connection
            startStreaming()
        } catch (e: Exception) {
            stateMutex.withLock {
                transitionTo(SabrSessionState.CLOSED)
            }
            throw e
        }
    }

    override suspend fun release() {
        stateMutex.withLock {
            transitionTo(SabrSessionState.CLOSED)
        }
        feedJob?.cancel()
        buffer.close()
        scope.cancel()
        listener?.onSessionClosed(sessionId)
    }

    override fun close() {
        // Synchronous close for AutoCloseable
        transitionTo(SabrSessionState.CLOSED)
        feedJob?.cancel()
        buffer.close()
        scope.cancel()
        listener?.onSessionClosed(sessionId)
    }

    /**
     * Seek to a new position in the stream.
     *
     * Resets the UMP decoder, buffer, and sends a new initial request
     * at the specified byte offset.
     */
    suspend fun seekTo(positionMs: Long, estimatedByteOffset: Long) {
        stateMutex.withLock {
            transitionTo(SabrSessionState.SEEKING)
        }

        // Cancel in-flight streaming
        feedJob?.cancel()

        // Reset protocol state
        frameDecoder.reset()
        buffer.resetForSeek(estimatedByteOffset)
        continuationRetryCount = 0

        try {
            startStreaming(seekOffsetBytes = estimatedByteOffset)
        } catch (e: Exception) {
            stateMutex.withLock {
                transitionTo(SabrSessionState.RECOVERING)
            }
            attemptRecovery(e)
        }
    }

    // --- Internal streaming logic ---

    private fun startStreaming(seekOffsetBytes: Long? = null) {
        feedJob = scope.launch {
            try {
                val client = httpClient ?: throw IOException("No HTTP client configured")
                val responseStream = client.openSabrStream(
                    endpoint = source.serverEndpoint,
                    clientContext = source.clientContextJson,
                    seekOffsetBytes = seekOffsetBytes,
                    playbackCookie = playbackCookie,
                    timeoutMs = config.httpTimeoutMs
                )

                stateMutex.withLock {
                    transitionTo(SabrSessionState.STREAMING)
                }
                listener?.onSessionPrepared(sessionId)

                // Feed loop: read chunks from HTTP response and decode
                val readBuffer = ByteArray(8192)
                while (isActive && state == SabrSessionState.STREAMING) {
                    val bytesRead = responseStream.read(readBuffer)
                    if (bytesRead == -1) {
                        buffer.markComplete()
                        break
                    }

                    val chunk = readBuffer.copyOf(bytesRead)
                    val parts: List<UmpPart> = frameDecoder.feed(chunk)

                    for (part in parts) {
                        val event = messageDecoder.decode(part)
                        handleEvent(event)
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    stateMutex.withLock {
                        transitionTo(SabrSessionState.RECOVERING)
                    }
                    attemptRecovery(e)
                }
            }
        }
    }

    /**
     * Handle a decoded SABR event.
     */
    private suspend fun handleEvent(event: SabrEvent) {
        when (event) {
            is SabrEvent.MediaData -> {
                buffer.write(event.offsetBytes, event.data)
                listener?.onMediaDataReceived(sessionId, event.formatId, event.data.size)
            }

            is SabrEvent.FormatInit -> {
                receivedFormatInits.add(event.formatId)
                // FormatInit contains codec configuration; write it to the buffer start
                // The exact handling depends on whether ExoPlayer needs it as an init segment
                listener?.onFormatInitReceived(sessionId, event.formatId)
            }

            is SabrEvent.MediaEnd -> {
                listener?.onMediaEndReceived(sessionId, event.formatId)
                // Check if continuation is needed
                if (playbackCookie != null && state == SabrSessionState.STREAMING) {
                    requestContinuation()
                } else {
                    buffer.markComplete()
                }
            }

            is SabrEvent.NextRequestPolicy -> {
                playbackCookie = event.playbackCookie
                // Server may override our buffer thresholds
                listener?.onNextRequestPolicyReceived(
                    sessionId,
                    event.backoffMs,
                    event.targetBufferDurationMs
                )
            }

            is SabrEvent.ReloadRequired -> {
                stateMutex.withLock {
                    transitionTo(SabrSessionState.RELOADING)
                }
                listener?.onReloadRequired(sessionId, event.reason)
                // Reload is handled by the session lifecycle manager
            }

            is SabrEvent.ServerRedirect -> {
                listener?.onServerRedirect(sessionId, event.newEndpoint)
                // Redirect handling: update endpoint and reconnect
            }

            is SabrEvent.StreamError -> {
                listener?.onStreamError(sessionId, event.code, event.message)
                // HTTP 403 must pass through to PlaybackErrorMapper (per architecture constraint)
                if (event.code == 403) {
                    stateMutex.withLock {
                        transitionTo(SabrSessionState.CLOSED)
                    }
                    throw IOException("SABR stream error: HTTP ${event.code}")
                }
            }

            is SabrEvent.UnknownPart -> {
                // Logged and ignored — no action needed
            }
        }
    }

    /**
     * Send a continuation request to fetch more data.
     */
    private suspend fun requestContinuation() {
        stateMutex.withLock {
            transitionTo(SabrSessionState.CONTINUING)
        }

        try {
            val client = httpClient ?: throw IOException("No HTTP client configured")
            val responseStream = client.openSabrStream(
                endpoint = source.serverEndpoint,
                clientContext = source.clientContextJson,
                seekOffsetBytes = null,
                playbackCookie = playbackCookie,
                timeoutMs = config.httpTimeoutMs
            )

            stateMutex.withLock {
                transitionTo(SabrSessionState.STREAMING)
            }

            continuationRetryCount = 0

            val readBuffer = ByteArray(8192)
            while (scope.isActive && state == SabrSessionState.STREAMING) {
                val bytesRead = responseStream.read(readBuffer)
                if (bytesRead == -1) {
                    buffer.markComplete()
                    break
                }

                val chunk = readBuffer.copyOf(bytesRead)
                val parts = frameDecoder.feed(chunk)
                for (part in parts) {
                    handleEvent(messageDecoder.decode(part))
                }
            }
        } catch (e: Exception) {
            continuationRetryCount++
            if (continuationRetryCount < config.maxContinuationRetries) {
                delay(1000L * continuationRetryCount) // Linear backoff
                requestContinuation()
            } else {
                stateMutex.withLock {
                    transitionTo(SabrSessionState.RECOVERING)
                }
                attemptRecovery(e)
            }
        }
    }

    /**
     * Attempt automatic recovery from a transient failure.
     */
    private suspend fun attemptRecovery(cause: Exception) {
        listener?.onRecoveryAttempt(sessionId, cause.message ?: "unknown")

        try {
            frameDecoder.reset()
            continuationRetryCount = 0
            startStreaming()
        } catch (e: Exception) {
            stateMutex.withLock {
                transitionTo(SabrSessionState.CLOSED)
            }
            listener?.onSessionFailed(sessionId, e)
        }
    }

    private fun transitionTo(newState: SabrSessionState) {
        val old = _state.getAndSet(newState)
        if (old != newState) {
            listener?.onStateChanged(sessionId, old, newState)
        }
    }
}

/**
 * Listener for SABR session events. All methods have no-op defaults.
 *
 * Implementations must be thread-safe — callbacks may arrive from any thread.
 */
interface SabrSessionListener {
    fun onStateChanged(sessionId: String, oldState: SabrSessionState, newState: SabrSessionState) {}
    fun onSessionPrepared(sessionId: String) {}
    fun onSessionClosed(sessionId: String) {}
    fun onSessionFailed(sessionId: String, cause: Exception) {}
    fun onMediaDataReceived(sessionId: String, formatId: Int, byteCount: Int) {}
    fun onFormatInitReceived(sessionId: String, formatId: Int) {}
    fun onMediaEndReceived(sessionId: String, formatId: Int) {}
    fun onNextRequestPolicyReceived(sessionId: String, backoffMs: Long?, targetBufferMs: Long?) {}
    fun onReloadRequired(sessionId: String, reason: String) {}
    fun onServerRedirect(sessionId: String, newEndpoint: String) {}
    fun onStreamError(sessionId: String, code: Int, message: String) {}
    fun onRecoveryAttempt(sessionId: String, reason: String) {}
}

/**
 * Abstraction for the HTTP client used by SABR sessions.
 *
 * This interface allows testing without real network calls and decouples
 * the SABR transport from any specific HTTP library.
 *
 * **Security**: Implementations must NEVER log signed URLs, tokens, or cookies.
 */
interface SabrHttpClient {
    /**
     * Open a SABR streaming connection.
     *
     * @param endpoint Server endpoint URL.
     * @param clientContext Client context JSON (contains session parameters).
     * @param seekOffsetBytes Optional byte offset for seek requests.
     * @param playbackCookie Playback cookie from previous NextRequestPolicy (for continuation).
     * @param timeoutMs HTTP request timeout.
     * @return An [java.io.InputStream] for reading the UMP response body.
     */
    suspend fun openSabrStream(
        endpoint: String,
        clientContext: String,
        seekOffsetBytes: Long?,
        playbackCookie: ByteArray?,
        timeoutMs: Long
    ): java.io.InputStream
}
