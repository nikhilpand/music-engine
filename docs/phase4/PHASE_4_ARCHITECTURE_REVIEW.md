# Phase 4 — SABR Architecture Review

**Date**: 2026-09-15
**Status**: CORRECTION PASS — DO NOT IMPLEMENT UNTIL RESOLVED
**Baseline**: 135/135 tests passing (user-declared baseline)
**Actual `@Test` count**: 137 (discrepancy in `aurora-provider-ytmusic`: 28 actual vs 26 reported)

---

## 1. Purpose

This document is a second-pass technical review of `docs/phase4/PHASE_4_SABR_ARCHITECTURE.md`.

The goal is to identify flaws, ambiguities, and protocol-reality gaps before any
production code is written for `aurora-transport-sabr`.

**Principle**: The repository is the source of truth. The protocol research is
evidence. This review corrects the architecture to be defensible.

---

## 2. Baseline Discrepancy

The user instructs us to treat 135/135 as baseline. However, grepping `@Test`
annotations across all four modules yields **137** tests:

| Module | User-Declared | Actual `@Test` Count |
|--------|---------------|----------------------|
| `aurora-core` | 37 | 37 |
| `aurora-player-android` | 54 | 54 |
| `aurora-provider-ytmusic` | 26 | **28** |
| `aurora-transport-progressive` | 18 | 18 |
| **Total** | **135** | **137** |

**Decision**: The architecture review documents the real count (137) for
engineering accuracy, but respects the user's instruction that 135 is the
contractual baseline. No existing test may be modified or deleted.

---

## 3. Protocol-Reality Findings

### 3.1 UMP Framing (from gsuberland/UMP_Format)

UMP (Universal Media Protocol) uses:
- **Variable-length integers** (1–5 bytes) for both the `type` and `size` fields.
  - Top N bits of the first byte encode the byte length (1–5).
  - 5-byte varints deviate from RFC 8794: they ignore the lower 3 bits of the
    prefix byte and read a raw 32-bit little-endian integer from the next 4 bytes.
- **TLV structure**: `[type: varint] [size: varint] [payload: byte[size]]`
- **Partial parts**: A UMP part's declared `size` may exceed the remaining bytes
  in the HTTP response body. The remaining bytes continue in the next response.
  This is critical — the parser must maintain cross-response state.
- **Content-Type**: `application/vnd.yt-ump`
- **First part**: Always type 20 (Onesie Header).

**Architectural Impact**: The initial architecture describes `UmpParser` as a
"pure function over InputStream" with "no state". This is **wrong**. The parser
must handle partial parts spanning multiple HTTP responses. It must be stateful
at the part level.

### 3.2 SABR Session Protocol

Based on research from YouTube.js, googlevideo library, and community analysis:

| Aspect | Reality | Initial Architecture |
|--------|---------|---------------------|
| Request method | HTTP POST with protobuf body to `/videoplayback` | ✅ Correct |
| Response format | `application/vnd.yt-ump` binary | ✅ Correct |
| Continuation trigger | **Demand-driven** — client sends new `VideoPlaybackAbrRequest` when buffer runs low, reporting `buffered_ranges` and `playback_position_ms` | ❌ Initial design uses a fixed-interval timer (`continuationIntervalMs = 10_000L`) |
| Seek mechanism | New `VideoPlaybackAbrRequest` with updated `playback_position_ms` and `buffered_ranges`; may reuse existing session or require new one depending on server response | ⚠️ Partially correct — design doesn't account for server-driven session invalidation |
| Session tokens | Server returns `playback_cookie` and `NextRequestPolicy` that must be echoed back | ❌ Not modeled in `PlaybackSource.Sabr` or `SabrSessionState` |
| Required auth fields | `PoToken`, `visitor_data`, cookies | ⚠️ `PlaybackSource.Sabr` has `clientContextJson` (may contain these) but no explicit modeling |
| UMP part types | Multiple types including init metadata (type 20/Onesie), media headers, media data, media end markers, format selection config, error/reload directives | ⚠️ Only `MEDIA_DATA`, `FORMAT_INIT`, `MEDIA_END`, `ERROR` listed |
| Server-initiated actions | `reloadPlayerResponse` can invalidate the session, requiring full re-initialization | ❌ Not modeled |

### 3.3 `PlaybackSource.Sabr` Model Gaps

The current model:

```kotlin
data class Sabr(
    val trackId: String,
    val serverEndpoint: String,
    val clientContextJson: String,
    val ustreamerConfig: String? = null,
    val audioFormat: AudioFormat,
    val expiresAtMs: Long? = null,
    val customCacheKey: String
)
```

**Missing fields for SABR session initiation**:
- `poToken: String?` — Proof of Origin token (from BotGuard/DroidGuard)
- `visitorData: String?` — Visitor identifier for session continuity
- `formatIds: List<Int>` — Requested itag format IDs
- `videoId: String` — The YouTube video ID (distinct from `trackId`)

**Decision**: These should NOT be added to `PlaybackSource.Sabr` directly.
The `clientContextJson` field is the correct opaque carrier for provider-specific
context. The transport must parse what it needs from that field. Adding explicit
fields would couple `aurora-core` to YouTube's protocol, violating isolation.

However, `aurora-transport-sabr` should define its own internal
`SabrInitParams` model that it extracts from `clientContextJson` during session
creation. This keeps the parsing and validation within the transport module.

---

## 4. Design Flaws in Initial Architecture

### 4.1 FLAW: Stateless UMP Parser

**Problem**: The architecture declares `UmpParser` as stateless with "no side
effects." But UMP responses contain **partial parts** — a part whose declared
size exceeds the remaining bytes in the current HTTP response. The remainder
arrives in the next HTTP response body.

**Fix**: `UmpParser` must maintain per-connection state:
- Current part type and total expected size (if a partial part is in progress)
- Bytes consumed so far for the current partial part
- A reassembly buffer for the current part

The parser should expose a `feed(bytes: ByteArray): List<UmpPart>` API rather
than consuming an `InputStream` to completion. This allows incremental feeding
from multiple HTTP response bodies.

### 4.2 FLAW: Fixed-Interval Continuation Timer

**Problem**: `ContinuationScheduler` uses `continuationIntervalMs = 10_000L`
(a fixed 10-second timer). In reality, SABR continuation is **demand-driven**:

1. The server pushes data until its transfer policy is satisfied.
2. The client monitors its buffer level (via `buffered_ranges`).
3. When the buffer falls below a threshold, the client sends a new
   `VideoPlaybackAbrRequest` with the current `playback_position_ms` and
   `buffered_ranges`.
4. The server's `NextRequestPolicy` (returned in each UMP response) may also
   specify when the client should send the next request.

**Fix**: Replace the fixed-interval `ContinuationScheduler` with a
**demand-driven `ContinuationController`** that:
- Monitors the buffer's coverage map
- Respects the server's `NextRequestPolicy` (if present)
- Fires a continuation request when `unbuffered_ahead < threshold`
- Has a maximum silence timeout as a safety net (not the primary trigger)

### 4.3 FLAW: Ring Buffer Design

**Problem**: The architecture proposes `MemoryRingBuffer` and
`DiskBackedRingBuffer` with a simple `write(data, timestampMs)` /
`read(position, length)` API. This models the buffer as a contiguous ring,
which fails for SABR because:

1. Seeking creates **gaps** in coverage — the buffer is not contiguous.
2. The server may send data out of order within a response.
3. The client must report `buffered_ranges` as a list of `[start, end]` byte
   ranges to the server. A ring buffer has no notion of this.
4. The user explicitly requires "coverage-based logic (missing vs. covered ranges)."

**Fix**: Replace with a **coverage-map buffer** (`SabrMediaBuffer`) that:
- Tracks byte coverage as `List<LongRange>` (sorted, non-overlapping)
- Supports random-access write at arbitrary byte offsets
- Supports reading from covered regions (blocks if requested range is not covered)
- Reports `getBufferedRanges(): List<LongRange>` for the continuation request
- Evicts the oldest covered segments when memory/disk limit is reached

### 4.4 FLAW: HTTP 403 = `SOURCE_EXPIRED` (Oversimplification)

**Problem**: `SabrErrorClassifier` maps `HTTP 403` directly to
`ErrorCategory.SOURCE_EXPIRED`. But the existing `PlaybackErrorMapper` in
`aurora-player-android` already has granular 403 sub-classification:

- `SOURCE_EXPIRED` (URL expired)
- `AUTHENTICATION_REQUIRED` (login/auth needed)
- `BOT_DETECTION` (captcha/robot)
- `PROVIDER_REJECTION` (geo/IP block)
- `UNKNOWN_FORBIDDEN` (unclassifiable 403)

The transport-level classifier should NOT collapse this taxonomy.

**Fix**: `SabrErrorClassifier` should produce an intermediate error with enough
context (HTTP status, response headers, response body snippet) so that the
existing `PlaybackErrorMapper` can perform its 403 sub-classification. The
transport should not duplicate that logic.

Alternatively, define a `SabrError` sealed class that carries the raw HTTP
response metadata, and let `PlaybackErrorMapper` handle the final classification.

### 4.5 FLAW: Missing Server Directives

**Problem**: SABR responses can contain server-initiated directives:
- `reloadPlayerResponse` — session is invalid, must re-initialize
- Format renegotiation — server changes the available format set
- Backoff policy — server requests the client to slow down
- CDN redirect — server redirects to a different endpoint

None of these are modeled.

**Fix**: The `SabrResponseParser` (or UMP message handler) must produce
**semantic events** from parsed UMP parts. These events should include:

```kotlin
sealed interface SabrEvent {
    data class MediaData(val formatId: Int, val data: ByteArray, val offsetBytes: Long) : SabrEvent
    data class FormatInit(val formatId: Int, val initSegment: ByteArray) : SabrEvent
    data class MediaEnd(val formatId: Int) : SabrEvent
    data class NextRequestPolicy(val playbackCookie: ByteArray?, val backoffMs: Long?) : SabrEvent
    data class ReloadRequired(val reason: String) : SabrEvent
    data class ServerRedirect(val newEndpoint: String) : SabrEvent
    data class StreamError(val code: Int, val message: String) : SabrEvent
}
```

### 4.6 FLAW: Session State Machine Missing States

**Problem**: The state machine has: CREATED → CONNECTING → STREAMING → CONTINUING → RECOVERING → CLOSED.
Missing states:
- **SEEKING** — distinct from CONTINUING; invalidates the current buffer position
- **RELOADING** — server demanded session re-initialization via `reloadPlayerResponse`
- **BACKOFF** — server requested client-side backoff

**Fix**: Expand the state machine:

```
CREATED → CONNECTING → STREAMING ←→ CONTINUING
                  ↕           ↕
               SEEKING    RECOVERING
                  ↕           ↕
               RELOADING → CLOSED
                            (also from BACKOFF timeout)
```

### 4.7 FLAW: DataSource Blocking Semantics

**Problem**: The `SabrDataSource.read()` description says "Block if data not yet
available (backpressure)." But ExoPlayer's `DataSource.read()` runs on the
loading thread. Indefinite blocking will:
- Prevent ExoPlayer from entering `STATE_BUFFERING`
- Block seek processing
- Prevent the player from being released cleanly

**Fix**: `SabrDataSource.read()` should:
1. If data is available in the buffer, return it immediately.
2. If data is NOT available, wait with a **bounded timeout** (e.g., 5s).
3. If the timeout expires, throw `IOException` to trigger ExoPlayer's
   `LoadErrorHandlingPolicy`, which feeds into Aurora's recovery path.
4. Support interruption via `Thread.interrupt()` for clean shutdown.

### 4.8 FLAW: Baseline Test Count Uses 137

**Problem**: Section 12 says "All existing 137 tests still pass." The user
explicitly requires 135 as the baseline number.

**Fix**: Use the user-declared baseline. Note the discrepancy for internal
tracking but do not assert 137 in external documentation.

---

## 5. Corrected Data Processing Pipeline

The initial architecture vaguely describes UMP → buffer. The corrected pipeline
must have clear separation of concerns:

```
Layer 1 — NETWORK
  HTTP/2 POST → raw response bytes
        │
Layer 2 — FRAMING (UmpFrameDecoder)
  raw bytes → UmpPart { type: Int, payload: ByteArray }
  Handles partial parts across HTTP responses.
  STATEFUL: maintains reassembly state.
        │
Layer 3 — PROTOCOL (SabrMessageDecoder)
  UmpPart → SabrEvent (sealed interface)
  Deserializes protobuf payloads within UMP parts.
  Maps UMP type IDs to semantic events.
  STATELESS per message.
        │
Layer 4 — SESSION (SabrSessionController)
  SabrEvent → session state transitions + buffer writes
  Handles NextRequestPolicy, ReloadRequired, etc.
  Owns the continuation controller.
  STATEFUL: owns session lifecycle.
        │
Layer 5 — BUFFER (SabrMediaBuffer)
  Coverage-map based byte storage.
  Provides blocking read with timeout for DataSource.
  Reports buffered ranges for continuation requests.
        │
Layer 6 — PLAYER BRIDGE (SabrDataSource)
  SabrMediaBuffer → Media3 DataSource interface
  Bounded blocking read. Interruptible.
```

---

## 6. Corrected `PlaybackSource.Sabr` Assessment

The current model is **sufficient** for Phase 4:

| Field | Purpose | Adequate? |
|-------|---------|-----------|
| `trackId` | Aurora-internal track identifier | ✅ |
| `serverEndpoint` | SABR `/videoplayback` URL | ✅ |
| `clientContextJson` | Opaque JSON carrying PoToken, visitor_data, format IDs, video ID | ✅ (transport parses internally) |
| `ustreamerConfig` | Server streamer configuration blob | ✅ |
| `audioFormat` | Target audio format | ✅ |
| `expiresAtMs` | Source expiration time | ✅ |
| `customCacheKey` | Cache key | ✅ |

**No changes to `aurora-core` required for Phase 4.**

The transport module will define `SabrInitParams` internally:

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
    // Validation happens here, not in aurora-core
}
```

---

## 7. Corrected Error Classification Strategy

### 7.1 Transport-Level Errors (in `aurora-transport-sabr`)

The transport should classify errors into Aurora's existing `ErrorCategory`
enum **but** with SABR-specific context:

```kotlin
sealed class SabrTransportError(
    val category: ErrorCategory,
    val isRecoverable: Boolean,
    val httpStatusCode: Int? = null,
    val responseHeaders: Map<String, List<String>> = emptyMap(),
    val responseBodySnippet: String? = null
) {
    class UmpParseError(cause: Exception) :
        SabrTransportError(ErrorCategory.MALFORMED_MEDIA, false)

    class ProtobufError(cause: Exception) :
        SabrTransportError(ErrorCategory.MALFORMED_MEDIA, false)

    class HttpError(code: Int, headers: Map<String, List<String>>, body: String?) :
        SabrTransportError(
            category = when (code) {
                429 -> ErrorCategory.RATE_LIMITED
                in 500..599 -> ErrorCategory.HTTP_SERVER
                // 403 is NOT pre-classified — passed through to player error mapper
                else -> ErrorCategory.UNKNOWN
            },
            isRecoverable = code != 404,
            httpStatusCode = code,
            responseHeaders = headers,
            responseBodySnippet = body?.take(500) // truncate for safety
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
        SabrTransportError(ErrorCategory.SOURCE_EXPIRED, true)
}
```

### 7.2 403 Classification

HTTP 403 from SABR must be passed to the existing `PlaybackErrorMapper.classifyHttp403()`
in `aurora-player-android`. The transport should NOT duplicate that logic.

The transport wraps the 403 in a `PlaybackException` with the HTTP response
context, and the existing mapper handles sub-classification.

---

## 8. Seek Architecture

### 8.1 Seek Flow

```
ExoPlayer.seekTo(positionMs)
    │
    ▼
SabrDataSource notices read position changed
    │
    ▼
SabrSessionController.seekTo(positionMs)
    │
    ├── Check if target position is within buffered ranges
    │     ├── YES → Update read cursor, no network request
    │     └── NO → Continue below
    │
    ├── Transition state to SEEKING
    ├── Abort any in-flight continuation request
    ├── Build new VideoPlaybackAbrRequest with:
    │     - playback_position_ms = target
    │     - buffered_ranges = current coverage map
    │     - playback_cookie (if available)
    ├── POST to server endpoint
    ├── Parse UMP response (may include new init segment)
    ├── Write media data to buffer at new offset
    └── Transition state back to STREAMING
```

### 8.2 Seek-in-Buffer Optimization

If the seek target falls within an already-covered byte range, no network
request is needed. The DataSource simply repositions its read cursor.

---

## 9. Session Ownership and Lifecycle

### 9.1 Ownership Chain

```
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

### 9.2 Lifecycle Contract

1. `SabrPlaybackTransport.createSession()` — Creates session, does NOT connect.
2. `SabrPlaybackSession.prepare()` — Sends initial request, establishes UMP stream.
3. `SabrPlaybackSession` is active — Data flows through buffer to DataSource.
4. `SabrPlaybackSession.release()` — Cancels all coroutines, closes buffer, closes HTTP connection.
5. The session is single-use. After `release()`, a new session must be created.

### 9.3 Aurora Global State vs SABR Internal State

| Aurora `EngineState` | SABR `SabrSessionState` | Relationship |
|---------------------|------------------------|--------------|
| `RESOLVING` | (no session yet) | Provider resolving, transport not involved |
| `PREPARING` | `CREATED → CONNECTING` | Session handshake |
| `PLAYING` | `STREAMING` | Normal playback |
| `BUFFERING` | `STREAMING` or `CONTINUING` | SABR is fetching more data |
| `SEEKING` | `SEEKING` | Seek in progress |
| `ERROR` | `RECOVERING` or `CLOSED` | Recovery or terminal failure |
| `IDLE` | `CLOSED` | Session released |

---

## 10. Security Review

### 10.1 Logging Rules (Mandatory)

The following must NEVER appear in logs, diagnostics, or crash reports:

| Data | Redaction |
|------|-----------|
| `serverEndpoint` URL | `[SABR_ENDPOINT]` |
| `clientContextJson` | `[CLIENT_CONTEXT]` |
| `poToken` / `visitorData` | `[AUTH_TOKEN]` |
| `playback_cookie` | `[PLAYBACK_COOKIE]` |
| Response `Set-Cookie` headers | `[COOKIES]` |
| Request `Authorization` headers | `[AUTH_HEADER]` |

### 10.2 Memory Safety

- `SabrMediaBuffer` must zero-fill deallocated segments before eviction
  (defense-in-depth against memory inspection).
- `SabrInitParams` should use `String` (not `CharArray`) for tokens because
  Kotlin strings are immutable and the JVM already manages their lifecycle.
  Explicit zeroing of credentials is not practical on JVM.

### 10.3 Network Safety

- TLS 1.2+ required (enforced by OkHttp default).
- Certificate pinning is OUT OF SCOPE for Phase 4 (would require maintaining
  Google certificate hashes).
- HTTP/2 preferred but HTTP/1.1 fallback must work (OkHttp handles this).

---

## 11. Performance Considerations

### 11.1 Memory Budget

| Component | Budget | Rationale |
|-----------|--------|-----------|
| UMP frame reassembly buffer | 256 KB max | Largest observed UMP part is ~200KB |
| In-memory media buffer | 8 MB default | ~30s of 256kbps audio |
| Protobuf parsing overhead | Negligible | javalite is allocation-efficient |

### 11.2 Thread Model

| Operation | Thread | Blocking? |
|-----------|--------|-----------|
| HTTP POST | OkHttp dispatcher thread | Non-blocking (coroutine) |
| UMP frame decode | Dedicated coroutine | Non-blocking |
| Buffer write | Coroutine + mutex | Brief lock |
| Buffer read (DataSource) | ExoPlayer loading thread | Bounded blocking (timeout) |
| Continuation decision | Coroutine | Non-blocking |

### 11.3 Latency Targets

| Metric | Target |
|--------|--------|
| Time to first audio byte (cold start) | < 2 seconds |
| Seek-to-audio latency | < 1 second |
| Continuation request turnaround | < 500ms |

---

## 12. Corrected Test Architecture

### 12.1 Unit Test Strategy

| Test Class | Statefulness | Key Scenarios |
|------------|-------------|---------------|
| `UmpFrameDecoderTest` | Stateful | Complete parts, partial parts across feeds, varint edge cases (1-5 byte), max-size parts, empty payload, unknown type IDs |
| `SabrMessageDecoderTest` | Stateless | Each UMP type → correct SabrEvent, malformed protobuf, unknown UMP types |
| `SabrRequestBuilderTest` | Stateless | Initial request, continuation with buffered ranges, seek request, playback cookie round-trip |
| `SabrMediaBufferTest` | Stateful | Write at offset, read from covered range, read from uncovered range (timeout), coverage map accuracy, eviction, concurrent read/write |
| `SabrSessionControllerTest` | Stateful | Full state machine transitions, server reload handling, backoff policy, seek state |
| `ContinuationControllerTest` | Stateful | Demand-driven trigger, server policy override, buffer threshold, maximum silence timeout |
| `SabrErrorClassifierTest` | Stateless | All HTTP codes, network exceptions, session states; verify 403 is NOT pre-classified |
| `SabrDataSourceTest` | Stateful | Normal read, bounded blocking timeout, interruption, seek position change |
| `SabrPlaybackTransportTest` | Stateless | `canHandle()` routing, session creation |
| `SabrPlaybackSessionTest` | Stateful | Lifecycle: create → prepare → stream → seek → release |
| `SabrInitParamsTest` | Stateless | Parse valid clientContextJson, missing fields, malformed JSON |

### 12.2 Expected Test Count

| Category | Tests |
|----------|-------|
| UMP framing (UmpFrameDecoder) | ~12 |
| Protocol (MessageDecoder, RequestBuilder) | ~10 |
| Buffer (SabrMediaBuffer) | ~10 |
| Session (SessionController, ContinuationController) | ~12 |
| Error + DataSource | ~8 |
| Transport + Session integration | ~5 |
| Init params parsing | ~4 |
| **Total** | **~61** |

Post-Phase 4 target: 135 (baseline) + ~61 (SABR) = **~196 tests**

---

## 13. GO/NO-GO Assessment

### 13.1 Resolved Issues (Safe to Proceed After Correction)

- [x] UMP parser must be stateful (partial parts)
- [x] Continuation must be demand-driven, not timer-based
- [x] Buffer must be coverage-map, not ring buffer
- [x] 403 must NOT be auto-classified as SOURCE_EXPIRED
- [x] Server directives (reload, redirect, backoff) must be modeled
- [x] DataSource blocking must be bounded with timeout
- [x] Session state machine must include SEEKING and RELOADING
- [x] `PlaybackSource.Sabr` is sufficient (no aurora-core changes)
- [x] Security logging rules documented

### 13.2 Open Questions for User

1. **Disk-backed buffer**: The user spec §23 requires disk-backed buffer for
   large streams. For audio-only SABR (our primary use case), the maximum buffer
   is ~8MB. Should we still implement `DiskBackedBuffer`, or defer to Phase 5?

2. **HTTP/2 requirement**: SABR works best over HTTP/2 for multiplexing. OkHttp
   supports HTTP/2 by default. Should we enforce HTTP/2-only, or allow HTTP/1.1
   fallback?

3. **Protobuf generation**: Should we use pre-compiled `.proto` files with
   `protobuf-javalite`, or hand-write the serialization for the small number of
   message types we need? Hand-written avoids the protoc build step but is
   harder to maintain.

### 13.3 Verdict

**NO-GO for implementation until**:
1. The corrected `PHASE_4_SABR_ARCHITECTURE.md` is produced (incorporating all
   fixes from this review).
2. The user reviews and approves the corrected architecture.
3. Open questions are resolved.

---

## 14. Summary of Corrections Required

| # | Section | Correction |
|---|---------|------------|
| C1 | UmpParser | Rename to `UmpFrameDecoder`, make stateful with `feed()` API |
| C2 | ContinuationScheduler | Replace with demand-driven `ContinuationController` |
| C3 | SabrMediaBuffer | Replace ring buffer with coverage-map buffer |
| C4 | SabrErrorClassifier | Do not auto-classify 403; pass through to player mapper |
| C5 | SabrResponseParser | Replace with `SabrMessageDecoder` producing `SabrEvent` sealed class |
| C6 | SabrSessionState | Add SEEKING, RELOADING states |
| C7 | SabrDataSource.read() | Add bounded timeout and interruption support |
| C8 | Module structure | Add `SabrInitParams`, `SabrEvent`, `ContinuationController`, `UmpFrameDecoder` |
| C9 | Data flow | Document 6-layer pipeline (Network → Frame → Protocol → Session → Buffer → Player) |
| C10 | Test count | Use 135 baseline, target ~196 post-Phase 4 |
| C11 | Server directives | Model reload, redirect, backoff, format renegotiation |
| C12 | Baseline | Document 135 (user) vs 137 (actual) discrepancy |
