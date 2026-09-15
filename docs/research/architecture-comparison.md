# Open-Source Music Client Architecture Comparison

This document provides an in-depth architectural comparative analysis of key open-source YouTube Music and media streaming projects:
1. **ViMusic** (`vfsfitvnm/ViMusic`)
2. **InnerTune** (`z-huang/InnerTune`)
3. **Vivi Music** (`vivizzz007/vivi-music`)
4. **OuterTune** (`OuterTune/OuterTune`)
5. **Zemer** (`ZemerTeam/zemer-app`)
6. **YouTube.js / youtubei.js** (`LuanRT/YouTube.js`)
7. **NewPipe & NewPipeExtractor** (`TeamNewPipe/NewPipeExtractor` & `TeamNewPipe/NewPipe`)

---

## 1. High-Level Project Profiles

| Project | Primary Language | Target Platform | Architecture Style | Active Status (as of Sep 2026) | Primary License | Key Dependency Highlights |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **ViMusic** | Kotlin (100%) | Android (Jetpack Compose) | Monolith + custom `innertube` subproject | Archived (Pushed July 2024) | GPL-3.0 | Media3 1.1+, Ktor (CIO/OkHttp), Room, Compose |
| **InnerTune** | Kotlin (95%), Java (5%) | Android (Material 3 Compose) | Multi-module (`app`, `innertube`, `kizzy`, `kugou`, `lrclib`) | Slow Maintenance (Branch `dev`, pushed Nov 2025) | GPL-3.0 | Media3 1.4+, Ktor, Room, Hilt |
| **Vivi Music** | Kotlin (96%), Java (4%) | Android (Expressive M3 Compose) | Multi-module + `innertubex` + NewPipeExtractor | **Very Active** (Pushed Sep 2026) | GPL-3.0 | Media3 1.7.1, Ktor 3.4, OkHttp, NewPipeExtractor v0.26.1, Hilt |
| **OuterTune** | Kotlin (98%), Java (2%) | Android (Material 3 Compose) | Multi-module (`app`, `material-color-utilities`) | **Active** (Branch `lite`, pushed Sep 2026) | GPL-3.0 | Media3 1.5+, Ktor, Room, Hilt, Local File Scanner |
| **Zemer** | Kotlin (95%), Java (5%) | Android (Material 3 Compose) | Monolith + in-tree `:innertube` | **Very Active** (Pushed Sep 2026) | GPL-3.0 | Media3 1.8.0, Ktor 3.3.3, OkHttp, ResilientDns, QuickJS |
| **YouTube.js** | TypeScript (100%) | Node.js, Deno, Bun, Browsers, React Native | Clean Modular TypeScript Library | **Very Active** (Pushed Sep 2026) | MIT | Native `fetch`, JSEngine VM, Custom Protobuf, OAuth2 |
| **NewPipeExtractor** | Java (98%), Kotlin (2%) | Android / JVM Shared Library | Layered Service/Extractor Pattern | **Very Active** (Pushed Sep 2026) | GPL-3.0 | NanoJSON, Jsoup, Rhino/QuickJS, OkHttp |

---

## 2. Deep Dive: Repository & Module Structures

### 2.1. ViMusic (`vfsfitvnm/ViMusic`)
- **Modules**:
  - `:app`: Android application container hosting Jetpack Compose UI, Room DB, ExoPlayer service, and navigation.
  - `:innertube`: Standalone Kotlin JVM module containing Ktor-based models and HTTP calls to `music.youtube.com/youtubei/v1/*`.
  - `:ktor-client-brotli`: Custom Brotli decompression engine for Ktor.
  - `:kugou`: Client for Chinese lyrics provider Kugou.
- **Architectural Strength**: Clean separation between the UI and the `:innertube` network module.
- **Architectural Weakness**: UI and playback layers are tightly coupled to Room database entities and Jetpack Compose state primitives. Playback logic uses `runBlocking(Dispatchers.IO)` directly within `ResolvingDataSource` callbacks.

### 2.2. InnerTune (`z-huang/InnerTune`)
- **Modules**:
  - `:app`: Main Android application.
  - `:innertube`: Fork of ViMusic's `innertube` library, modernized with improved browse/search parsers.
  - `:lrclib`: Dedicated synced-lyrics provider client.
  - `:kizzy`: Discord Rich Presence integration via local WebSocket/IPC.
  - `:material-color-utilities`: Dynamic Material You palette derivation.
- **Architectural Evolution**: InnerTune separated playback queue management into explicit strategy classes (`ListQueue`, `YouTubeQueue`, `YouTubeAlbumRadio`, `LocalAlbumRadio`). However, stream resolution remained embedded inside `MusicService.kt`.

### 2.3. Vivi Music (`vivizzz007/vivi-music`)
- **Modules**:
  - `:app`: Core app featuring advanced audio pipelines, equalizer, AI integrations (OpenRouter/Mistral translation for lyrics), and JioSaavn fallback.
  - `:innertube`: In-tree YouTube Music client.
  - `innertubex` (external artifact `com.github.MetrolistGroup.innertubex:innertubex-android`): Advanced stream extraction engine with bundled cipher resolution.
  - `NewPipeExtractor` (`com.github.TeamNewPipe:NewPipeExtractor`): Fallback extraction engine.
- **Architectural Highlights**:
  - Multi-provider fallback: If JioSaavn streaming is enabled, it searches and streams 320kbps AAC directly, wrapping it inside InnerTube data structures.
  - Dedicated `StreamUrlCache.kt` with generation tracking to prevent race conditions during playback invalidations.
  - Separation of metadata fetching (using authenticated `WEB_REMIX` client to preserve YouTube history) and streaming URL resolution (using `ANDROID_VR` or `VISIONOS`).

### 2.4. Zemer (`ZemerTeam/zemer-app`)
- **Modules**:
  - `:app`: Android client.
  - `:innertube`: High-resilience InnerTube engine.
- **Architectural Highlights**:
  - **Fallback Ladder**: Implements an explicit prioritized client fallback sequence (`VISIONOS` -> `VISIONOS_0_1` -> `WEB_CREATOR` -> `TVHTML5_SIMPLY`).
  - **Self-Healing Cipher**: When stream validation fails on a cipher-based client, it triggers `CipherDeobfuscator.onStreamRejected()` to invalidate stale player scripts and refresh WebView/QuickJS extraction asynchronously without crashing or interrupting playback.
  - **Adaptive Video Rung Ladder**: Resolves audio and all video quality rungs in a single pass to allow instantaneous quality switching via local MediaItem replacements without additional network round-trips.

### 2.5. YouTube.js / youtubei.js (`LuanRT/YouTube.js`)
- **Modules**:
  - `src/core`: Session manager, OAuth2, Actions engine, and Player script parser.
  - `src/parser`: Universal parser generating strongly typed ASTs from raw InnerTube JSON renderers.
  - `src/utils`: HTTP abstraction, Protobuf serialization, CPN generators, and cryptographic helpers.
- **Architectural Highlights**:
  - Pure platform-agnostic TypeScript design running seamlessly across Node, Deno, Bun, Browser, and React Native (via Hermes/JavaScriptCore).
  - Strongly typed client enumeration (`WEB`, `WEB_REMIX`, `IOS`, `ANDROID`, `VISIONOS`, `TVHTML5`, etc.).
  - Embedded decipher parser extracting `n`-parameter transform and signature unscrambler from `base.js`.
  - PoToken generation via Botguard VM emulation (`bgUtils`).

### 2.6. NewPipeExtractor (`TeamNewPipe/NewPipeExtractor`)
- **Modules**:
  - Pure Java core library designed without Android SDK dependencies so it can run on CLI or server-side.
  - Service abstraction: `StreamingService` base class implemented by `YoutubeService`, `SoundcloudService`, `BandcampService`, and `MediaCCCService`.
- **Architectural Highlights**:
  - Strict separation of scraping/extraction logic from playback.
  - Uses `Localization` and `ContentCountry` parameters on all extraction requests.
  - Recent evolution: Adopted Apple `VISIONOS` client as the primary stream extractor to bypass YouTube's aggressive Android client bot detection.

---

## 3. Comprehensive Feature & Capability Matrix

| Feature | ViMusic | InnerTune | Vivi Music | OuterTune | Zemer | YouTube.js | NewPipeExtractor |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Catalog Search** | InnerTube JSON | InnerTube JSON | InnerTube + Saavn | InnerTube + Local | InnerTube (Kosher filtered) | InnerTube (Full AST) | Regex + JSON parser |
| **Stream Resolution** | `WEB_REMIX` only | `ANDROID_VR` / `WEB_REMIX` | Multi-Client + Saavn | Multi-Client | Fallback Ladder (`VISIONOS` first) | Configurable Client | `VISIONOS` + Web Meta |
| **`n`-parameter Transform** | ❌ None | ❌ None | ✅ QuickJS/Regex | ⚠️ Partial | ✅ QuickJS / Embedded | ✅ Full AST evaluation | ✅ Rhino / QuickJS |
| **PoToken / Botguard** | ❌ None | ❌ None | ✅ WebView generator | ⚠️ Basic | ✅ WebView generator | ✅ Node/JS VM | ⚠️ Work-in-progress |
| **Pre-Playback Validation** | ❌ None | ❌ None | ⚠️ Minimal | ⚠️ Minimal | ✅ HTTP HEAD / Byte Range | ❌ Manual | ❌ Manual |
| **Stream Health Scoring** | ❌ None | ❌ None | ❌ None | ❌ None | ⚠️ Failure Blacklist | ❌ None | ❌ None |
| **URL Caching** | 2-item RingBuffer | 5-minute Map | 500-item Gen LRU | 500-item LRU | In-memory Cache | Pluggable `ICache` | None (caller handled) |
| **Audio Chunk Caching** | Media3 `SimpleCache` | Media3 `SimpleCache` | Media3 `SimpleCache` | Media3 `SimpleCache` | Media3 `SimpleCache` | None | None |
| **Playback Engine** | Media3 ExoPlayer | Media3 ExoPlayer | Media3 ExoPlayer | Media3 ExoPlayer | Media3 ExoPlayer | External (HTML5/mpv) | External (ExoPlayer) |
| **Prefetching** | Next track (naive) | Next track (naive) | Next track pre-resolve | Next track pre-resolve | Next track + Video ladder | None | None |
| **RN / Cross-Platform** | Android Only | Android Only | Android Only | Android Only | Android Only | Universal JS/TS | JVM Only |
| **Automated Tests** | Minimal Unit | Minimal Unit | UI/Widget tests | Basic Unit | Basic Unit | Comprehensive Test Suite | Extensive Live Tests |

---

## 4. Key Architectural Findings for AuroraMusicEngine

1. **Monolithic Player Trap**: ViMusic, InnerTune, and even Vivi Music tightly fuse stream resolution into the Android `MusicService` / `ResolvingDataSource` callback. When network resolution stalls or throws, the entire playback pipeline hangs or stutters.
2. **The Client Evolution**: YouTube has systematically blocked or restricted legacy clients:
   - `ANDROID` / `ANDROID_MUSIC`: 403s without PoToken; subject to bot-detection videos ("Content unavailable on this app").
   - `WEB_REMIX`: Throttled without `n`-transform; 403s on HEAD requests; requires PoToken for guest sessions.
   - `ANDROID_VR`: Historical sanctuary, now 403-walled on complete song downloads.
   - `VISIONOS`: Currently provides clean CDN streaming without signature ciphers or PoToken gates, but lacks YouTube Music browse metadata and history scrobbling.
3. **Dual-Client Architecture**: Modern engines MUST decouple **Metadata/Session Clients** (`WEB_REMIX` for account playlists, user library, and remote history tracking) from **Streaming Resolution Clients** (`VISIONOS`, `WEB_CREATOR`, `TVHTML5_SIMPLY`).
4. **Provider Isolation**: No project cleanly exposes a pluggable `MusicProvider` SPI that allows swapping YouTube Music for JioSaavn, Spotify metadata matching, Subsonic, or local storage without patching core playback logic.
