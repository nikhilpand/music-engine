# AuroraMusicEngine

A high-performance, modular audio playback engine engineered for adaptive streaming, provider federation, and resilient playback architecture on Android and JVM.

---

## Architecture Overview

AuroraMusicEngine follows a strict unidirectional dependency architecture with separation of concerns between discovery, transport, buffering, and audio rendering:

```
PlaybackProvider  (e.g., aurora-provider-ytmusic)
       │
       ▼
PlaybackSource    (e.g., Progressive, Sabr, HLS)
       │
       ▼
PlaybackTransport (e.g., aurora-transport-progressive, aurora-transport-sabr)
       │
       ▼
PlaybackSession   (State machine, demand pacing, recovery)
       │
       ▼
Media3 / ExoPlayer (Audio rendering, audio focus, platform sync)
```

### Modules

| Module | Platform | Description |
|---|---|---|
| **`aurora-core`** | Pure Kotlin (JVM) | Core domain entities, contracts (`PlaybackProvider`, `PlaybackTransport`, `PlaybackSession`), state machine orchestrator, circuit breaker, health tracker, and diagnostics sanitizer. Zero Android dependencies. |
| **`aurora-player-android`** | Android Library | Media3/ExoPlayer integration layer, audio focus manager, player state synchronizer, network connectivity observer, and playback recovery coordinator. |
| **`aurora-provider-ytmusic`** | Kotlin (JVM) | YouTube Music provider catalog and player response parsers, streaming format resolvers, and secure playback token management. |
| **`aurora-transport-progressive`** | Android Library | Provider-neutral HTTP progressive transport with range-request handling, expiration tracking, and custom Media3 DataSource. |
| **`aurora-transport-sabr`** | Android Library | Protobuf-over-HTTP/2 Server-Adaptive Bitrate (SABR) transport with Universal Media Protocol (UMP) framing, stateless protobuf decoding, disjoint coverage-range media buffer, and demand-paced streaming. |

---

## Key Features

- **Protocol Isolation**: Transports operate purely on generic byte/frame streams without leaking provider details or proprietary tokens into `aurora-core`.
- **Disjoint Range Buffering**: `SabrMediaBuffer` maintains coverage interval sets `[start, end)` to prevent duplicate downloads and support instant seeking into cached regions.
- **Failover & Recovery**: Built-in circuit breakers, health trackers, and `PlaybackRecoveryCoordinator` enable dynamic route invalidation and multi-tier transport fallback.
- **Privacy & Security**: Zero secret logging — signed endpoints, visitor cookies, PoTokens, and authentication headers are redacted automatically at the diagnostics boundary.

---

## Building & Testing

### Prerequisites
- JDK 17+
- Android SDK (API 34+, Build Tools 34.0.0)

### Run Unit Tests
```bash
./gradlew test
```

### Build All Modules
```bash
./gradlew build
```

---

## Documentation

Comprehensive phase reports and technical specifications are available in the [`docs/`](docs/) directory:
- [Technical Specification](AuroraMusicEngine%20%E2%80%94%20Music%20Engine%20V1.2%20Technical%20Specification.md)
- [Phase 4 SABR Pre-implementation Audit](docs/phase4/PHASE_4_PREIMPLEMENTATION_AUDIT.md)
- [Phase 4 SABR Architecture](docs/phase4/PHASE_4_SABR_ARCHITECTURE.md)
- [Phase 4 Implementation Report](docs/phase4/PHASE_4_IMPLEMENTATION_REPORT.md)
