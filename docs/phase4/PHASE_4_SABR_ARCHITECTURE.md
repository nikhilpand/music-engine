# Phase 4 — SABR Transport Architecture (Corrected)

**Date**: 2026-09-15
**Revision**: 2 (Corrected per PHASE_4_ARCHITECTURE_REVIEW.md)
**Module**: `aurora-transport-sabr`
**Depends On**: `aurora-core` (interfaces and models only)
**Depended On By**: `aurora-player-android` (runtime registration and DataSource bridging)
**Baseline**: 135/135 tests passing (user-declared)

---

## 1. Overview

SABR (Server-Adaptive Bitrate) is YouTube's Protobuf-over-HTTP/2 streaming
protocol. Unlike progressive download (single HTTPS URL → byte-range requests),
SABR uses a demand-driven session where:

1. The client sends a `VideoPlaybackAbrRequest` protobuf to a server endpoint.
2. The server responds with a UMP (Universal Media Protocol) binary stream
   containing interleaved media data, metadata, and control messages.
3. The client monitors its buffer and sends **demand-driven continuation
   requests** when the buffer level falls below a threshold, reporting
   `buffered_ranges` and `playback_position_ms` to the server.
4. The server may return `NextRequestPolicy`, `playback_cookie`, reload
   directives, or CDN redirects within the UMP response.
5. Seeking constructs a new request with updated position and buffered ranges.

---

## 2. Architectural Constraints

### 2.1 Isolation Rules

| Rule | Description |
|------|-------------|
| I-1 | `aurora-core` shall have **zero** imports from `aurora-transport-sabr` |
| I-2 | `aurora-transport-progressive` shall have **zero** imports from `aurora-transport-sabr` |
| I-3 | `aurora-provider-ytmusic` shall have **zero** imports from `aurora-transport-sabr` |
| I-4 | `aurora-transport-sabr` depends **only** on `aurora-core` for `PlaybackTransport`, `PlaybackSession`, `PlaybackSource.Sabr` |
| I-5 | All protobuf/UMP parsing code is contained within this module |
| I-6 | `PlaybackSource.Sabr` is NOT modified — provider-specific fields are extracted internally via `SabrInitParams` |

### 2.2 Security Rules

The following must **never** be logged, serialized to disk, or included in diagnostic events:

| Data | Redaction |
|------|-----------|
| Signed URLs / server endpoints | `[SABR_ENDPOINT]` |
| `clientContextJson` | `[CLIENT_CONTEXT]` |
| PoTokens / `visitorData` | `[AUTH_TOKEN]` |
| `playback_cookie` | `[PLAYBACK_COOKIE]` |
| Response `Set-Cookie` headers | `[COOKIES]` |
| Request `Authorization` headers | `[AUTH_HEADER]` |

### 2.3 What This Phase Does NOT Implement

- React Native bridge
- Downloads or offline caching
- Persistent media library
- Recommendation engine / Route Intelligence
- Collaborative intelligence / UI components
- Provider expansion unrelated to SABR

---

## 3. Module Structure

```text
aurora-transport-sabr/
├── build.gradle.kts
├── src/
│   ├── main/kotlin/com/aurora/engine/transport/sabr/
│   │   ├── SabrPlaybackTransport.kt          # PlaybackTransport implementation
│   │   ├── SabrPlaybackSession.kt            # PlaybackSession implementation
│   │   ├── model/
│   │   │   ├── SabrInitParams.kt             # Internal model parsed from clientContextJson
│   │   │   ├── SabrEvent.kt                  # Sealed interface for semantic events
│   │   │   └── SabrSessionState.kt           # Session state enum
│   │   ├── protocol/
│   │   │   ├── UmpFrameDecoder.kt            # STATEFUL UMP framing: raw bytes → UmpPart
│   │   │   ├── UmpMessageType.kt             # UMP message type constants
│   │   │   ├── SabrMessageDecoder.kt         # UmpPart → SabrEvent (STATELESS per message)
│   │   │   └── SabrRequestBuilder.kt         # VideoPlaybackAbrRequest construction
│   │   ├── session/
│   │   │   ├── SabrSessionController.kt      # Session state machine + lifecycle
│   │   │   └── ContinuationController.kt     # Demand-driven continuation triggering
│   │   ├── buffer/
│   │   │   └── SabrMediaBuffer.kt            # Coverage-map based byte buffer
│   │   ├── datasource/
│   │   │   └── SabrDataSource.kt             # Media3 DataSource adapter (bounded blocking)
│   │   ├── error/
│   │   │   └── SabrErrorClassifier.kt        # SABR-specific error classification
│   │   └── config/
│   │       └── SabrTransportConfig.kt        # Tunable configuration
│   │
│   └── test/kotlin/com/aurora/engine/transport/sabr/
│       ├── model/
│       │   └── SabrInitParamsTest.kt
│       ├── protocol/
│       │   ├── UmpFrameDecoderTest.kt
│       │   ├── SabrMessageDecoderTest.kt
│       │   └── SabrRequestBuilderTest.kt
│       ├── session/
│       │   ├── SabrSessionControllerTest.kt
│       │   └── ContinuationControllerTest.kt
│       ├── buffer/
│       │   └── SabrMediaBufferTest.kt
│       ├── datasource/
│       │   └── SabrDataSourceTest.kt
│       ├── error/
│       │   └── SabrErrorClassifierTest.kt
│       ├── SabrPlaybackTransportTest.kt
│       └── SabrPlaybackSessionTest.kt
```

### 3.1 Structural Changes from Revision 1

| Removed | Replaced By | Reason |
|---------|-------------|--------|
| `UmpParser.kt` | `UmpFrameDecoder.kt` | Must be stateful for partial parts |
| `SabrResponseParser.kt` | `SabrMessageDecoder.kt` | Produces semantic `SabrEvent` sealed class |
| `ContinuationScheduler.kt` | `ContinuationController.kt` | Demand-driven, not timer-based |
| `SabrSessionManager.kt` | `SabrSessionController.kt` | Expanded state machine |
| `MemoryRingBuffer.kt` | `SabrMediaBuffer.kt` | Coverage-map, not ring buffer |
| `DiskBackedRingBuffer.kt` | (deferred to Phase 5) | Audio-only ≤ 8MB; disk backing unnecessary |
| `SabrSeekResolver.kt` | (merged into `SabrSessionController`) | Seek is a state transition, not standalone |
| (none) | `SabrInitParams.kt` | Internal params extracted from `clientContextJson` |
| (none) | `SabrEvent.kt` | Semantic event model for protocol messages |
| (none) | `SabrSessionState.kt` | Expanded state enum with SEEKING, RELOADING |

---

## 4. Component Design

### 4.1 SabrPlaybackTransport

The entry point. Implements `PlaybackTransport` from `aurora-core`.

```kotlin
class SabrPlaybackTransport(
    private val httpClient: OkHttpClient,
    private val config: SabrTransportConfig = SabrTransportConfig()
) : PlaybackTransport {

    override val transportId: String = "sabr"

    override fun canHandle(source: PlaybackSource): Boolean =
        source is PlaybackSource.Sabr

    override suspend fun createSession(source: PlaybackSource): PlaybackSession {
        require(source is PlaybackSource.Sabr)
        val initParams = source.extractInitParams()
        // Create session controller, buffer, continuation controller, return session
    }
}
```

**Key Design Decision**: `canHandle()` is a simple type check. No network calls.
Transport selection remains fast and deterministic.

### 4.2 SabrPlaybackSession

Implements `PlaybackSession`. Owns the lifecycle of a single SABR streaming session.

```kotlin
class SabrPlaybackSession(
    override val sessionId: String,
    override val source: PlaybackSource.Sabr,
    private val sessionController: SabrSessionController,
    private val buffer: SabrMediaBuffer,
    private val continuationController: ContinuationController,
    private val coroutineScope: CoroutineScope
) : PlaybackSession {

    override val isPrepared: Boolean
        get() = sessionController.state == SabrSessionState.STREAMING

    override suspend fun prepare() {
        // 1. Build initial VideoPlaybackAbrRequest from SabrInitParams
        // 2. Send to serverEndpoint via HTTP/2 POST
        // 3. Feed UMP response bytes to UmpFrameDecoder
        // 4. Process SabrEvents from SabrMessageDecoder
        // 5. Start ContinuationController monitoring
        // 6. Begin writing media data to buffer
    }

    override suspend fun release() {
        continuationController.stop()
        buffer.close()
        sessionController.close()
        coroutineScope.cancel()
    }
}
```

### 4.3 SabrInitParams (Internal Model)

Extracted from `PlaybackSource.Sabr.clientContextJson` within the transport.
Keeps YouTube-specific fields out of `aurora-core`.

```kotlin
// Internal to aurora-transport-sabr — NOT in aurora-core
internal data class SabrInitParams(
    val videoId: String,
    val poToken: String?,
    val visitorData: String?,
    val formatIds: List<Int>,
    val clientContext: Map<String, Any>
)

internal fun PlaybackSource.Sabr.extractInitParams(): SabrInitParams {
    // Parse clientContextJson into SabrInitParams
    // Validation: videoId required, formatIds non-empty
    // Failure: throw IllegalArgumentException (caught by session)
}
```

### 4.4 SabrEvent (Semantic Event Model)

All UMP message types are decoded into this sealed interface. This provides
the protocol layer's output contract.

```kotlin
sealed interface SabrEvent {
    /** Raw media bytes at a specific byte offset for a given format/itag. */
    data class MediaData(
        val formatId: Int,
        val data: ByteArray,
        val offsetBytes: Long
    ) : SabrEvent

    /** Initialization segment (codec config) for a given format. */
    data class FormatInit(
        val formatId: Int,
        val initSegment: ByteArray
    ) : SabrEvent

    /** End-of-media marker for a format (may be final or segment boundary). */
    data class MediaEnd(val formatId: Int) : SabrEvent

    /** Server's policy for when to send the next request + session cookie. */
    data class NextRequestPolicy(
        val playbackCookie: ByteArray?,
        val backoffMs: Long?,
        val targetBufferDurationMs: Long?
    ) : SabrEvent

    /** Server demands session re-initialization. */
    data class ReloadRequired(val reason: String) : SabrEvent

    /** Server redirects to a different CDN endpoint. */
    data class ServerRedirect(val newEndpoint: String) : SabrEvent

    /** Server-initiated error within the UMP stream. */
    data class StreamError(val code: Int, val message: String) : SabrEvent

    /** Unknown/unrecognized UMP part (logged at DEBUG, otherwise ignored). */
    data class UnknownPart(val typeId: Int, val payloadSize: Int) : SabrEvent
}
```

### 4.5 UmpFrameDecoder (STATEFUL)

Parses raw bytes from HTTP response bodies into `UmpPart` records. This
component is **stateful** because UMP parts can span multiple HTTP responses.

```kotlin
data class UmpPart(val typeId: Int, val payload: ByteArray)

class UmpFrameDecoder {
    // Reassembly state for partial parts
    private var pendingType: Int? = null
    private var pendingSize: Int = 0
    private var pendingBuffer: ByteArrayOutputStream? = null
    private var pendingBytesRemaining: Int = 0

    /**
     * Feed raw bytes from an HTTP response body.
     * Returns a list of fully reassembled UmpParts.
     * May return 0, 1, or many parts per feed call.
     */
    fun feed(data: ByteArray): List<UmpPart> { /* ... */ }

    /**
     * Reset state (e.g., on session invalidation or seek).
     */
    fun reset() { /* ... */ }

    companion object {
        /**
         * Decode a UMP varint from the given byte array starting at offset.
         * Returns Pair(value, bytesConsumed) or null if insufficient bytes.
         *
         * UMP varints use 1-5 bytes:
         * - 1 byte: prefix 0xxxxxxx → 7-bit value
         * - 2 bytes: prefix 10xxxxxx → 14-bit value
         * - 3 bytes: prefix 110xxxxx → 21-bit value
         * - 4 bytes: prefix 1110xxxx → 28-bit value
         * - 5 bytes: prefix 11110xxx → raw LE u32 from next 4 bytes
         */
        fun decodeVarint(data: ByteArray, offset: Int): Pair<Int, Int>? { /* ... */ }
    }
}
```

**Critical**: The `decodeVarint` implementation must handle the 5-byte case
correctly — the lower 3 bits of the prefix byte are ignored, and the next 4
bytes are read as a raw little-endian 32-bit integer.

### 4.6 SabrMessageDecoder (STATELESS per message)

Converts `UmpPart` into `SabrEvent`. Pure function with no state.

```kotlin
class SabrMessageDecoder {

    fun decode(part: UmpPart): SabrEvent = when (part.typeId) {
        UmpMessageType.ONESIE_HEADER -> decodeOnesieHeader(part.payload)
        UmpMessageType.MEDIA_HEADER  -> decodeMediaHeader(part.payload)
        UmpMessageType.MEDIA_DATA    -> decodeMediaData(part.payload)
        UmpMessageType.MEDIA_END     -> decodeMediaEnd(part.payload)
        UmpMessageType.FORMAT_INIT   -> decodeFormatInit(part.payload)
        UmpMessageType.NEXT_REQUEST  -> decodeNextRequestPolicy(part.payload)
        UmpMessageType.RELOAD        -> decodeReloadRequired(part.payload)
        UmpMessageType.REDIRECT      -> decodeServerRedirect(part.payload)
        UmpMessageType.STREAM_ERROR  -> decodeStreamError(part.payload)
        else -> SabrEvent.UnknownPart(part.typeId, part.payload.size)
    }
}
```

### 4.7 SabrRequestBuilder

Constructs the protobuf request payloads.

```kotlin
class SabrRequestBuilder {

    fun buildInitialRequest(
        initParams: SabrInitParams,
        source: PlaybackSource.Sabr,
        config: SabrTransportConfig
    ): ByteArray {
        // Serialize VideoPlaybackAbrRequest protobuf:
        // - videoId, formatIds, poToken, visitorData
        // - ustreamerConfig from source
        // - initial buffer status (empty)
    }

    fun buildContinuationRequest(
        sessionState: SabrSessionState,
        bufferedRanges: List<LongRange>,
        playbackPositionMs: Long,
        playbackCookie: ByteArray?
    ): ByteArray {
        // Serialize continuation with:
        // - buffered_ranges as byte offset ranges
        // - playback_position_ms
        // - playback_cookie (echoed from server)
    }

    fun buildSeekRequest(
        seekPositionMs: Long,
        bufferedRanges: List<LongRange>,
        playbackCookie: ByteArray?
    ): ByteArray {
        // Serialize seek request with updated position
    }
}
```

### 4.8 SabrSessionController (State Machine)

Manages the session lifecycle with the corrected state machine.

```text
            ┌──────────┐
            │  CREATED  │
            └─────┬─────┘
                  │ prepare()
                  ▼
            ┌───────────┐
            │ CONNECTING │
            └─────┬──────┘
                  │ UMP header received
                  ▼
   ┌────────►┌──────────┐◄─────────┐
   │         │ STREAMING │         │
   │         └──┬──┬──┬──┘         │
   │            │  │  │            │
   │   buffer   │  │  │ server     │
   │   low      │  │  │ reload     │
   │   ┌────────┘  │  └────────┐   │
   │   ▼           │           ▼   │
   │ ┌───────────┐ │  ┌──────────┐ │
   │ │CONTINUING │ │  │RELOADING │ │
   │ └─────┬─────┘ │  └────┬─────┘ │
   │       │ data   │       │ new   │
   │       │ recv   │       │ session│
   └───────┘       │       └───────┘
                   │ seekTo()
                   ▼
            ┌──────────┐
            │ SEEKING   │──── data recv ───►STREAMING
            └──────────┘
                   │ failure (any state)
                   ▼
            ┌───────────┐
            │ RECOVERING │──── recovered ──►STREAMING
            └─────┬──────┘
                  │ terminal failure / release()
                  ▼
            ┌──────────┐
            │  CLOSED   │
            └──────────┘
```

```kotlin
enum class SabrSessionState {
    CREATED,
    CONNECTING,
    STREAMING,
    CONTINUING,
    SEEKING,
    RELOADING,
    RECOVERING,
    CLOSED
}
```

### 4.9 ContinuationController (Demand-Driven)

Replaces the timer-based `ContinuationScheduler`. Triggers continuation requests
based on buffer level and server policy.

```kotlin
class ContinuationController(
    private val buffer: SabrMediaBuffer,
    private val requestBuilder: SabrRequestBuilder,
    private val config: SabrTransportConfig,
    private val coroutineScope: CoroutineScope
) {
    private var playbackCookie: ByteArray? = null
    private var serverTargetBufferMs: Long? = null
    private var serverBackoffMs: Long? = null

    /**
     * Called when a NextRequestPolicy event is received from the server.
     * Updates the continuation trigger thresholds.
     */
    fun onServerPolicy(policy: SabrEvent.NextRequestPolicy) {
        playbackCookie = policy.playbackCookie
        serverBackoffMs = policy.backoffMs
        serverTargetBufferMs = policy.targetBufferDurationMs
    }

    /**
     * Starts monitoring the buffer. Fires a continuation request when:
     * 1. Unbuffered time ahead of playback position < threshold, OR
     * 2. Server requested a continuation via NextRequestPolicy, OR
     * 3. Maximum silence timeout exceeded (safety net)
     */
    fun startMonitoring(
        playbackPositionProvider: () -> Long,
        onContinuationNeeded: suspend (ByteArray) -> Unit
    ) { /* ... */ }

    fun stop() { /* ... */ }
}
```

### 4.10 SabrMediaBuffer (Coverage-Map Based)

Replaces the ring buffer design. Tracks byte coverage as sorted, non-overlapping
ranges, supporting random-access write and read.

```kotlin
class SabrMediaBuffer(
    private val maxCapacityBytes: Long = 8 * 1024 * 1024  // 8MB
) : Closeable {
    // Coverage tracking
    private val coveredRanges: MutableList<LongRange> = mutableListOf()
    private val data: ByteArrayOutputStream = ByteArrayOutputStream()

    // Read blocking
    private val readCondition = Object()  // for wait/notify
    private val readTimeoutMs: Long = 5_000L

    /**
     * Write media data at a specific byte offset.
     * Updates coverage map. Notifies blocked readers.
     */
    fun write(offsetBytes: Long, data: ByteArray) { /* ... */ }

    /**
     * Read data from the buffer at the given offset.
     * If the requested range is covered, returns immediately.
     * If NOT covered, blocks up to readTimeoutMs.
     * Throws IOException on timeout (triggers ExoPlayer error handling).
     */
    @Throws(IOException::class)
    fun read(offsetBytes: Long, target: ByteArray, targetOffset: Int, length: Int): Int { /* ... */ }

    /**
     * Returns the current byte-offset coverage map.
     * Used by ContinuationController to build continuation requests.
     */
    fun getBufferedRanges(): List<LongRange> =
        synchronized(coveredRanges) { coveredRanges.toList() }

    /**
     * Check if a byte offset range is fully covered (for seek-in-buffer).
     */
    fun isCovered(startOffset: Long, endOffset: Long): Boolean { /* ... */ }

    /**
     * Evict the oldest covered segments when capacity is exceeded.
     */
    private fun evictOldestSegments(requiredBytes: Long) { /* ... */ }

    override fun close() { /* zero-fill and release */ }
}
```

### 4.11 SabrDataSource (Bounded Blocking)

Bridges the SABR buffer to Media3's `DataSource` interface with bounded
blocking to avoid stalling ExoPlayer's loading thread indefinitely.

```kotlin
class SabrDataSource(
    private val buffer: SabrMediaBuffer
) : DataSource {

    private var readPosition: Long = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        readPosition = dataSpec.position
        opened = true
        return C.LENGTH_UNSET.toLong()  // SABR streams have unknown total length
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (!opened) throw IllegalStateException("DataSource not opened")
        // Delegates to SabrMediaBuffer.read() which handles:
        // - Immediate return if data available
        // - Bounded blocking (5s timeout) if data not yet available
        // - IOException on timeout → ExoPlayer LoadErrorHandlingPolicy
        val bytesRead = buffer.read(readPosition, target, offset, length)
        if (bytesRead > 0) readPosition += bytesRead
        return bytesRead
    }

    override fun close() {
        opened = false
    }

    override fun getUri(): Uri? = Uri.parse("[SABR_SESSION]")  // Never expose endpoint

    class Factory(
        private val transport: SabrPlaybackTransport
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource { /* ... */ }
    }
}
```

### 4.12 SabrErrorClassifier

Maps SABR-specific failures to Aurora's `ErrorCategory` taxonomy.

**Critical change from Revision 1**: HTTP 403 is NOT pre-classified as
`SOURCE_EXPIRED`. It is wrapped with HTTP response context and passed through
to the existing `PlaybackErrorMapper.classifyHttp403()` in `aurora-player-android`.

```kotlin
sealed class SabrTransportError(
    val category: ErrorCategory,
    val isRecoverable: Boolean,
    val httpContext: HttpErrorContext? = null
) {
    class UmpParseError(cause: Exception) :
        SabrTransportError(ErrorCategory.MALFORMED_MEDIA, isRecoverable = false)

    class ProtobufError(cause: Exception) :
        SabrTransportError(ErrorCategory.MALFORMED_MEDIA, isRecoverable = false)

    class HttpError(val context: HttpErrorContext) :
        SabrTransportError(
            category = when (context.statusCode) {
                429 -> ErrorCategory.RATE_LIMITED
                in 500..599 -> ErrorCategory.HTTP_SERVER
                // 403: NOT pre-classified. Passed through to player error mapper.
                403 -> ErrorCategory.UNKNOWN_FORBIDDEN  // Signals "needs further classification"
                else -> ErrorCategory.UNKNOWN
            },
            isRecoverable = context.statusCode != 404,
            httpContext = context
        )

    class NetworkError(cause: Exception) :
        SabrTransportError(
            category = when (cause) {
                is SocketTimeoutException -> ErrorCategory.TIMEOUT
                is UnknownHostException -> ErrorCategory.NETWORK_FAILURE
                else -> ErrorCategory.IO
            },
            isRecoverable = true
        )

    class SessionInvalidated(reason: String) :
        SabrTransportError(ErrorCategory.SOURCE_EXPIRED, isRecoverable = true)
}

data class HttpErrorContext(
    val statusCode: Int,
    val responseHeaders: Map<String, List<String>>,
    val responseBodySnippet: String?  // max 500 chars, redacted
)
```

---

## 5. Data Processing Pipeline

Six-layer pipeline with clear separation of concerns:

```text
Layer 1 — NETWORK
  HTTP/2 POST → raw response bytes
        │
Layer 2 — FRAMING (UmpFrameDecoder) ← STATEFUL
  raw bytes → UmpPart { typeId: Int, payload: ByteArray }
  Handles partial parts spanning HTTP responses.
  Maintains reassembly buffer.
        │
Layer 3 — PROTOCOL (SabrMessageDecoder) ← STATELESS per message
  UmpPart → SabrEvent (sealed interface)
  Deserializes protobuf payloads within UMP parts.
  Maps UMP type IDs to semantic events.
        │
Layer 4 — SESSION (SabrSessionController) ← STATEFUL
  SabrEvent → state transitions + buffer writes
  Handles NextRequestPolicy, ReloadRequired, ServerRedirect.
  Owns the ContinuationController.
  Drives the session state machine.
        │
Layer 5 — BUFFER (SabrMediaBuffer) ← STATEFUL
  Coverage-map based byte storage.
  Reports buffered ranges for continuation requests.
  Provides bounded-blocking read for DataSource.
        │
Layer 6 — PLAYER BRIDGE (SabrDataSource)
  SabrMediaBuffer → Media3 DataSource interface
  Bounded blocking read (5s timeout).
  Interruptible for clean shutdown.
```

---

## 6. Seek Architecture

### 6.1 Seek Flow

```text
ExoPlayer.seekTo(positionMs)
    │
    ▼
SabrDataSource notices read position changed (via open(dataSpec))
    │
    ▼
SabrSessionController.seekTo(positionMs)
    │
    ├── Check if target position is within buffered ranges
    │     ├── YES → Update read cursor, no network request
    │     │         (seek-in-buffer optimization)
    │     └── NO → Continue below
    │
    ├── Transition state to SEEKING
    ├── Abort any in-flight continuation request
    ├── Reset UmpFrameDecoder (clear partial part state)
    ├── Build seek VideoPlaybackAbrRequest with:
    │     - playback_position_ms = target
    │     - buffered_ranges = current coverage map
    │     - playback_cookie (if available)
    ├── POST to server endpoint
    ├── Parse UMP response (may include new init segment)
    ├── Write media data to buffer at new offset
    └── Transition state back to STREAMING
```

### 6.2 Seek-in-Buffer Optimization

If `buffer.isCovered(seekTargetByteOffset, seekTargetByteOffset + minRead)`,
no network request is needed. The DataSource simply repositions its read cursor.

---

## 7. Session Ownership and Lifecycle

### 7.1 Ownership Chain

```text
AuroraAndroidPlayerController
  └── PlaybackTransportRegistry.findTransportFor(source)
        └── SabrPlaybackTransport.createSession(source)
              └── SabrPlaybackSession (owns everything below)
                    ├── SabrSessionController (owns session state machine)
                    │     ├── UmpFrameDecoder (framing state)
                    │     ├── SabrMessageDecoder (stateless per message)
                    │     └── ContinuationController (demand-driven)
                    ├── SabrMediaBuffer (coverage-map buffer)
                    └── SabrDataSource (player bridge)
```

### 7.2 Lifecycle Contract

1. `createSession()` — Creates session, does NOT connect.
2. `prepare()` — Sends initial request, establishes UMP stream.
3. Active — Data flows through pipeline to DataSource.
4. `release()` — Cancels all coroutines, closes buffer, closes HTTP connection.
5. Single-use: after `release()`, a new session must be created.

### 7.3 State Mapping

| Aurora `EngineState` | SABR `SabrSessionState` | Relationship |
|---------------------|------------------------|--------------|
| `RESOLVING` | (no session yet) | Provider resolving |
| `PREPARING` | `CREATED → CONNECTING` | Session handshake |
| `PLAYING` | `STREAMING` | Normal playback |
| `BUFFERING` | `STREAMING` or `CONTINUING` | SABR fetching more data |
| `SEEKING` | `SEEKING` | Seek in progress |
| `ERROR` | `RECOVERING` or `CLOSED` | Recovery or terminal |
| `IDLE` | `CLOSED` | Session released |

---

## 8. Error Recovery Integration

SABR errors integrate with `PlaybackRecoveryCoordinator` in `aurora-player-android`:

| SABR Error | ErrorCategory | Recovery Action |
|------------|---------------|-----------------|
| UMP parse failure | `MALFORMED_MEDIA` | Retry with new session |
| HTTP 403 | `UNKNOWN_FORBIDDEN` → player mapper sub-classifies | Depends on sub-classification |
| HTTP 429 rate limit | `RATE_LIMITED` | Exponential backoff |
| Continuation timeout | `TIMEOUT` | Retry continuation |
| Network change | `NETWORK_FAILURE` | Re-establish session at last position |
| Protobuf error | `MALFORMED_MEDIA` | Retry with new session |
| Server reload directive | `SOURCE_EXPIRED` | Re-resolve source |
| Session invalidation | `SOURCE_EXPIRED` | Re-resolve source |

**Critical**: HTTP 403 is classified as `UNKNOWN_FORBIDDEN` at the transport
level. The `PlaybackErrorMapper` in `aurora-player-android` performs the
sub-classification into `SOURCE_EXPIRED`, `AUTHENTICATION_REQUIRED`,
`BOT_DETECTION`, or `PROVIDER_REJECTION` based on response body analysis.

---

## 9. Configuration

```kotlin
data class SabrTransportConfig(
    // Continuation (demand-driven)
    val bufferLowThresholdMs: Long = 5_000L,       // Trigger continuation when < 5s buffered ahead
    val maxSilenceTimeoutMs: Long = 30_000L,       // Safety net: continuation if no server data for 30s
    val sessionTimeoutMs: Long = 30_000L,           // Overall session timeout

    // Recovery
    val maxContinuationRetries: Int = 3,

    // Buffer
    val memoryBufferCapacityBytes: Long = 8 * 1024 * 1024,  // 8MB (~30s of 256kbps)
    val bufferReadTimeoutMs: Long = 5_000L,         // DataSource blocking timeout

    // Network
    val httpTimeoutMs: Long = 15_000L,

    // Seek
    val seekBufferTargetMs: Long = 3_000L,          // Min buffer after seek before playback resumes

    // UMP
    val maxUmpPartSize: Int = 512 * 1024,           // 512KB max single UMP part
    val maxReassemblyBufferSize: Int = 256 * 1024   // 256KB reassembly buffer
)
```

---

## 10. Dependencies

### 10.1 build.gradle.kts

```kotlin
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.aurora.engine.transport.sabr"
    compileSdk = 35
    defaultConfig {
        minSdk = 24
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Core interfaces only
    implementation(project(":aurora-core"))

    // HTTP/2 client for SABR streaming
    implementation(libs.okhttp)

    // Protobuf deserialization for UMP messages
    implementation(libs.protobuf.javalite)

    // Media3 DataSource interface (for SabrDataSource)
    implementation(libs.media3.datasource)

    // Coroutines for session management
    implementation(libs.kotlinx.coroutines.core)

    // JSON parsing for clientContextJson → SabrInitParams
    implementation(libs.kotlinx.serialization.json)

    // Testing
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testImplementation(libs.junit.jupiter.params)
    testImplementation(libs.truth)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
```

### 10.2 Dependency Graph

```text
aurora-transport-sabr
    │
    ├── aurora-core (interfaces: PlaybackTransport, PlaybackSession, PlaybackSource.Sabr)
    │
    ├── okhttp (HTTP/2 POST for SABR requests)
    ├── protobuf-javalite (UMP message deserialization)
    ├── media3-datasource (DataSource interface for SabrDataSource)
    ├── kotlinx-coroutines-core (async session management)
    └── kotlinx-serialization-json (clientContextJson parsing)
```

---

## 11. Testing Strategy

### 11.1 Unit Tests (Pure Kotlin, no Android)

| Test Class | Statefulness | Key Scenarios |
|------------|-------------|---------------|
| `UmpFrameDecoderTest` | Stateful | Complete parts, partial parts across feeds, 1-5 byte varint edge cases, max-size parts, empty payload, unknown type IDs, reset behavior |
| `SabrMessageDecoderTest` | Stateless | Each UMP type → correct SabrEvent, malformed protobuf, unknown UMP types |
| `SabrRequestBuilderTest` | Stateless | Initial request, continuation with buffered ranges, seek request, playback cookie round-trip |
| `SabrMediaBufferTest` | Stateful | Write at offset, read from covered range, read from uncovered range (timeout), coverage map accuracy, eviction, concurrent read/write |
| `SabrSessionControllerTest` | Stateful | Full state machine transitions, server reload handling, seek state, backoff |
| `ContinuationControllerTest` | Stateful | Demand-driven trigger, server policy override, buffer threshold, max silence |
| `SabrErrorClassifierTest` | Stateless | All HTTP codes, network exceptions; verify 403 → UNKNOWN_FORBIDDEN (not SOURCE_EXPIRED) |
| `SabrDataSourceTest` | Stateful | Normal read, bounded blocking timeout, seek position change, interruption |
| `SabrPlaybackTransportTest` | Stateless | `canHandle()` routing, session creation |
| `SabrPlaybackSessionTest` | Stateful | Lifecycle: create → prepare → stream → seek → release |
| `SabrInitParamsTest` | Stateless | Parse valid clientContextJson, missing fields, malformed JSON |

### 11.2 Test Fixtures

Binary UMP fixtures stored as test resources:
- `valid_audio_single_part.ump` — Single complete UMP part
- `valid_audio_multi_part.ump` — Multiple parts in one response
- `partial_part_split.ump` — Part split across two feeds
- `varint_edge_cases.ump` — 1, 2, 3, 4, 5-byte varints
- `error_response.ump` — Server error UMP message
- `reload_directive.ump` — Server reload directive
- `next_request_policy.ump` — Server continuation policy

### 11.3 Expected Test Count

| Category | Estimated Tests |
|----------|----------------|
| UMP framing (UmpFrameDecoder) | ~12 |
| Protocol (MessageDecoder, RequestBuilder) | ~10 |
| Buffer (SabrMediaBuffer) | ~10 |
| Session (SessionController, ContinuationController) | ~12 |
| Error + DataSource | ~8 |
| Transport + Session integration | ~5 |
| Init params parsing | ~4 |
| **Total** | **~61** |

**Post-Phase 4 target: 135 (baseline) + ~61 (SABR) = ~196 tests**

---

## 12. Implementation Order

| Phase | Components | Dependencies |
|-------|------------|-------------|
| 4.1 | Module scaffold + `build.gradle.kts` + `SabrTransportConfig` + `SabrSessionState` + `SabrEvent` | None |
| 4.2 | `UmpFrameDecoder` + `UmpMessageType` + tests | 4.1 |
| 4.3 | `SabrMessageDecoder` + tests | 4.1, 4.2 |
| 4.4 | `SabrRequestBuilder` + `SabrInitParams` + tests | 4.1 |
| 4.5 | `SabrMediaBuffer` + tests | 4.1 |
| 4.6 | `SabrSessionController` + tests | 4.2, 4.3, 4.4, 4.5 |
| 4.7 | `ContinuationController` + tests | 4.4, 4.5, 4.6 |
| 4.8 | `SabrErrorClassifier` + tests | 4.1 |
| 4.9 | `SabrDataSource` + tests | 4.5 |
| 4.10 | `SabrPlaybackSession` + `SabrPlaybackTransport` + integration tests | 4.6, 4.7, 4.8, 4.9 |
| 4.11 | `settings.gradle.kts` update + full build verification | All |

---

## 13. Integration with aurora-player-android

After `aurora-transport-sabr` is implemented, `aurora-player-android` will need:

1. **Register** `SabrPlaybackTransport` in the `PlaybackTransportRegistry`.
2. **Create** a `SabrDataSource.Factory` for Media3's `DefaultMediaSourceFactory`.
3. **Route** SABR 403 errors through `PlaybackErrorMapper.classifyHttp403()` using
   the `HttpErrorContext` from `SabrTransportError.HttpError`.

These changes are minimal and confined to `aurora-player-android` only.

---

## 14. Post-Implementation Verification

1. ✅ All existing 135 tests still pass (zero regressions)
2. ✅ All new SABR tests pass
3. ✅ `aurora-core` has zero imports from `aurora-transport-sabr` (I-1)
4. ✅ `aurora-transport-progressive` has zero imports from `aurora-transport-sabr` (I-2)
5. ✅ `aurora-provider-ytmusic` has zero imports from `aurora-transport-sabr` (I-3)
6. ✅ Full Gradle build succeeds with `aurora-transport-sabr` included
7. ✅ No sensitive data appears in any log output or diagnostic event
8. ✅ HTTP 403 passes through to player error mapper (not pre-classified)
