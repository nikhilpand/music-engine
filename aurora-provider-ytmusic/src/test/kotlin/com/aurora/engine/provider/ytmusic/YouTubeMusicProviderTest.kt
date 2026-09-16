package com.aurora.engine.provider.ytmusic

import com.aurora.engine.core.model.AudioCodec
import com.aurora.engine.core.model.ErrorCategory
import com.aurora.engine.core.model.PlaybackSource
import com.aurora.engine.core.model.QualityProfile
import com.aurora.engine.core.model.Track
import com.aurora.engine.core.provider.ResolutionContext
import com.aurora.engine.core.provider.ResolutionResult
import com.aurora.engine.core.strategy.StrategyRegistry
import com.aurora.engine.provider.ytmusic.session.InnerTubeSession
import com.aurora.engine.provider.ytmusic.strategy.YouTubePlaybackStrategy
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

class YouTubeMusicProviderTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var provider: YouTubeMusicProvider
    private lateinit var strategyRegistry: StrategyRegistry

    private val sampleTrack = Track(
        id = "test_vid_123",
        providerId = "ytmusic",
        title = "Blinding Lights",
        artists = listOf(com.aurora.engine.core.model.ArtistRef("art_1", "The Weeknd")),
        durationMs = 200_000L
    )

    @BeforeEach
    fun setUp() {
        mockServer = MockWebServer()
        mockServer.start()

        strategyRegistry = StrategyRegistry()

        val httpClient = OkHttpClient.Builder().build()
        val session = InnerTubeSession(
            baseUrl = mockServer.url("/").toString(),
            httpClient = httpClient
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
    @DisplayName("Resolves track successfully to PlaybackSource with direct URLs and ranks candidate sources")
    fun testResolveSuccessDirectUrls() = runBlocking {
        val playerResponseBody = """
        {
            "playabilityStatus": {
                "status": "OK"
            },
            "streamingData": {
                "expiresInSeconds": "21600",
                "adaptiveFormats": [
                    {
                        "itag": 140,
                        "url": "https://rr1.googlevideo.com/videoplayback?itag=140",
                        "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"",
                        "bitrate": 128000,
                        "averageBitrate": 127000
                    },
                    {
                        "itag": 251,
                        "url": "https://rr1.googlevideo.com/videoplayback?itag=251",
                        "mimeType": "audio/webm; codecs=\"opus\"",
                        "bitrate": 160000,
                        "averageBitrate": 154000
                    }
                ]
            }
        }
        """.trimIndent()

        mockServer.enqueue(MockResponse().setResponseCode(200).setBody(playerResponseBody))

        val context = ResolutionContext(
            track = sampleTrack,
            targetQuality = QualityProfile.MEDIUM,
            preferredCodecs = listOf(AudioCodec.OPUS, AudioCodec.AAC)
        )

        val result = provider.resolvePlayback(context)

        assertThat(result).isInstanceOf(ResolutionResult.Success::class.java)
        val success = result as ResolutionResult.Success

        assertThat(success.sources).isNotEmpty()
        val primarySource = success.primarySource as PlaybackSource.Progressive

        // OPUS is preferred in context, so itag 251 should rank first
        assertThat(primarySource.audioFormat.codec).isEqualTo(AudioCodec.OPUS)
        assertThat(primarySource.url).contains("itag=251")
        assertThat(primarySource.customCacheKey).isEqualTo("aurora:track:test_vid_123")

        // Recorded success into health tracker
        val healthScore = strategyRegistry.healthTracker.getScore(success.strategyId)
        assertThat(healthScore).isEqualTo(100.0)
    }

    @Test
    @DisplayName("Resolves cipher streams via PlayerTransformProvider")
    fun testResolveCipherDeciphering() = runBlocking {
        val cipherResponse = """
        {
            "playabilityStatus": {
                "status": "OK"
            },
            "streamingData": {
                "expiresInSeconds": "18000",
                "adaptiveFormats": [
                    {
                        "itag": 251,
                        "signatureCipher": "s=mock_encrypted_sig&sp=sig&url=https%3A%2F%2Frr1.googlevideo.com%2Fvideoplayback%3Fitag%3D251%26n%3Dsample_n_param",
                        "mimeType": "audio/webm; codecs=\"opus\"",
                        "bitrate": 160000
                    }
                ]
            }
        }
        """.trimIndent()

        mockServer.enqueue(MockResponse().setResponseCode(200).setBody(cipherResponse))

        val mockTransform = object : PlayerTransformProvider {
            override val name: String = "MockTransformer"
            override suspend fun decipherSignature(encryptedSignature: String, scriptSource: String?): String {
                return "deciphered_sig_value"
            }
            override suspend fun transformN(nParameter: String, scriptSource: String?): String {
                return "transformed_$nParameter"
            }
        }

        val session = InnerTubeSession(baseUrl = mockServer.url("/").toString())
        val cipherProvider = YouTubeMusicProvider(
            session = session,
            strategyRegistry = strategyRegistry,
            transformProvider = mockTransform
        )

        val context = ResolutionContext(track = sampleTrack)
        val result = cipherProvider.resolvePlayback(context)

        assertThat(result).isInstanceOf(ResolutionResult.Success::class.java)
        val success = result as ResolutionResult.Success
        val primary = success.primarySource as PlaybackSource.Progressive

        assertThat(primary.url).contains("sig=deciphered_sig_value")
    }

    @Test
    @DisplayName("Strategy fallback: when first strategy returns 403, engine falls back to next strategy and succeeds")
    fun testStrategyFallbackOn403() = runBlocking {
        // First request (Strategy 1) fails with HTTP 403 Bot Detection
        mockServer.enqueue(MockResponse().setResponseCode(403).setBody("Forbidden bot access"))

        // Second request (Strategy 2) succeeds with valid stream formats
        val fallbackSuccessBody = """
        {
            "playabilityStatus": { "status": "OK" },
            "streamingData": {
                "expiresInSeconds": "21600",
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
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody(fallbackSuccessBody))

        val context = ResolutionContext(track = sampleTrack)
        val result = provider.resolvePlayback(context)

        assertThat(result).isInstanceOf(ResolutionResult.Success::class.java)
        val success = result as ResolutionResult.Success
        assertThat(success.sources).hasSize(1)

        // Verify MockWebServer received 2 distinct requests
        assertThat(mockServer.requestCount).isEqualTo(2)

        // Verify that the failed strategy's health was penalized
        val failedStrategy = YouTubePlaybackStrategy.ANDROID_MUSIC_STRATEGY.id
        val failedScore = strategyRegistry.healthTracker.getScore(failedStrategy)
        assertThat(failedScore).isLessThan(70.0)
    }

    @Test
    @DisplayName("Total failure when all strategies return HTTP 403")
    fun testAllStrategiesFail() = runBlocking {
        // Enqueue 403 for each registered strategy
        for (i in 1..4) {
            mockServer.enqueue(MockResponse().setResponseCode(403).setBody("Forbidden"))
        }

        val context = ResolutionContext(track = sampleTrack)
        val result = provider.resolvePlayback(context)

        assertThat(result).isInstanceOf(ResolutionResult.Failure::class.java)
        val failure = result as ResolutionResult.Failure
        assertThat(failure.error.category).isEqualTo(ErrorCategory.PROVIDER_REJECTION)
    }

    @Test
    @DisplayName("Catalog search returns parsed track list from InnerTube response")
    fun testCatalogSearch() = runBlocking {
        val searchJson = """
        {
            "contents": {
                "tabbedSearchResultsRenderer": {
                    "tabs": [
                        {
                            "tabRenderer": {
                                "content": {
                                    "sectionListRenderer": {
                                        "contents": [
                                            {
                                                "musicShelfRenderer": {
                                                    "contents": [
                                                        {
                                                            "musicResponsiveListItemRenderer": {
                                                                "flexColumns": [
                                                                    {
                                                                        "musicResponsiveListItemFlexColumnRenderer": {
                                                                            "text": {
                                                                                "runs": [
                                                                                    {
                                                                                        "text": "Save Your Tears",
                                                                                        "navigationEndpoint": {
                                                                                            "watchEndpoint": { "videoId": "XXYlFuWEuKI" }
                                                                                        }
                                                                                    }
                                                                                ]
                                                                            }
                                                                        }
                                                                    },
                                                                    {
                                                                        "musicResponsiveListItemFlexColumnRenderer": {
                                                                            "text": {
                                                                                "runs": [
                                                                                    { "text": "The Weeknd" },
                                                                                    { "text": " • " },
                                                                                    { "text": "3:35" }
                                                                                ]
                                                                            }
                                                                        }
                                                                    }
                                                                ]
                                                            }
                                                        }
                                                    ]
                                                }
                                            }
                                        ]
                                    }
                                }
                            }
                        }
                    ]
                }
            }
        }
        """.trimIndent()

        mockServer.enqueue(MockResponse().setResponseCode(200).setBody(searchJson))

        val results = provider.search("The Weeknd")
        assertThat(results).hasSize(1)
        assertThat(results.first().id).isEqualTo("XXYlFuWEuKI")
        assertThat(results.first().title).isEqualTo("Save Your Tears")
        assertThat(results.first().durationMs).isEqualTo(215_000L) // 3*60+35 = 215 sec
    }

    @Test
    @DisplayName("MultiClientStreamResolver primary path resolves successfully and resets consecutive recovery errors")
    fun testPrimaryStreamResolverSuccess() = runBlocking {
        val store = com.aurora.engine.provider.ytmusic.config.ClientConfigStore(
            """[{"clientName":"TEST_CLIENT","clientVersion":"1.0","userAgent":"UA","supportsSabr":false,"priority":100,"enabled":true}]"""
        )
        val ladder = com.aurora.engine.provider.ytmusic.config.ClientLadder.forStreamResolution(store)
        val cache = com.aurora.engine.provider.ytmusic.resolver.StreamUrlCache()
        val recoveryManager = com.aurora.engine.provider.ytmusic.recovery.RecoveryManager()
        val resolver = com.aurora.engine.provider.ytmusic.resolver.MultiClientStreamResolver(
            session = provider.session,
            clientLadder = ladder,
            urlCache = cache,
            cipherService = com.aurora.engine.provider.ytmusic.cipher.NoOpCipherService()
        )

        val providerWithResolver = YouTubeMusicProvider(
            session = provider.session,
            streamResolver = resolver,
            recoveryManager = recoveryManager
        )

        val playerJson = """
        {
            "playabilityStatus": { "status": "OK" },
            "streamingData": {
                "expiresInSeconds": "3600",
                "adaptiveFormats": [
                    {
                        "itag": 251,
                        "mimeType": "audio/webm; codecs=\"opus\"",
                        "url": "https://resolver.googlevideo.com/audio.opus",
                        "bitrate": 160000,
                        "approxDurationMs": "200000"
                    }
                ]
            }
        }
        """.trimIndent()
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody(playerJson))

        val context = ResolutionContext(track = sampleTrack)
        val result = providerWithResolver.resolvePlayback(context)

        assertThat(result).isInstanceOf(ResolutionResult.Success::class.java)
        val success = result as ResolutionResult.Success
        assertThat(success.strategyId).isEqualTo("TEST_CLIENT")
        val progressive = success.primarySource as PlaybackSource.Progressive
        assertThat(progressive.url).isEqualTo("https://resolver.googlevideo.com/audio.opus")
    }

    @Test
    @DisplayName("Falls back to StrategyRegistry when MultiClientStreamResolver fails")
    fun testFallbackToStrategyRegistryOnResolverFailure() = runBlocking {
        val store = com.aurora.engine.provider.ytmusic.config.ClientConfigStore(
            """[{"clientName":"FAILING_CLIENT","clientVersion":"1.0","userAgent":"UA","supportsSabr":false,"priority":100,"enabled":true}]"""
        )
        val ladder = com.aurora.engine.provider.ytmusic.config.ClientLadder.forStreamResolution(store)
        val cache = com.aurora.engine.provider.ytmusic.resolver.StreamUrlCache()
        val resolver = com.aurora.engine.provider.ytmusic.resolver.MultiClientStreamResolver(
            session = provider.session,
            clientLadder = ladder,
            urlCache = cache,
            cipherService = com.aurora.engine.provider.ytmusic.cipher.NoOpCipherService()
        )

        val providerWithResolver = YouTubeMusicProvider(
            session = provider.session,
            streamResolver = resolver
        )

        // 1. Resolver attempt returns 403 (fails)
        mockServer.enqueue(MockResponse().setResponseCode(403).setBody("Forbidden"))
        // 2. Legacy fallback attempt returns 200 (succeeds)
        val fallbackJson = """
        {
            "playabilityStatus": { "status": "OK" },
            "streamingData": {
                "expiresInSeconds": "3600",
                "adaptiveFormats": [
                    {
                        "itag": 251,
                        "mimeType": "audio/webm; codecs=\"opus\"",
                        "url": "https://fallback.googlevideo.com/audio.opus",
                        "bitrate": 160000
                    }
                ]
            }
        }
        """.trimIndent()
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody(fallbackJson))

        val context = ResolutionContext(track = sampleTrack)
        val result = providerWithResolver.resolvePlayback(context)

        assertThat(result).isInstanceOf(ResolutionResult.Success::class.java)
        val success = result as ResolutionResult.Success
        val progressive = success.primarySource as PlaybackSource.Progressive
        assertThat(progressive.url).isEqualTo("https://fallback.googlevideo.com/audio.opus")
    }

    @Test
    @DisplayName("checkpointPlayback and handlePlaybackError correctly manage RecoveryManager state")
    fun testCheckpointAndRecoveryHandling() {
        val recoveryManager = com.aurora.engine.provider.ytmusic.recovery.RecoveryManager()
        val providerWithRecovery = YouTubeMusicProvider(
            session = provider.session,
            recoveryManager = recoveryManager
        )

        providerWithRecovery.checkpointPlayback(
            mediaId = "track_checkpoint_test",
            positionMs = 45_000L,
            byteOffset = 600_000L,
            durationMs = 210_000L,
            currentTransport = com.aurora.engine.provider.ytmusic.transport.TransportType.PROGRESSIVE,
            currentClientName = "ANDROID_MUSIC"
        )

        val action = providerWithRecovery.handlePlaybackError(
            mediaId = "track_checkpoint_test",
            errorType = com.aurora.engine.provider.ytmusic.recovery.PlaybackErrorType.HTTP_403,
            currentClientName = "ANDROID_MUSIC",
            currentTransport = com.aurora.engine.provider.ytmusic.transport.TransportType.PROGRESSIVE,
            availableClients = 3,
            currentClientIndex = 0
        )

        assertThat(action.strategy).isEqualTo(com.aurora.engine.provider.ytmusic.recovery.RecoveryStrategy.REAUTH)
        assertThat(action.resumePositionMs).isEqualTo(45_000L)
        assertThat(action.resumeByteOffset).isEqualTo(600_000L)
    }

    @Test
    @DisplayName("Metadata catalog request falls back to second client when first metadata client returns 403")
    fun testMetadataFallbackOnFailure() = runBlocking {
        val store = com.aurora.engine.provider.ytmusic.config.ClientConfigStore(
            """[
                {"clientName":"META_CLIENT_1","clientVersion":"1.0","userAgent":"UA1","supportsMetadata":true,"metadataPriority":100,"enabled":true},
                {"clientName":"META_CLIENT_2","clientVersion":"2.0","userAgent":"UA2","supportsMetadata":true,"metadataPriority":80,"enabled":true}
            ]"""
        )
        val metadataLadder = com.aurora.engine.provider.ytmusic.config.ClientLadder.forMetadata(store)
        val resolver = com.aurora.engine.provider.ytmusic.resolver.MultiClientStreamResolver(
            session = provider.session,
            clientLadder = metadataLadder,
            urlCache = com.aurora.engine.provider.ytmusic.resolver.StreamUrlCache(),
            cipherService = com.aurora.engine.provider.ytmusic.cipher.NoOpCipherService(),
            metadataLadder = metadataLadder
        )

        val providerWithResolver = YouTubeMusicProvider(
            session = provider.session,
            streamResolver = resolver
        )

        // First client returns 403
        mockServer.enqueue(MockResponse().setResponseCode(403).setBody("Forbidden"))

        // Second client returns valid response
        val nextJson = """
        {
            "contents": {
                "singleColumnMusicWatchNextResultsRenderer": {
                    "tabbedRenderer": {
                        "watchNextTabbedResultsRenderer": {
                            "tabs": [
                                {
                                    "tabRenderer": {
                                        "content": {
                                            "musicQueueRenderer": {
                                                "content": {
                                                    "playlistPanelRenderer": {
                                                        "contents": [
                                                            {
                                                                "playlistPanelVideoRenderer": {
                                                                    "videoId": "radio_1",
                                                                    "title": { "runs": [{ "text": "Radio Song" }] },
                                                                    "shortBylineText": { "runs": [{ "text": "Artist" }] },
                                                                    "lengthText": { "runs": [{ "text": "3:00" }] }
                                                                }
                                                            }
                                                        ]
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            ]
                        }
                    }
                }
            }
        }
        """.trimIndent()
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody(nextJson))

        val radioTracks = providerWithResolver.getNextRadioTracks("start_track")
        assertThat(radioTracks).hasSize(1)
        assertThat(radioTracks.first().id).isEqualTo("radio_1")

        // First metadata client penalized, second client rewarded
        assertThat(metadataLadder.getHealthSnapshot()["META_CLIENT_1"]).isEqualTo(1)
        assertThat(metadataLadder.getHealthSnapshot()["META_CLIENT_2"]).isEqualTo(0)
    }
}
