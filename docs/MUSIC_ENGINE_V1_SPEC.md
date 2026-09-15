# AuroraMusicEngine — Music Engine V1.2 Technical Specification

**Document Version:** 1.2.0  
**Status:** Architecture Baseline — Ready for Phased Implementation  
**Target:** Android API 26+ + React Native New Architecture  
**Primary Language:** Kotlin + TypeScript  
**Primary Playback:** Android Media3  
**Architecture:** Provider-agnostic, transport-agnostic, failure-resilient

---

# 1. Executive Decision

AuroraMusicEngine will **not** be implemented as a direct clone of VIVI, ViMusic, InnerTune, or youtubei.js.

Instead, Aurora will combine the strongest architectural ideas observed across those projects while introducing a higher-level abstraction:

```text
Provider
   ↓
Playback Orchestrator
   ↓
Transport
   ↓
Candidate / Session
   ↓
Validation
   ↓
Media3
   ↓
Audio Output
```

The central design principle is:

> InnerTube is a provider implementation detail. Playback transport is a separate abstraction.

This prevents the application from becoming permanently coupled to one undocumented provider protocol.

---

# 2. Research-Derived Architecture

## 2.1 VIVI

VIVI demonstrates strong modular separation, with dedicated modules for InnerTube and additional providers/integrations.

Useful concepts:

- modular Android architecture
- separate InnerTube module
- multiple provider integrations
- native playback
- caching
- lyrics and metadata providers

Do not copy:

- assumptions that a fixed client ladder will remain valid
- provider-specific behavior leaking into the core engine

---

## 2.2 ViMusic

ViMusic is valuable primarily as an architectural reference for:

- Android music application design
- InnerTube integration
- caching
- queue management
- playback lifecycle

The original repository should not be treated as the authoritative source for current playback behavior because it is no longer the best representation of the rapidly changing provider environment.

---

## 2.3 InnerTune

InnerTune provides a strong reference for:

- complete Android YouTube Music client architecture
- catalog browsing
- background playback
- downloads
- queue management
- Android integration

Aurora should learn from its separation of application concerns but maintain a cleaner provider/playback boundary.

---

## 2.4 youtubei.js

youtubei.js is primarily a reusable JavaScript client for YouTube's internal API.

Useful concepts:

- session abstraction
- InnerTube client abstraction
- player response parsing
- player-script handling
- transformation handling
- client-specific behavior

Aurora should not make the JavaScript implementation itself the engine core.

The native Android playback layer should remain authoritative.

---

## 2.5 Zemer and current playback research

Current open-source implementations demonstrate that progressive media URLs are no longer sufficient as the sole playback model.

SABR/UMP-style transport needs to be treated as a distinct transport rather than merely another `StreamCandidate`.

Zemer's implementation separates SABR protocol handling, session management, buffering and protection logic. Its documentation describes `serverAbrStreamingUrl`, `VideoPlaybackAbrRequest`, UMP parsing and continuation sessions. 

Therefore Aurora V1.2 introduces:

```text
PlaybackTransport
├── ProgressiveTransport
├── SabrTransport
├── HlsTransport
└── LocalFileTransport
```

---

# 3. Core Architecture

```text
                         React Native
                              │
                              ▼
                       AuroraEngine API
                              │
                              ▼
                    ┌───────────────────┐
                    │   Aurora Core     │
                    └─────────┬─────────┘
                              │
              ┌───────────────┼────────────────┐
              ▼               ▼                ▼
          Catalog          Playback         Storage
              │           Orchestrator         │
              │               │                │
              ▼               ▼                ▼
        Provider SPI     Strategy Registry   Cache Layers
              │               │
      ┌───────┼───────┐       │
      ▼       ▼       ▼       │
     YTM    JioSaavn Local     │
      │                       │
      ▼                       ▼
 InnerTube              Transport Selection
                              │
                 ┌────────────┼─────────────┐
                 ▼            ▼             ▼
            Progressive      SABR          HLS
                 │            │             │
                 └────────────┼─────────────┘
                              ▼
                       Source Validation
                              │
                              ▼
                        Source Ranking
                              │
                              ▼
                            Media3
                              │
                              ▼
                         Audio Output
```

---

# 4. Module Structure

```text
aurora-music-engine/
│
├── aurora-core/
│   ├── model/
│   ├── provider/
│   ├── playback/
│   ├── transport/
│   ├── cache/
│   ├── recovery/
│   ├── diagnostics/
│   └── config/
│
├── aurora-provider-ytmusic/
│   ├── innertube/
│   │   ├── client/
│   │   ├── session/
│   │   ├── parser/
│   │   ├── search/
│   │   ├── browse/
│   │   └── player/
│   │
│   ├── transform/
│   ├── token/
│   └── adapter/
│
├── aurora-transport-progressive/
│   ├── resolver/
│   ├── validation/
│   └── datasource/
│
├── aurora-transport-sabr/
│   ├── protocol/
│   ├── session/
│   ├── buffer/
│   └── datasource/
│
├── aurora-player-android/
│   ├── service/
│   ├── media3/
│   ├── session/
│   ├── audio/
│   └── queue/
│
├── aurora-storage/
│   ├── metadata/
│   ├── resolution/
│   ├── media/
│   └── configuration/
│
├── aurora-bridge-rn/
│   ├── android/
│   └── js/
│
└── tests/
    ├── unit/
    ├── integration/
    ├── playback/
    ├── transport/
    └── fixtures/
```

---

# 5. Provider SPI

YouTube Music must never be referenced directly from core code.

```kotlin
interface MusicProvider {

    val providerId: String

    val catalog: CatalogProvider

    val playback: PlaybackProvider

    val capabilities: Set<ProviderCapability>
}
```

```kotlin
interface CatalogProvider {

    suspend fun search(
        query: String,
        filter: SearchFilter
    ): Result<SearchResultPage>

    suspend fun getTrack(
        trackId: String
    ): Result<Track>

    suspend fun getAlbum(
        albumId: String
    ): Result<Album>

    suspend fun getArtist(
        artistId: String
    ): Result<Artist>

    suspend fun getPlaylist(
        playlistId: String
    ): Result<Playlist>
}
```

---

# 6. Playback Abstraction

The provider should return a playback description rather than directly controlling Media3.

```kotlin
interface PlaybackProvider {

    suspend fun resolve(
        track: Track,
        context: ResolutionContext
    ): Result<PlaybackResolution>
}
```

```kotlin
data class PlaybackResolution(
    val trackId: String,
    val sources: List<PlaybackSource>,
    val resolvedAt: Instant
)
```

---

# 7. PlaybackSource

Do not represent every playback mechanism as a URL.

```kotlin
sealed interface PlaybackSource {

    val providerId: String
    val trackId: String

    data class Progressive(
        override val providerId: String,
        override val trackId: String,
        val url: String,
        val codec: AudioCodec,
        val bitrateBps: Int,
        val sampleRateHz: Int,
        val expiresAt: Instant?,
        val requiresTransform: Boolean
    ) : PlaybackSource

    data class Sabr(
        override val providerId: String,
        override val trackId: String,
        val endpoint: String,
        val metadata: SabrMetadata
    ) : PlaybackSource

    data class Hls(
        override val providerId: String,
        override val trackId: String,
        val manifestUrl: String
    ) : PlaybackSource

    data class Local(
        override val providerId: String,
        override val trackId: String,
        val fileUri: String
    ) : PlaybackSource
}
```

---

# 8. Transport Interface

```kotlin
interface PlaybackTransport {

    val transportId: String

    fun supports(
        source: PlaybackSource,
        device: DeviceCapabilities
    ): Boolean

    suspend fun prepare(
        source: PlaybackSource,
        context: TransportContext
    ): Result<PlaybackSession>
}
```

Implementations:

```text
ProgressiveTransport
SabrTransport
HlsTransport
LocalFileTransport
```

The player does not need to understand provider-specific protocols.

---

# 9. Strategy Registry

Replace the fixed "client ladder" with a strategy registry.

```kotlin
data class PlaybackStrategy(
    val id: String,
    val providerId: String,
    val transportId: String,
    val capabilities: StrategyCapabilities,
    val enabled: Boolean
)
```

A strategy can express:

```text
requires authentication
requires token
requires transformation
supports progressive
supports SABR
supports specific content
```

The system then evaluates strategies dynamically.

---

# 10. Strategy Selection

Do not hard-code:

```text
Client A = Tier 1
Client B = Tier 2
```

Instead:

```text
Track
 ↓
Available strategies
 ↓
Capability filtering
 ↓
Health filtering
 ↓
Quality scoring
 ↓
Network scoring
 ↓
Risk scoring
 ↓
Candidate ranking
 ↓
Attempt best candidate
```

---

# 11. Candidate Ranking

Initial scoring:

```text
Score =
    0.35 × Quality
  + 0.20 × Codec
  + 0.25 × Health
  + 0.10 × Network
  + 0.10 × Reliability
  - RiskPenalty
```

These coefficients are **initial defaults**, not immutable truths.

The engine must record outcomes so they can later be tuned using real measurements.

---

# 12. Health Tracking

For each strategy:

```kotlin
data class StrategyHealth(
    val strategyId: String,
    val successes: Long,
    val failures: Long,
    val consecutiveFailures: Int,
    val averageResolutionMs: Long,
    val averageStartupMs: Long,
    val lastSuccessAt: Instant?,
    val lastFailureAt: Instant?
)
```

Calculate:

```text
successRate
recentFailureRate
latencyScore
startupScore
```

Health must decay over time so old failures do not permanently disable a strategy.

---

# 13. Circuit Breaker

Initial configuration:

```text
3 consecutive failures
within 60 seconds
        ↓
temporary demotion
        ↓
10-minute cooldown
```

However, failures must be classified.

For example:

```text
403
timeout
DNS
codec unsupported
token failure
malformed response
provider unavailable
network changed
```

A network-wide failure must not automatically punish a provider strategy.

---

# 14. Resolution Deduplication

Multiple requests for the same track must share the same in-flight resolution.

```kotlin
class ResolutionCoordinator {
    private val requests =
        ConcurrentHashMap<ResolutionKey, Deferred<Result<PlaybackResolution>>>()
}
```

The key should include relevant context:

```text
provider
track
quality
network profile
authentication state
transport constraints
```

This prevents an incompatible cached resolution from being reused incorrectly.

---

# 15. Validation Philosophy

Do not perform unnecessary standalone probes.

Preferred flow:

```text
resolve
 ↓
Media3 DataSource
 ↓
actual request
 ↓
observe response
 ↓
observe decoder
 ↓
observe startup
 ↓
continue playback
```

A source is considered successful only when the playback pipeline can actually consume it.

---

# 16. Failure Recovery

```text
PLAYING
   │
   ▼
ERROR
   │
   ▼
Classify Failure
   │
   ├── transient network
   │       ↓
   │    retry
   │
   ├── source failure
   │       ↓
   │    next source
   │
   ├── expired source
   │       ↓
   │    re-resolve
   │
   ├── transport failure
   │       ↓
   │    alternate transport
   │
   └── provider failure
           ↓
       alternate strategy
```

Recovery must preserve the desired playback position whenever possible.

---

# 17. Network Change Handling

Detect:

```text
Wi-Fi
Cellular
VPN
offline
network loss
network recovery
```

When the network changes:

```text
current source
      ↓
mark connection context stale
      ↓
attempt continuation
      ↓
if source fails
      ↓
re-resolve
      ↓
seek to previous position
```

Do not guarantee a specific recovery time until real device testing establishes it.

---

# 18. Resolution Cache

Separate resolution knowledge from media data.

```text
ResolutionKnowledge
├── preferred strategy
├── preferred transport
├── preferred codec
├── historical health
└── last success
```

Do not treat temporary stream URLs as long-lived cache objects.

---

# 19. Media Cache

Separate from resolution cache.

```text
MediaCache
├── trackId
├── providerId
├── local URI
├── size
├── duration
├── codec
└── checksum/integrity metadata
```

Media caching should be explicitly controlled by product policy and provider/licensing constraints.

---

# 20. Player Configuration Cache

Player transformation/configuration information should have its own lifecycle.

```text
PlayerConfigRepository
├── bundled last-known-good
├── remote configuration
├── schema validation
├── compatibility validation
└── rollback
```

Remote configuration must never be treated as arbitrary trusted executable code.

---

# 21. Player Transform Provider

Do not hard-code QuickJS into the core architecture.

```kotlin
interface PlayerTransformProvider {

    suspend fun transform(
        input: TransformInput
    ): Result<TransformResult>
}
```

Potential implementations:

```text
EmbeddedTransformProvider
WebViewTransformProvider
FutureTransformProvider
```

This allows the implementation to change without rewriting the playback system.

---

# 22. Token Provider

Token handling must be isolated.

```kotlin
interface PlaybackTokenProvider {

    suspend fun getToken(
        request: TokenRequest
    ): Result<PlaybackToken>
}
```

The provider adapter determines whether a token is required.

The core engine does not need to know why.

---

# 23. SABR Transport

SABR must be an isolated transport.

```text
SabrTransport
│
├── SabrProtocol
├── UmpParser
├── MessageBuilder
├── Session
├── Continuation
├── SeekLogic
├── Protection
├── Buffer
└── DataSource
```

The protocol implementation must be independently unit-tested.

The buffer must be disk-backed for large streams rather than assuming the entire media object fits in memory.

---

# 24. Media3 Integration

Media3 is the only component responsible for actual Android audio playback.

```text
PlaybackOrchestrator
        ↓
PlaybackTransport
        ↓
Media3 DataSource
        ↓
ExoPlayer
        ↓
MediaSession
        ↓
Audio Output
```

The engine must support:

- background playback
- lock-screen controls
- audio focus
- Bluetooth
- notification
- headset events
- Android lifecycle
- playback speed
- seeking
- queue management

---

# 25. Android Service

Use:

```text
MediaLibraryService
```

with Media3.

The service owns:

```text
Player
MediaSession
Queue
Playback state
Notification
```

React Native must not become the source of truth for playback state.

---

# 26. React Native Architecture

React Native is a control/UI layer.

```text
React Native
      │
      ▼
TurboModule
      │
      ▼
Native Aurora Engine
      │
      ▼
Media3
```

Minimal API:

```typescript
play()
pause()
resume()
stop()

seekTo(positionMs)

skipToNext()
skipToPrevious()

loadQueue()
addTrackToQueue()
removeTrackFromQueue()
clearQueue()

setAudioQuality()
setPlaybackSpeed()

getPlaybackState()
getCurrentTrack()
```

---

# 27. State Model

```text
IDLE
RESOLVING
PREPARING
BUFFERING
PLAYING
PAUSED
STALLED
RECOVERING
ENDED
ERROR
```

Transitions must be explicit.

No component should infer playback state from UI assumptions.

---

# 28. Progress Events

Native engine:

```text
maximum 4Hz
```

React Native:

```text
interpolate visually to 60fps
```

The UI should never require native callbacks every animation frame.

---

# 29. Prefetch

Prefetch is adaptive rather than fixed.

Inputs:

```text
current track progress
remaining duration
queue position
network
metered state
battery
next-track confidence
recent skip frequency
```

Example:

```text
Wi-Fi + stable queue
    → pre-resolve next track

Metered cellular + uncertain queue
    → minimal/no prefetch

rapid skipping
    → cancel prefetch
```

---

# 30. Diagnostics

Every resolution attempt should produce structured diagnostics.

```json
{
  "trackId": "...",
  "strategy": "...",
  "transport": "...",
  "resolutionMs": 412,
  "startupMs": 781,
  "result": "success",
  "failureReason": null
}
```

Never log:

```text
cookies
session secrets
private tokens
authorization headers
full signed URLs
```

Diagnostics must be safe to export.

---

# 31. Testing Architecture

Three levels:

## Unit

Test:

- parsers
- models
- ranking
- health scoring
- state machines
- cache keys
- protocol parsing

## Integration

Test:

- provider → resolver
- resolver → transport
- transport → Media3
- RN → native engine

## Live Diagnostic Harness

Use controlled live tests to detect provider changes.

```text
provider
 ↓
resolve
 ↓
transport
 ↓
stream
 ↓
drain/play
 ↓
measure
```

Live tests must not be required for every local build.

---

# 32. Failure Matrix

Aurora must explicitly test:

```text
network loss
network recovery
Wi-Fi → cellular
cellular → Wi-Fi
DNS failure
timeout
403
404
expired source
malformed response
unsupported codec
decoder failure
provider failure
transport failure
rapid skip
seek during recovery
app backgrounding
app foregrounding
Bluetooth disconnect
Bluetooth reconnect
headphone unplug
phone-call interruption
process recreation
queue mutation
```

---

# 33. Security Requirements

Never expose provider credentials or session material to React Native.

Use Android secure storage for sensitive session data.

Do not execute arbitrary remotely downloaded code.

Player configuration must be:

```text
download
 ↓
schema validation
 ↓
sanity checks
 ↓
compatibility validation
 ↓
accept
```

Otherwise:

```text
reject
 ↓
retain last-known-good
```

---

# 34. Performance Targets

V1 targets are measurements, not guarantees.

Track:

```text
search latency
resolution latency
startup latency
buffering ratio
recovery latency
memory usage
CPU usage
battery impact
cache effectiveness
skip response time
```

Initial goals:

```text
cold playback startup: < 2.5 s target
warm resolution: < 500 ms target
normal UI command response: < 100 ms target
no main-thread network I/O
no playback-thread blocking network calls
```

Targets must be measured across real devices before being promoted to hard acceptance criteria.

---

# 35. V1 Scope

## MUST HAVE

```text
Provider abstraction
YouTube Music provider
catalog/search
track metadata
one working playback transport
Media3
background playback
queue
basic recovery
resolution cache
diagnostics
unit tests
integration tests
React Native bridge
```

## SHOULD HAVE

```text
adaptive ranking
health tracking
prefetch
network handover
second playback transport
live diagnostics
```

## NOT V1

```text
multiple public providers
complex DSP
advanced audio effects
Android Auto customization
large offline system
remote service infrastructure
provider marketplace
ML-based prediction
```

---

# 36. Implementation Phases

## Phase 0 — Research Lock

Deliver:

```text
architecture-comparison.md
playback-comparison.md
innertube-comparison.md
failure-analysis.md
MUSIC_ENGINE_V1_SPEC.md
```

No production implementation.

---

## Phase 1 — Core

Build:

```text
models
provider SPI
transport SPI
state machine
diagnostics
cache interfaces
```

Everything must compile and pass unit tests.

---

## Phase 2 — Catalog

Implement:

```text
YouTube Music provider
session
search
browse
metadata normalization
```

No playback yet.

---

## Phase 3 — First Playback Transport

Implement one supported transport end-to-end:

```text
provider
 ↓
resolver
 ↓
source
 ↓
Media3
 ↓
audio
```

Success criterion:

> A real track can be resolved and played reliably through the complete native pipeline.

---

## Phase 4 — Recovery

Implement:

```text
failure classification
retry
source fallback
re-resolution
position recovery
network change handling
```

---

## Phase 5 — Adaptive Resolver

Implement:

```text
strategy registry
candidate ranking
health scoring
circuit breakers
resolution deduplication
```

---

## Phase 6 — SABR

Implement SABR as an independent transport.

Do not modify the existing working transport unnecessarily.

```text
ProgressiveTransport
        +
SabrTransport
```

Both implement:

```text
PlaybackTransport
```

---

## Phase 7 — React Native

Implement:

```text
TurboModule
TypeScript API
state events
hooks
```

---

## Phase 8 — Performance

Measure:

```text
startup
memory
battery
buffering
recovery
queue operations
```

Optimize based on evidence.

---

## Phase 9 — Hardening

Run:

```text
100-track test
rapid skip test
network transition test
background playback test
process recreation test
Bluetooth test
long-session test
```

Fix actual failures rather than weakening tests.

---

# 37. Definition of Done

Aurora V1 is complete only when:

```text
[ ] Core builds
[ ] Provider layer works
[ ] Catalog works
[ ] Playback works
[ ] Media3 works
[ ] Background playback works
[ ] Queue works
[ ] Recovery works
[ ] Resolution caching works
[ ] Diagnostics work
[ ] React Native bridge works
[ ] Unit tests pass
[ ] Integration tests pass
[ ] No main-thread network I/O
[ ] No known lifecycle crash
[ ] Long playback test passes
[ ] Failure matrix has been exercised
```

---

# 38. Engineering Rules for AI Agents

This project will be developed primarily using AI coding agents.

Agents MUST:

1. Read the specification before modifying code.
2. Never rewrite architecture without documenting why.
3. Never implement multiple phases simultaneously.
4. Never delete tests to make builds pass.
5. Never replace failing tests with weaker tests.
6. Never invent provider behavior.
7. Never place provider-specific code in `aurora-core`.
8. Never perform network I/O on the Android main thread.
9. Never expose secrets through React Native.
10. Never commit credentials.
11. Run tests after every behavioral change.
12. Explain unknown behavior instead of guessing.
13. Keep commits small and logically scoped.
14. Preserve working playback paths when adding experimental transports.
15. Treat provider behavior as unstable.

---

# 39. AI Development Workflow

```text
                    Human
                      │
                      ▼
                Specification
                      │
                      ▼
                 Antigravity
                      │
                      ▼
                Implementation
                      │
                      ▼
                    Tests
                      │
                      ▼
                   Codex
                      │
                Independent Review
                      │
                      ▼
                 Antigravity Fixes
                      │
                      ▼
                    Tests
                      │
                      ▼
                 Human Approval
```

Antigravity is the primary implementation agent.

Codex is the independent reviewer/debugger.

---

# 40. Final Architectural Principle

Aurora should never depend on the assumption that:

```text
"this YouTube client works forever."
```

Instead:

```text
Provider changes
      ↓
Adapter changes
      ↓
Strategy changes
      ↓
Transport changes
```

while:

```text
React Native
     ↓
Aurora Engine
     ↓
Media3
```

remains stable.

The long-term product is therefore not an InnerTube clone.

It is:

> **A resilient, provider-agnostic music playback engine capable of adapting to changing provider protocols and transport mechanisms without forcing the application layer to change.**

This is the architectural boundary that should guide all future implementation decisions.
