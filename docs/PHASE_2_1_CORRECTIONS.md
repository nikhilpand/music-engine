# AuroraMusicEngine — Phase 2.1 Provider Architecture Corrections Report

**Document Version:** 1.0.0  
**Phase:** 2.1 — Provider Architecture Corrections  
**Modules Affected:** `aurora-core`, `aurora-provider-ytmusic`  
**Test Suite Status:** 63/63 Unit Tests Passing (100% Green)

---

## 1. Executive Summary

Phase 2.1 introduces critical architectural corrections to `aurora-core` and `aurora-provider-ytmusic` before progressing to Phase 3 (Media3 / Transport). 

These corrections establish:
1. **Clear Failure Disambiguation**: Differentiating account-level authentication (`AUTHENTICATION_REQUIRED`), client-level challenges (`BOT_DETECTION`), provider rejection (`PROVIDER_REJECTION`), and network connectivity (`NETWORK_FAILURE`).
2. **Strategy Health Integrity**: Device-wide network outages and account authentication blocks no longer poison strategy health scores or trip circuit breakers.
3. **Provider-Local Strategy Configuration**: Strategy definitions (`ANDROID_MUSIC`, `WEB_REMIX`, `VISIONOS`, `TVHTML5`) remain strictly isolated within `aurora-provider-ytmusic`.
4. **Replaceable & Dynamic Strategy Lifecycle**: Enabling/disabling strategies, runtime version and priority updates, temporary retirement, health-based demotion, and manual/automatic rehabilitation without core resolver modification.
5. **Intermediate Candidate Representation (`ResolvedFormatCandidate`)**: Retaining full format and cipher metadata before mapping to generic `PlaybackSource` objects.
6. **Explicit Transformation & Token Gating**: Explicit `TRANSFORMATION_REQUIRED` and `TOKEN_FAILURE` classifications with safe fallback ladders, strictly preventing fake or unplayable stream candidate generation.
7. **Clean Candidate Ranking**: Decoupling provider resolution ranking from transport layer consumption.

---

## 2. Key Architectural Corrections Implemented

### 2.1. Failure Category Decoupling & Non-Poisoning Health Semantics
In `aurora-core`:
- **`ErrorCategory`** was expanded with distinct categories:
  - `AUTHENTICATION_REQUIRED`: User login required for track access.
  - `PROVIDER_REJECTION`: HTTP 403 or explicit refusal not tied to bot challenges.
  - `BOT_DETECTION`: Cloudflare/Google captcha or bot detection.
  - `RATE_LIMITED`: HTTP 429 rate limit exceeded.
  - `NETWORK_FAILURE`: Socket, DNS, or transport connection failures.
  - `TIMEOUT`: Request latency exceeding execution SLA.
  - `UNPLAYABLE`: Media unavailable in catalog or geo-region.
  - `CONTENT_RESTRICTION`: Age gate or legal block.
  - `INVALID_RESPONSE`: Malformed or unexpected payload.
  - `TRANSFORMATION_REQUIRED`: Required signature cipher or n-parameter transformation unavailable.
  - `TOKEN_FAILURE`: Required integrity or visitor token acquisition failed.

- **`FailureType` in `HealthTracker`**:
  - Added `penalizesStrategy: Boolean` property.
  - Non-strategy-poisoning failures (`AUTHENTICATION_REQUIRED`, `NETWORK_FAILURE`, `UNPLAYABLE`, `CONTENT_RESTRICTION`) have `severityWeight = 0.0` and `penalizesStrategy = false`.
  - In `HealthTracker.recordFailure()`: When `!failureType.penalizesStrategy`, requests and failure counts are tracked for metrics, but `consecutiveFailures` is **not** incremented and no score penalty is applied.
  - In `CircuitBreaker.recordFailure()`: When `!failureType.penalizesStrategy`, the circuit breaker ignores the event, preventing device offline events or login gates from tripping the strategy.

### 2.2. Replaceable & Dynamic Strategy Architecture
In `aurora-core` (`PlaybackStrategy.kt`, `StrategyRegistry.kt`):
- `PlaybackStrategy` now includes dynamic lifecycle properties:
  - `val isEnabled: Boolean get() = true`
  - `val isRetired: Boolean get() = false`
  - `val version: String get() = "1.0.0"`
- `StrategyRegistry` supports runtime mutations:
  - `register(strategy)`: Replaces existing instances by ID, enabling real-time priority, version, or capability adjustments.
  - `updateStrategy(strategy)`: Replaces strategy configuration.
  - `setStrategyEnabled(strategyId, enabled)`: Dynamically enables or disables strategies.
  - `retireStrategy(strategyId)`: Permanently or temporarily retires strategies.
  - `rehabilitate(strategyId)`: Instantly resets circuit breaker to `CLOSED` and restores baseline health score to 100.0.
  - `getEligibleStrategies(context)`: Automatically filters out disabled or retired strategies before running capability and circuit breaker checks.

### 2.3. Intermediate Provider Format Representation (`ResolvedFormatCandidate`)
In `aurora-provider-ytmusic`:
- Introduced `ResolvedFormatCandidate` and auxiliary models `CipherInfo` and `TransportHints`:
  - Preserves raw format ID, resolved URL, MIME type, codec, bitrate, sample rate, channel count, duration, expiry, cipher info, n-transform requirements, token requirements, transport hints (SABR endpoints, client context JSON, custom headers), and raw provider metadata.
  - Candidate ranking (`rankCandidates`) sorts candidates based on preferred codecs and quality bitrate proximity before converting to `PlaybackSource`.
  - `toPlaybackSources(trackId)` maps candidates into generic `PlaybackSource.Progressive` and `PlaybackSource.Sabr` instances.

### 2.4. Explicit Transformation & Token Gating
- `PlayerTransformProvider.canTransform`:
  - `PassThroughPlayerTransformProvider` explicitly returns `canTransform = false` and throws `UnsupportedOperationException` if transformation is invoked.
  - If a strategy returns formats requiring cipher deciphering or n-parameter transformation and the active transform provider cannot perform the transformation, `YouTubeMusicProvider` rejects the candidate and fails with `ErrorCategory.TRANSFORMATION_REQUIRED` (`canFallback = true`), allowing fallback to an eligible strategy without cipher requirements (e.g. `ANDROID_MUSIC` or `VISIONOS`).
  - No fake or broken stream URLs are ever emitted.
- Explicit Token Handling:
  - When a strategy specifies `requiresPoToken = true` (e.g., `WEB_REMIX`), `YouTubeMusicProvider` checks `tokenProvider.getPoToken(videoId)`.
  - If token acquisition fails or yields null, the strategy immediately fails with `ErrorCategory.TOKEN_FAILURE` and `FailureType.TOKEN_FAILURE`, cleanly falling back to strategies that do not require tokens.

---

## 3. Module & Test Verification Results

### 3.1. Verification Command
```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat test
```

### 3.2. Test Suite Breakdown
| Module | Total Tests | Passed | Failed | Skipped | Status |
| :--- | :---: | :---: | :---: | :---: | :---: |
| `:aurora-core` | 37 | 37 | 0 | 0 | **GREEN** |
| `:aurora-provider-ytmusic` | 26 | 26 | 0 | 0 | **GREEN** |
| **Total** | **63** | **63** | **0** | **0** | **100% PASS** |

### 3.3. Phase 2.1 Regression Tests Added
A dedicated regression suite (`Phase21CorrectionsTest.kt`) was implemented covering all 11 required scenarios:
1. `testLoginRequiredDoesNotPoisonStrategy`: Verifies that `LOGIN_REQUIRED` produces `AUTHENTICATION_REQUIRED`, preserves health score at 100.0, and keeps circuit breaker `CLOSED`.
2. `testNetworkFailureDoesNotPoisonStrategy`: Verifies that network dropouts produce `NETWORK_FAILURE`, preserve health score at 100.0, and do not trip circuit breaker.
3. `testTransformationRequiredExplicitFailureAndFallback`: Verifies that `PassThroughPlayerTransformProvider` rejects cipher streams with `TRANSFORMATION_REQUIRED` and falls back to clean stream strategies.
4. `testTokenRequiredFailureTriggersFallback`: Verifies that missing `poToken` produces explicit `TOKEN_FAILURE` and falls back to strategies not requiring tokens.
5. `testDisabledStrategyExclusion`: Verifies runtime disabling excludes strategies from eligible resolution lists.
6. `testUnhealthyStrategyDemotion`: Verifies dynamic composite scoring demotes degraded high-priority strategies below healthy lower-priority strategies.
7. `testStrategyRehabilitation`: Verifies `rehabilitate()` restores circuit breaker to `CLOSED` and health score to 100.0.
8. `testUnknownProviderFormatHandling`: Verifies resilient parsing of non-standard codecs and mimeTypes without crashes.
9. `testMalformedPlayerResponse`: Verifies malformed JSON responses are handled gracefully without exceptions.
10. `testCandidateConversion`: Verifies `ResolvedFormatCandidate` faithfully preserves metadata and translates to `PlaybackSource.Progressive` and `PlaybackSource.Sabr`.
11. `testProviderSpecificStrategyIsolation`: Verifies core registry and resolver accept arbitrary provider strategies with zero YouTube-specific dependencies.

---

## 4. Scope Compliance Check

- [x] Media3 playback **NOT** implemented.
- [x] SABR transport **NOT** implemented.
- [x] React Native bridge **NOT** implemented.
- [x] Downloads **NOT** implemented.
- [x] Cache layer **NOT** implemented.
- [x] All Phase 1 and Phase 2 existing tests remain green.
- [x] Awaiting user review before proceeding.
