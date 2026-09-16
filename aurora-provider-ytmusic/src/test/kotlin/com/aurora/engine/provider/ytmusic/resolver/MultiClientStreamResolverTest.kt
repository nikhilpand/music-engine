package com.aurora.engine.provider.ytmusic.resolver

import com.aurora.engine.core.model.AudioCodec
import com.aurora.engine.core.model.AudioFormat
import com.aurora.engine.core.model.PlaybackSource
import com.aurora.engine.core.model.QualityProfile
import com.aurora.engine.core.model.Track
import com.aurora.engine.core.provider.ResolutionContext
import com.aurora.engine.core.provider.ResolutionResult
import com.aurora.engine.provider.ytmusic.cipher.CipherService
import com.aurora.engine.provider.ytmusic.config.ClientConfigEntry
import com.aurora.engine.provider.ytmusic.config.ClientConfigStore
import com.aurora.engine.provider.ytmusic.config.ClientLadder
import com.aurora.engine.provider.ytmusic.session.InnerTubeSession
import com.aurora.engine.provider.ytmusic.transport.TransportSelector
import com.aurora.engine.provider.ytmusic.transport.TransportType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MultiClientStreamResolverTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var session: InnerTubeSession
    private lateinit var urlCache: StreamUrlCache
    private lateinit var ladder: ClientLadder
    private lateinit var cipherService: FakeCipherService
    private lateinit var resolver: MultiClientStreamResolver

    private val sampleTrack = Track(
        id = "track_xyz",
        providerId = "ytmusic",
        title = "Test Track",
        artists = listOf(com.aurora.engine.core.model.ArtistRef("art_1", "Test Artist")),
        durationMs = 200_000L
    )

    private val resolutionContext = ResolutionContext(
        track = sampleTrack,
        targetQuality = QualityProfile.HIGH
    )

    private val client1 = ClientConfigEntry(
        clientName = "CLIENT_ONE",
        clientVersion = "1.0",
        userAgent = "UA1",
        supportsSabr = true,
        priority = 100
    )

    private val client2 = ClientConfigEntry(
        clientName = "CLIENT_TWO",
        clientVersion = "2.0",
        userAgent = "UA2",
        supportsSabr = false,
        priority = 80
    )

    @BeforeEach
    fun setUp() {
        mockServer = MockWebServer()
        mockServer.start()

        val httpClient = OkHttpClient.Builder().build()
        session = InnerTubeSession(
            baseUrl = mockServer.url("/").toString(),
            httpClient = httpClient
        )
        urlCache = StreamUrlCache()
        ladder = ClientLadder(listOf(client1, client2))
        cipherService = FakeCipherService()

        resolver = MultiClientStreamResolver(
            session = session,
            clientLadder = ladder,
            urlCache = urlCache,
            cipherService = cipherService
        )
    }

    @AfterEach
    fun tearDown() {
        mockServer.shutdown()
    }

    @Test
    fun `resolve returns cached source immediately on cache hit`() = runBlocking {
        // Pre-populate cache
        val gen = urlCache.startResolution(sampleTrack.id)
        urlCache.completeResolution(
            mediaId = sampleTrack.id,
            generation = gen,
            url = "https://cached.googlevideo.com/audio.opus",
            expiresAtMs = System.currentTimeMillis() + 60_000,
            clientName = "CLIENT_ONE",
            transportType = StreamUrlCache.TransportType.PROGRESSIVE
        )

        val result = resolver.resolve(sampleTrack, resolutionContext)

        assertThat(result).isInstanceOf(ResolutionResult.Success::class.java)
        val success = result as ResolutionResult.Success
        assertThat(success.strategyId).isEqualTo("CLIENT_ONE")
        assertThat(success.primarySource).isInstanceOf(PlaybackSource.Progressive::class.java)
        val progressive = success.primarySource as PlaybackSource.Progressive
        assertThat(progressive.url).isEqualTo("https://cached.googlevideo.com/audio.opus")

        // No network request made
        assertThat(mockServer.requestCount).isEqualTo(0)
    }

    @Test
    fun `resolve falls back to next client when first client returns 403`() = runBlocking {
        // First client returns 403
        mockServer.enqueue(MockResponse().setResponseCode(403).setBody("Forbidden"))

        // Second client returns valid response
        val successJson = """
        {
            "playabilityStatus": { "status": "OK" },
            "streamingData": {
                "expiresInSeconds": "3600",
                "adaptiveFormats": [
                    {
                        "itag": 251,
                        "mimeType": "audio/webm; codecs=\"opus\"",
                        "url": "https://client2.googlevideo.com/audio.opus",
                        "bitrate": 160000,
                        "approxDurationMs": "200000"
                    }
                ]
            }
        }
        """.trimIndent()
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody(successJson))

        val result = resolver.resolve(sampleTrack, resolutionContext)

        assertThat(result).isInstanceOf(ResolutionResult.Success::class.java)
        val success = result as ResolutionResult.Success
        assertThat(success.strategyId).isEqualTo("CLIENT_TWO")
        val progressive = success.primarySource as PlaybackSource.Progressive
        assertThat(progressive.url).isEqualTo("https://client2.googlevideo.com/audio.opus")

        // Client one should have a failure recorded
        assertThat(ladder.getHealthSnapshot()["CLIENT_ONE"]).isEqualTo(1)
        // Client two should have success recorded (penalty = 0)
        assertThat(ladder.getHealthSnapshot()["CLIENT_TWO"]).isEqualTo(0)
    }

    @Test
    fun `resolve demotes client and tries next client on duration mismatch`() = runBlocking {
        // Track expected duration is 200_000ms.
        // Client 1 returns format with approxDurationMs = 50_000ms (difference = 150_000ms > 5000ms)
        val mismatchedJson = """
        {
            "playabilityStatus": { "status": "OK" },
            "streamingData": {
                "expiresInSeconds": "3600",
                "adaptiveFormats": [
                    {
                        "itag": 251,
                        "mimeType": "audio/webm; codecs=\"opus\"",
                        "url": "https://mismatched.googlevideo.com/short_clip.opus",
                        "bitrate": 160000,
                        "approxDurationMs": "50000"
                    }
                ]
            }
        }
        """.trimIndent()
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody(mismatchedJson))

        // Client 2 returns valid format matching duration (202_000ms, diff 2000ms <= 5000ms)
        val validJson = """
        {
            "playabilityStatus": { "status": "OK" },
            "streamingData": {
                "expiresInSeconds": "3600",
                "adaptiveFormats": [
                    {
                        "itag": 251,
                        "mimeType": "audio/webm; codecs=\"opus\"",
                        "url": "https://valid.googlevideo.com/full_song.opus",
                        "bitrate": 160000,
                        "approxDurationMs": "202000"
                    }
                ]
            }
        }
        """.trimIndent()
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody(validJson))

        val result = resolver.resolve(sampleTrack, resolutionContext)

        assertThat(result).isInstanceOf(ResolutionResult.Success::class.java)
        val success = result as ResolutionResult.Success
        assertThat(success.strategyId).isEqualTo("CLIENT_TWO")
        val progressive = success.primarySource as PlaybackSource.Progressive
        assertThat(progressive.url).isEqualTo("https://valid.googlevideo.com/full_song.opus")

        // Client 1 failed duration validation
        assertThat(ladder.getHealthSnapshot()["CLIENT_ONE"]).isEqualTo(1)
    }

    @Test
    fun `resolve deobfuscates cipher signature and transforms n parameter`() = runBlocking {
        cipherService.operational = true
        cipherService.decipheredResult = "DECIPHERED_SIG_123"
        cipherService.transformedNResult = "TRANSFORMED_N_VAL"

        val cipherJson = """
        {
            "playabilityStatus": { "status": "OK" },
            "streamingData": {
                "expiresInSeconds": "3600",
                "adaptiveFormats": [
                    {
                        "itag": 251,
                        "mimeType": "audio/webm; codecs=\"opus\"",
                        "signatureCipher": "s=ENCRYPTED_SIG&sp=sig&url=https%3A%2F%2Fcipher.googlevideo.com%2Fvideoplayback%3Fn%3DORIGINAL_N_VAL",
                        "bitrate": 160000,
                        "approxDurationMs": "200000"
                    }
                ]
            }
        }
        """.trimIndent()
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody(cipherJson))

        val result = resolver.resolve(sampleTrack, resolutionContext)

        assertThat(result).isInstanceOf(ResolutionResult.Success::class.java)
        val success = result as ResolutionResult.Success
        val progressive = success.primarySource as PlaybackSource.Progressive

        assertThat(progressive.url).contains("sig=DECIPHERED_SIG_123")
        assertThat(progressive.url).contains("n=TRANSFORMED_N_VAL")
        assertThat(progressive.url).doesNotContain("ORIGINAL_N_VAL")
    }

    @Test
    fun `resolve returns failure when all clients are quarantined or exhausted`() = runBlocking {
        // Quarantine all clients
        repeat(3) { ladder.recordFailure("CLIENT_ONE") }
        repeat(3) { ladder.recordFailure("CLIENT_TWO") }

        val result = resolver.resolve(sampleTrack, resolutionContext)

        assertThat(result).isInstanceOf(ResolutionResult.Failure::class.java)
        val failure = result as ResolutionResult.Failure
        assertThat(failure.error.code).isEqualTo("ALL_CLIENTS_QUARANTINED")
        assertThat(failure.canFallback).isTrue()
    }

    @Test
    fun `resolve selects SABR when client is SABR capable and SABR is preferred`() = runBlocking {
        val sabrJson = """
        {
            "playabilityStatus": { "status": "OK" },
            "streamingData": {
                "serverEndpoint": "https://sabr.googlevideo.com/stream",
                "ustreamerConfig": "ustr_cfg_123",
                "expiresInSeconds": "3600",
                "adaptiveFormats": [
                    {
                        "itag": 251,
                        "mimeType": "audio/webm; codecs=\"opus\"",
                        "url": "https://prog.googlevideo.com/audio.opus",
                        "bitrate": 160000,
                        "approxDurationMs": "200000"
                    }
                ]
            }
        }
        """.trimIndent()
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody(sabrJson))

        val result = resolver.resolve(sampleTrack, resolutionContext, preferSabr = true)

        assertThat(result).isInstanceOf(ResolutionResult.Success::class.java)
        val success = result as ResolutionResult.Success
        assertThat(success.primarySource).isInstanceOf(PlaybackSource.Sabr::class.java)
        val sabr = success.primarySource as PlaybackSource.Sabr
        assertThat(sabr.serverEndpoint).isEqualTo("https://sabr.googlevideo.com/stream")
        assertThat(sabr.ustreamerConfig).isEqualTo("ustr_cfg_123")
    }

    class FakeCipherService : CipherService {
        var operational = true
        var decipheredResult = "DECIPHERED"
        var transformedNResult = "TRANSFORMED"

        override val isOperational: Boolean get() = operational

        override suspend fun decipherSignature(encryptedSignature: String, playerScriptUrl: String): String {
            return decipheredResult
        }

        override suspend fun transformN(nParameter: String, playerScriptUrl: String): String {
            return transformedNResult
        }

        override suspend fun invalidate(playerScriptUrl: String) {}

        override fun close() {}
    }
}
