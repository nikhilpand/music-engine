package com.aurora.engine.provider.ytmusic.conformance

import com.aurora.engine.provider.ytmusic.config.ClientConfigEntry
import com.aurora.engine.provider.ytmusic.config.ClientConfigStore
import com.aurora.engine.provider.ytmusic.config.ClientLadder
import com.aurora.engine.provider.ytmusic.recovery.PlaybackErrorType
import com.aurora.engine.provider.ytmusic.recovery.RecoveryManager
import com.aurora.engine.provider.ytmusic.recovery.RecoveryStrategy
import com.aurora.engine.provider.ytmusic.resolver.StreamUrlCache
import com.aurora.engine.provider.ytmusic.transport.NetworkType
import com.aurora.engine.provider.ytmusic.transport.TransportSelector
import com.aurora.engine.provider.ytmusic.transport.TransportType
import org.junit.jupiter.api.Test
import com.google.common.truth.Truth.assertThat

/**
 * Playback Conformance Harness
 *
 * Validates the 7 mandated playback scenarios end-to-end using the
 * real component implementations (no mocking of internal state).
 *
 * These scenarios are:
 * 1. 403 Recovery with client rotation
 * 2. Stream URL Expiry with position preservation
 * 3. Network Switch (WiFi → Cellular) with cache invalidation
 * 4. Mid-stream Seek with byte offset checkpoint
 * 5. Cipher Failure with fallback to non-cipher client
 * 6. SABR Protocol Failure with transport rotation
 * 7. Full Recovery Exhaustion resulting in GIVE_UP
 */
class PlaybackConformanceHarnessTest {

    private val seedJson = """
    [
        {"clientName":"ANDROID_MUSIC","clientVersion":"6.42","userAgent":"AM","supportsSabr":true,"supportsMetadata":true,"priority":100,"metadataPriority":80,"enabled":true},
        {"clientName":"VISIONOS","clientVersion":"0.1","userAgent":"VOS","supportsSabr":false,"supportsMetadata":false,"priority":85,"enabled":true},
        {"clientName":"WEB_REMIX","clientVersion":"1.0","userAgent":"WR","requiresCipher":true,"supportsMetadata":true,"priority":80,"metadataPriority":100,"enabled":true},
        {"clientName":"TVHTML5","clientVersion":"7.0","userAgent":"TV","supportsSabr":false,"supportsMetadata":false,"priority":70,"enabled":true}
    ]
    """.trimIndent()

    // ── Scenario 1: 403 Recovery with Client Rotation ───────────────────────

    @Test
    fun `SCENARIO 1 - 403 recovery escalates through REAUTH then ROTATE_CLIENT`() {
        val store = ClientConfigStore(seedJson)
        val ladder = ClientLadder.forStreamResolution(store)
        val recovery = RecoveryManager()
        val cache = StreamUrlCache()

        // Simulate: playing track, checkpoint at 30s
        val mediaId = "dQw4w9WgXcQ"
        recovery.checkpoint(mediaId, 30_000, 500_000, 180_000,
            TransportType.PROGRESSIVE, "ANDROID_MUSIC")

        // Simulate: first 403 → REAUTH
        val action1 = recovery.onError(mediaId, PlaybackErrorType.HTTP_403,
            "ANDROID_MUSIC", TransportType.PROGRESSIVE, ladder.size, 0)
        assertThat(action1.strategy).isEqualTo(RecoveryStrategy.REAUTH)
        assertThat(action1.resumePositionMs).isEqualTo(30_000)

        // Cache should be invalidated on 403
        cache.invalidate(mediaId)
        assertThat(cache.get(mediaId)).isNull()

        // Simulate: second 403 → ROTATE_CLIENT
        val action2 = recovery.onError(mediaId, PlaybackErrorType.HTTP_403,
            "ANDROID_MUSIC", TransportType.PROGRESSIVE, ladder.size, 0)
        assertThat(action2.strategy).isEqualTo(RecoveryStrategy.ROTATE_CLIENT)

        // Ladder should provide next client
        ladder.recordFailure("ANDROID_MUSIC")
        ladder.recordFailure("ANDROID_MUSIC")
        ladder.recordFailure("ANDROID_MUSIC")
        val nextClient = ladder.nextClient()
        assertThat(nextClient).isNotNull()
        assertThat(nextClient!!.clientName).isNotEqualTo("ANDROID_MUSIC")
    }

    // ── Scenario 2: Stream URL Expiry with Position Preservation ────────────

    @Test
    fun `SCENARIO 2 - expired stream URL triggers REAUTH with preserved position`() {
        val recovery = RecoveryManager()
        val cache = StreamUrlCache()

        val mediaId = "track_expire_test"

        // Cache a URL that will expire
        val gen = cache.startResolution(mediaId)
        cache.completeResolution(mediaId, gen, "https://expired.url",
            expiresAtMs = System.currentTimeMillis() - 1, // already expired
            clientName = "VISIONOS", transportType = StreamUrlCache.TransportType.PROGRESSIVE)

        // Verify it's expired
        assertThat(cache.get(mediaId)).isNull()

        // Checkpoint was at 60s
        recovery.checkpoint(mediaId, 60_000, 1_200_000, 240_000,
            TransportType.PROGRESSIVE, "VISIONOS")

        val action = recovery.onError(mediaId, PlaybackErrorType.STREAM_EXPIRED,
            "VISIONOS", TransportType.PROGRESSIVE, 4, 1)

        assertThat(action.strategy).isEqualTo(RecoveryStrategy.REAUTH)
        assertThat(action.resumePositionMs).isEqualTo(60_000)
        assertThat(action.resumeByteOffset).isEqualTo(1_200_000)
    }

    // ── Scenario 3: Network Switch ──────────────────────────────────────────

    @Test
    fun `SCENARIO 3 - network switch invalidates cache and triggers re-resolve`() {
        val cache = StreamUrlCache()
        val selector = TransportSelector()
        val recovery = RecoveryManager()

        val mediaId = "net_switch_test"

        // Playing on WiFi
        selector.onNetworkTypeChanged(NetworkType.WIFI)
        val gen = cache.startResolution(mediaId)
        cache.completeResolution(mediaId, gen, "https://wifi.stream",
            expiresAtMs = System.currentTimeMillis() + 300_000,
            clientName = "VISIONOS", transportType = StreamUrlCache.TransportType.PROGRESSIVE)

        assertThat(cache.get(mediaId)).isNotNull()

        // Network changes to cellular
        cache.onNetworkChanged()
        selector.onNetworkTypeChanged(NetworkType.METERED)

        // Cached entry is now stale
        assertThat(cache.get(mediaId)).isNull()

        // Recovery action
        recovery.checkpoint(mediaId, 45_000, 800_000, 200_000,
            TransportType.PROGRESSIVE, "VISIONOS")
        val action = recovery.onError(mediaId, PlaybackErrorType.NETWORK_CHANGE,
            "VISIONOS", TransportType.PROGRESSIVE, 4, 1)

        assertThat(action.strategy).isEqualTo(RecoveryStrategy.REAUTH)
        assertThat(action.backoffMs).isGreaterThan(0L) // stabilization delay
        assertThat(action.resumePositionMs).isEqualTo(45_000)
    }

    // ── Scenario 4: Mid-stream Seek with Byte Offset ────────────────────────

    @Test
    fun `SCENARIO 4 - seek checkpoint preserves byte offset for range request`() {
        val recovery = RecoveryManager()
        val mediaId = "seek_test"

        // Initial checkpoint at 10s
        recovery.checkpoint(mediaId, 10_000, 200_000, 300_000,
            TransportType.PROGRESSIVE, "VISIONOS")

        // User seeks to 120s — new checkpoint
        recovery.checkpoint(mediaId, 120_000, 2_400_000, 300_000,
            TransportType.PROGRESSIVE, "VISIONOS")

        // Error occurs after seek
        val action = recovery.onError(mediaId, PlaybackErrorType.HTTP_403,
            "VISIONOS", TransportType.PROGRESSIVE, 4, 1)

        // Recovery should resume from the SEEK position, not the initial one
        assertThat(action.resumePositionMs).isEqualTo(120_000)
        assertThat(action.resumeByteOffset).isEqualTo(2_400_000)
    }

    // ── Scenario 5: Cipher Failure ──────────────────────────────────────────

    @Test
    fun `SCENARIO 5 - cipher failure rotates to non-cipher client`() {
        val store = ClientConfigStore(seedJson)
        val ladder = ClientLadder.forStreamResolution(store)
        val recovery = RecoveryManager()

        val mediaId = "cipher_fail_test"

        // WEB_REMIX (requiresCipher=true) is at index 2
        val action = recovery.onError(mediaId, PlaybackErrorType.CIPHER_FAILURE,
            "WEB_REMIX", TransportType.PROGRESSIVE, ladder.size, 2)

        assertThat(action.strategy).isEqualTo(RecoveryStrategy.ROTATE_CLIENT)

        // Next client after quarantining WEB_REMIX should not require cipher
        ladder.recordFailure("WEB_REMIX")
        ladder.recordFailure("WEB_REMIX")
        ladder.recordFailure("WEB_REMIX")

        val nextClient = ladder.nextClient { !it.requiresCipher }
        assertThat(nextClient).isNotNull()
        assertThat(nextClient!!.requiresCipher).isFalse()
    }

    // ── Scenario 6: SABR Protocol Failure ───────────────────────────────────

    @Test
    fun `SCENARIO 6 - SABR protocol error falls back to Progressive`() {
        val selector = TransportSelector()
        val recovery = RecoveryManager()

        val mediaId = "sabr_fail_test"
        selector.onNetworkTypeChanged(NetworkType.WIFI)

        recovery.checkpoint(mediaId, 20_000, 400_000, 200_000,
            TransportType.SABR, "ANDROID_MUSIC")

        val action = recovery.onError(mediaId, PlaybackErrorType.SABR_PROTOCOL_ERROR,
            "ANDROID_MUSIC", TransportType.SABR, 4, 0)

        assertThat(action.strategy).isEqualTo(RecoveryStrategy.ROTATE_TRANSPORT)

        // Transport selector should be informed
        selector.recordTransportResult(TransportType.SABR, success = false)
        assertThat(selector.isSabrHealthy()).isFalse()

        // Next selection for same client should use Progressive
        val decision = selector.select(
            ClientConfigEntry("ANDROID_MUSIC", "6.42", "AM", supportsSabr = true, priority = 100)
        )
        assertThat(decision.type).isEqualTo(TransportType.PROGRESSIVE)
    }

    // ── Scenario 7: Full Recovery Exhaustion ────────────────────────────────

    @Test
    fun `SCENARIO 7 - exhausted recovery attempts result in GIVE_UP`() {
        val recovery = RecoveryManager(maxRecoveryAttempts = 3)
        val mediaId = "exhaustion_test"

        recovery.checkpoint(mediaId, 90_000, 1_800_000, 240_000,
            TransportType.PROGRESSIVE, "TVHTML5")

        // Exhaust all recovery attempts
        var lastAction = recovery.onError(mediaId, PlaybackErrorType.HTTP_403,
            "TVHTML5", TransportType.PROGRESSIVE, 1, 0)

        while (lastAction.strategy != RecoveryStrategy.GIVE_UP) {
            lastAction = recovery.onError(mediaId, PlaybackErrorType.HTTP_403,
                "TVHTML5", TransportType.PROGRESSIVE, 1, 0)
        }

        assertThat(lastAction.strategy).isEqualTo(RecoveryStrategy.GIVE_UP)
        assertThat(lastAction.resumePositionMs).isEqualTo(90_000)
        assertThat(lastAction.reason).contains("Exhausted")
    }
}
