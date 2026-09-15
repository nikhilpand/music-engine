package com.aurora.engine.provider.ytmusic

import com.aurora.engine.core.model.AudioCodec
import com.aurora.engine.core.model.AudioContainer
import com.aurora.engine.core.model.AudioFormat
import com.aurora.engine.core.model.ErrorCategory
import com.aurora.engine.core.model.PlaybackSource
import com.aurora.engine.core.model.QualityProfile
import com.aurora.engine.core.model.Track
import com.aurora.engine.core.provider.ResolutionContext
import com.aurora.engine.core.provider.ResolutionResult
import com.aurora.engine.core.strategy.CircuitState
import com.aurora.engine.core.strategy.FailureType
import com.aurora.engine.core.strategy.PlaybackStrategy
import com.aurora.engine.core.strategy.StrategyCapabilities
import com.aurora.engine.core.strategy.StrategyRegistry
import com.aurora.engine.provider.ytmusic.model.CipherInfo
import com.aurora.engine.provider.ytmusic.model.ResolvedFormatCandidate
import com.aurora.engine.provider.ytmusic.model.TransportHints
import com.aurora.engine.provider.ytmusic.parser.PlayerResponseParser
import com.aurora.engine.provider.ytmusic.session.InnerTubeSession
import com.aurora.engine.provider.ytmusic.strategy.YouTubePlaybackStrategy
import com.aurora.engine.provider.ytmusic.token.DefaultPlaybackTokenProvider
import com.aurora.engine.provider.ytmusic.token.PlaybackTokenProvider
import com.aurora.engine.provider.ytmusic.transform.PassThroughPlayerTransformProvider
import com.aurora.engine.provider.ytmusic.transform.PlayerTransformProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.IOException

class Phase21CorrectionsTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var strategyRegistry: StrategyRegistry
    private lateinit var provider: YouTubeMusicProvider

    private val sampleTrack = Track(
        id = "test_vid_abc",
        providerId = "ytmusic",
        title = "Starboy",
        artists = listOf(com.aurora.engine.core.model.ArtistRef("art_1", "The Weeknd")),
        durationMs = 230_000L
    )

    @BeforeEach
    fun setUp() {
        mockServer = MockWebServer()
        mockServer.start()

        strategyRegistry = StrategyRegistry()
        val session = InnerTubeSession(
            baseUrl = mockServer.url("/").toString(),
            httpClient = OkHttpClient.Builder().build()
        )

        provider = YouTubeMusicProvider(
            session = session,
            strategyRegistry = strategyRegistry
        )
    }

    @AfterEach
    fun tearDown() {
        mockServer.shutdown()
    }

    @Test
    @DisplayName("1. LOGIN_REQUIRED != automatic strategy poisoning")
    fun testLoginRequiredDoesNotPoisonStrategy() = runBlocking {
        val loginRequiredJson = """
        {
            "playabilityStatus": {
                "status": "LOGIN_REQUIRED",
                "reason": "Sign in to confirm you are not a bot"
            }
        }
        """.trimIndent()

        mockServer.enqueue(MockResponse().setResponseCode(200).setBody(loginRequiredJson))

        val context = ResolutionContext(track = sampleTrack)
        val result = provider.resolvePlayback(context)

        assertThat(result).isInstanceOf(ResolutionResult.Failure::class.java)
        val failure = result as ResolutionResult.Failure
        assertThat(failure.error.category).isEqualTo(ErrorCategory.AUTHENTICATION_REQUIRED)

        // Strategy health must remain unpoisoned at baseline 100.0
        val strategyId = YouTubePlaybackStrategy.ANDROID_MUSIC_STRATEGY.id
        val healthScore = strategyRegistry.healthTracker.getScore(strategyId)
        assertThat(healthScore).isEqualTo(100.0)

        val snapshot = strategyRegistry.healthTracker.getSnapshot(strategyId)
        assertThat(snapshot.consecutiveFailures).isEqualTo(0)

        // Circuit breaker must remain CLOSED
        val cb = strategyRegistry.getCircuitBreaker(strategyId)
        assertThat(cb?.currentState).isEqualTo(CircuitState.CLOSED)
    }

    @Test
    @DisplayName("2. Network failure != strategy poisoning")
    fun testNetworkFailureDoesNotPoisonStrategy() = runBlocking {
        // Shutdown server prematurely to force network connection failure
        mockServer.shutdown()

        val context = ResolutionContext(track = sampleTrack)
        val result = provider.resolvePlayback(context)

        assertThat(result).isInstanceOf(ResolutionResult.Failure::class.java)
        val failure = result as ResolutionResult.Failure
        assertThat(failure.error.category).isEqualTo(ErrorCategory.NETWORK_FAILURE)

        // Strategy health must remain unpoisoned at baseline 100.0
        val strategyId = YouTubePlaybackStrategy.ANDROID_MUSIC_STRATEGY.id
        val healthScore = strategyRegistry.healthTracker.getScore(strategyId)
        assertThat(healthScore).isEqualTo(100.0)

        val snapshot = strategyRegistry.healthTracker.getSnapshot(strategyId)
        assertThat(snapshot.consecutiveFailures).isEqualTo(0)

        // Circuit breaker must remain CLOSED
        val cb = strategyRegistry.getCircuitBreaker(strategyId)
        assertThat(cb?.currentState).isEqualTo(CircuitState.CLOSED)
    }

    @Test
    @DisplayName("3. Transformation-required failure: PassThrough rejects cipher instead of returning fake URL")
    fun testTransformationRequiredExplicitFailureAndFallback() = runBlocking {
        val cipherOnlyResponse = """
        {
            "playabilityStatus": { "status": "OK" },
            "streamingData": {
                "expiresInSeconds": "18000",
                "adaptiveFormats": [
                    {
                        "itag": 251,
                        "signatureCipher": "s=encrypted_s_val&sp=sig&url=https%3A%2F%2Frr1.googlevideo.com%2Fvideoplayback%3Fitag%3D251",
                        "mimeType": "audio/webm; codecs=\"opus\"",
                        "bitrate": 160000
                    }
                ]
            }
        }
        """.trimIndent()

        val validFallbackResponse = """
        {
            "playabilityStatus": { "status": "OK" },
            "streamingData": {
                "expiresInSeconds": "18000",
                "adaptiveFormats": [
                    {
                        "itag": 140,
                        "url": "https://rr2.googlevideo.com/videoplayback?itag=140",
                        "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"",
                        "bitrate": 128000
                    }
                ]
            }
        }
        """.trimIndent()

        // PassThrough transform cannot decipher signatures
        val transformProvider = PassThroughPlayerTransformProvider()
        assertThat(transformProvider.canTransform).isFalse()

        // Strategy 1 returns cipher only -> fails with TRANSFORMATION_REQUIRED
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody(cipherOnlyResponse))
        // Strategy 2 returns direct URL -> succeeds!
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody(validFallbackResponse))

        val testSession = InnerTubeSession(baseUrl = mockServer.url("/").toString())
        val testProvider = YouTubeMusicProvider(
            session = testSession,
            strategyRegistry = strategyRegistry,
            transformProvider = transformProvider
        )

        val context = ResolutionContext(track = sampleTrack)
        val result = testProvider.resolvePlayback(context)

        assertThat(result).isInstanceOf(ResolutionResult.Success::class.java)
        val success = result as ResolutionResult.Success
        val primary = success.primarySource as PlaybackSource.Progressive
        assertThat(primary.url).contains("itag=140")
    }

    @Test
    @DisplayName("4. Token-required failure: missing token fails explicitly and triggers fallback")
    fun testTokenRequiredFailureTriggersFallback() = runBlocking {
        val emptyTokenProvider = object : PlaybackTokenProvider {
            override suspend fun getPoToken(videoId: String): String? = null
            override suspend fun getVisitorData(): String? = null
            override fun updateVisitorData(visitorData: String) {}
            override fun invalidate(videoId: String) {}
            override fun clear() {}
        }

        val webRemixRegistry = StrategyRegistry()
        val tokenTestProvider = YouTubeMusicProvider(
            session = InnerTubeSession(baseUrl = mockServer.url("/").toString()),
            strategyRegistry = webRemixRegistry,
            tokenProvider = emptyTokenProvider
        )

        // Clear default auto-registered strategies and register WEB_REMIX (priority 90) & VISIONOS (priority 80)
        webRemixRegistry.clear()
        webRemixRegistry.register(YouTubePlaybackStrategy.WEB_REMIX_STRATEGY.copy(priority = 90))
        webRemixRegistry.register(YouTubePlaybackStrategy.VISIONOS_STRATEGY.copy(priority = 80))

        val validFallbackResponse = """
        {
            "playabilityStatus": { "status": "OK" },
            "streamingData": {
                "expiresInSeconds": "18000",
                "adaptiveFormats": [
                    {
                        "itag": 140,
                        "url": "https://rr3.googlevideo.com/videoplayback?itag=140",
                        "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"",
                        "bitrate": 128000
                    }
                ]
            }
        }
        """.trimIndent()

        // VisionOS fallback request succeeds
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody(validFallbackResponse))

        val context = ResolutionContext(track = sampleTrack)
        val result = tokenTestProvider.resolvePlayback(context)

        assertThat(result).isInstanceOf(ResolutionResult.Success::class.java)
        val success = result as ResolutionResult.Success
        // Resolved with fallback strategy VisionOS
        assertThat(success.strategyId).isEqualTo(YouTubePlaybackStrategy.VISIONOS_STRATEGY.id)
    }

    @Test
    @DisplayName("5. Disabled strategy exclusion from eligibility")
    fun testDisabledStrategyExclusion() {
        val registry = StrategyRegistry()
        val strategy = YouTubePlaybackStrategy.ANDROID_MUSIC_STRATEGY
        registry.register(strategy)

        val context = ResolutionContext(track = sampleTrack)
        assertThat(registry.getEligibleStrategies(context)).hasSize(1)

        // Disable strategy
        registry.setStrategyEnabled(strategy.id, false)
        assertThat(registry.getEligibleStrategies(context)).isEmpty()

        // Also excluded when forced
        val forcedContext = ResolutionContext(track = sampleTrack, forcedStrategyId = strategy.id)
        assertThat(registry.getEligibleStrategies(forcedContext)).isEmpty()
    }

    @Test
    @DisplayName("6. Unhealthy strategy demotion via dynamic composite scoring")
    fun testUnhealthyStrategyDemotion() {
        val registry = StrategyRegistry()
        val highPriorityStrat = YouTubePlaybackStrategy.ANDROID_MUSIC_STRATEGY // priority 90
        val lowerPriorityStrat = YouTubePlaybackStrategy.TVHTML5_STRATEGY // priority 70

        registry.register(highPriorityStrat)
        registry.register(lowerPriorityStrat)

        val context = ResolutionContext(track = sampleTrack)
        val initialEligible = registry.getEligibleStrategies(context)
        assertThat(initialEligible.first().id).isEqualTo(highPriorityStrat.id)

        // Report severe failures on high priority strategy
        registry.reportFailure(highPriorityStrat.id, FailureType.PROVIDER_REJECTION, 100L)
        registry.reportFailure(highPriorityStrat.id, FailureType.BOT_DETECTION, 100L)

        // Health-based demotion: lowerPriorityStrat should now rank first!
        val rerankedEligible = registry.getEligibleStrategies(context)
        assertThat(rerankedEligible.first().id).isEqualTo(lowerPriorityStrat.id)
    }

    @Test
    @DisplayName("7. Strategy rehabilitation restores circuit breaker and health score")
    fun testStrategyRehabilitation() {
        val registry = StrategyRegistry(defaultFailureThreshold = 2)
        val strategy = YouTubePlaybackStrategy.ANDROID_MUSIC_STRATEGY
        registry.register(strategy)

        // Trip circuit breaker with 2 severe failures
        registry.reportFailure(strategy.id, FailureType.BOT_DETECTION, 100L)
        registry.reportFailure(strategy.id, FailureType.BOT_DETECTION, 100L)

        val cb = registry.getCircuitBreaker(strategy.id)!!
        assertThat(cb.currentState).isEqualTo(CircuitState.OPEN)
        assertThat(registry.healthTracker.getScore(strategy.id)).isLessThan(60.0)

        // Rehabilitate
        registry.rehabilitate(strategy.id)

        assertThat(cb.currentState).isEqualTo(CircuitState.CLOSED)
        assertThat(cb.canExecute()).isTrue()
        assertThat(registry.healthTracker.getScore(strategy.id)).isEqualTo(100.0)
    }

    @Test
    @DisplayName("8. Unknown provider format handling does not crash")
    fun testUnknownProviderFormatHandling() {
        val unknownFormatJson = """
        {
            "playabilityStatus": { "status": "OK" },
            "streamingData": {
                "adaptiveFormats": [
                    {
                        "itag": 9999,
                        "url": "https://rr.googlevideo.com/videoplayback?itag=9999",
                        "mimeType": "audio/vnd.dlna.adts; codecs=\"unknown_codec\"",
                        "bitrate": 192000
                    }
                ]
            }
        }
        """.trimIndent()

        val parsed = PlayerResponseParser.parse(unknownFormatJson)
        assertThat(parsed.isPlayable).isTrue()
        assertThat(parsed.formats).hasSize(1)

        val format = parsed.formats.first()
        assertThat(format.audioFormat.codec).isEqualTo(AudioCodec.UNKNOWN)
        assertThat(format.audioFormat.container).isEqualTo(AudioContainer.RAW)
    }

    @Test
    @DisplayName("9. Malformed player response handled gracefully")
    fun testMalformedPlayerResponse() {
        val malformedJson = "{ this is completely invalid JSON content }}}"
        val parsed = PlayerResponseParser.parse(malformedJson)

        assertThat(parsed.isPlayable).isFalse()
        assertThat(parsed.status).isEqualTo("PARSE_ERROR")
        assertThat(parsed.formats).isEmpty()
    }

    @Test
    @DisplayName("10. Candidate conversion preserves metadata and converts to PlaybackSource")
    fun testCandidateConversion() {
        val candidate = ResolvedFormatCandidate(
            formatId = "140",
            url = "https://rr1.googlevideo.com/videoplayback?itag=140",
            mimeType = "audio/mp4",
            codec = AudioCodec.AAC,
            bitrateKbps = 128,
            sampleRateHz = 44100,
            channelCount = 2,
            durationMs = 180_000L,
            expiresAtMs = 1700000000000L,
            cipherInfo = CipherInfo("enc_sig", "sig", "https://rr1.googlevideo.com", isDeciphered = true),
            requiresNTransform = false,
            transportHints = TransportHints(
                isProgressiveCapable = true,
                isSabrCapable = true,
                serverEndpoint = "https://sabr.googlevideo.com",
                clientContextJson = "{}",
                headers = mapOf("User-Agent" to "TestUA")
            ),
            strategyId = "ytmusic:test",
            audioFormat = AudioFormat(codec = AudioCodec.AAC, container = AudioContainer.MP4_M4A, bitrateKbps = 128)
        )

        val sources = candidate.toPlaybackSources("track_xyz")
        assertThat(sources).hasSize(2)

        val progSource = sources.filterIsInstance<PlaybackSource.Progressive>().first()
        assertThat(progSource.trackId).isEqualTo("track_xyz")
        assertThat(progSource.url).contains("itag=140")
        assertThat(progSource.headers["User-Agent"]).isEqualTo("TestUA")
        assertThat(progSource.audioFormat.codec).isEqualTo(AudioCodec.AAC)

        val sabrSource = sources.filterIsInstance<PlaybackSource.Sabr>().first()
        assertThat(sabrSource.serverEndpoint).isEqualTo("https://sabr.googlevideo.com")
        assertThat(sabrSource.trackId).isEqualTo("track_xyz")
    }

    @Test
    @DisplayName("11. Provider-specific strategy isolation: generic registry handles arbitrary strategies")
    fun testProviderSpecificStrategyIsolation() {
        class CustomTestStrategy(
            override val id: String = "custom:mock_provider",
            override val priority: Int = 88,
            override val capabilities: StrategyCapabilities = StrategyCapabilities(
                supportsProgressive = true,
                supportedCodecs = setOf(AudioCodec.FLAC),
                supportedQualities = setOf(QualityProfile.HIGH)
            )
        ) : PlaybackStrategy

        val registry = StrategyRegistry()
        val customStrat = CustomTestStrategy()
        registry.register(customStrat)

        val context = ResolutionContext(
            track = Track(
                id = "c_1",
                providerId = "custom",
                title = "Lossless Track",
                artists = listOf(com.aurora.engine.core.model.ArtistRef("art_custom", "Custom Artist")),
                durationMs = 120_000L
            ),
            targetQuality = QualityProfile.HIGH,
            preferredCodecs = listOf(AudioCodec.FLAC)
        )

        val eligible = registry.getEligibleStrategies(context)
        assertThat(eligible).hasSize(1)
        assertThat(eligible.first().id).isEqualTo("custom:mock_provider")
    }
}
