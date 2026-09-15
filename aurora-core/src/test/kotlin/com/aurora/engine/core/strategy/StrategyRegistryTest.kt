package com.aurora.engine.core.strategy

import com.aurora.engine.core.model.AudioCodec
import com.aurora.engine.core.model.QualityProfile
import com.aurora.engine.core.model.Track
import com.aurora.engine.core.provider.ResolutionContext
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class StrategyRegistryTest {

    private lateinit var registry: StrategyRegistry

    private val sampleTrack = Track(
        id = "track_123",
        providerId = "ytmusic",
        title = "Test Song",
        artists = listOf(com.aurora.engine.core.model.ArtistRef("art_1", "Test Artist")),
        durationMs = 210_000L
    )

    private class MockStrategy(
        override val id: String,
        override val priority: Int,
        override val capabilities: StrategyCapabilities
    ) : PlaybackStrategy

    @BeforeEach
    fun setUp() {
        registry = StrategyRegistry()
    }

    @Test
    @DisplayName("Registering and querying strategies")
    fun testRegistration() {
        val s1 = MockStrategy("s1", 10, StrategyCapabilities())
        val s2 = MockStrategy("s2", 20, StrategyCapabilities())

        registry.register(s1)
        registry.register(s2)

        assertThat(registry.getAllStrategies()).containsExactly(s1, s2)
        assertThat(registry.getStrategy("s1")).isEqualTo(s1)

        registry.unregister("s1")
        assertThat(registry.getStrategy("s1")).isNull()
    }

    @Test
    @DisplayName("Filtering by target quality profile capability")
    fun testCapabilityQualityFiltering() {
        val lowOnlyStrategy = MockStrategy(
            id = "low_only",
            priority = 100,
            capabilities = StrategyCapabilities(
                supportedQualities = setOf(QualityProfile.LOW)
            )
        )
        val highStrategy = MockStrategy(
            id = "high_capable",
            priority = 50,
            capabilities = StrategyCapabilities(
                supportedQualities = setOf(QualityProfile.LOW, QualityProfile.MEDIUM, QualityProfile.HIGH)
            )
        )

        registry.register(lowOnlyStrategy)
        registry.register(highStrategy)

        val highContext = ResolutionContext(
            track = sampleTrack,
            targetQuality = QualityProfile.HIGH
        )

        val eligibleForHigh = registry.getEligibleStrategies(highContext)
        // low_only must be excluded because it cannot provide HIGH quality
        assertThat(eligibleForHigh).containsExactly(highStrategy)
    }

    @Test
    @DisplayName("Filtering by preferred audio codecs")
    fun testCapabilityCodecFiltering() {
        val opusStrategy = MockStrategy(
            id = "opus_only",
            priority = 10,
            capabilities = StrategyCapabilities(
                supportedCodecs = setOf(AudioCodec.OPUS)
            )
        )
        val aacStrategy = MockStrategy(
            id = "aac_only",
            priority = 10,
            capabilities = StrategyCapabilities(
                supportedCodecs = setOf(AudioCodec.AAC)
            )
        )

        registry.register(opusStrategy)
        registry.register(aacStrategy)

        val context = ResolutionContext(
            track = sampleTrack,
            preferredCodecs = listOf(AudioCodec.AAC)
        )

        val eligible = registry.getEligibleStrategies(context)
        assertThat(eligible).containsExactly(aacStrategy)
    }

    @Test
    @DisplayName("Circuit breaker excludes tripped strategies from eligibility")
    fun testCircuitBreakerExclusion() {
        val s1 = MockStrategy("s1", 100, StrategyCapabilities())
        val s2 = MockStrategy("s2", 50, StrategyCapabilities())

        registry.register(s1)
        registry.register(s2)

        val context = ResolutionContext(track = sampleTrack)

        // Trip s1's circuit breaker
        val cb1 = registry.getCircuitBreaker("s1")!!
        cb1.recordFailure(FailureType.HTTP_403_PROVIDER_REJECTION)
        cb1.recordFailure(FailureType.HTTP_403_PROVIDER_REJECTION)
        assertThat(cb1.currentState).isEqualTo(CircuitState.OPEN)

        val eligible = registry.getEligibleStrategies(context)
        // s1 must be excluded since its circuit breaker is open
        assertThat(eligible).containsExactly(s2)
    }

    @Test
    @DisplayName("Dynamic ranking: strategy with higher health ranks above degraded strategy with higher base priority")
    fun testDynamicRanking() {
        val s1 = MockStrategy("s1_high_base", priority = 100, capabilities = StrategyCapabilities())
        val s2 = MockStrategy("s2_low_base", priority = 60, capabilities = StrategyCapabilities())

        registry.register(s1)
        registry.register(s2)

        // S1 experiences multiple failures, degrading its health
        registry.reportFailure("s1_high_base", FailureType.HTTP_403_PROVIDER_REJECTION, latencyMs = 200L)
        registry.reportFailure("s1_high_base", FailureType.TIMEOUT, latencyMs = 3000L)

        // S2 experiences consistent successes
        registry.reportSuccess("s2_low_base", latencyMs = 80L)
        registry.reportSuccess("s2_low_base", latencyMs = 90L)

        val context = ResolutionContext(track = sampleTrack)
        val ranked = registry.getEligibleStrategies(context)

        // S2 should leapfrog S1 due to healthy score
        assertThat(ranked.first().id).isEqualTo("s2_low_base")
    }
}
