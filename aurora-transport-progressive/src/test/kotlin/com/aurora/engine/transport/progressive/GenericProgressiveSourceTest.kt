package com.aurora.engine.transport.progressive

import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import com.aurora.engine.core.model.AudioCodec
import com.aurora.engine.core.model.AudioContainer
import com.aurora.engine.core.model.AudioFormat
import com.aurora.engine.core.model.PlaybackSource
import com.aurora.engine.transport.progressive.session.ProgressivePlaybackSession
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GenericProgressiveSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var transport: ProgressivePlaybackTransport

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        transport = ProgressivePlaybackTransport()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `transport accepts generic progressive source and preserves headers without youtube parameters`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("GENERIC_AUDIO_BITSTREAM_DATA")
        )

        val genericAudioFormat = AudioFormat(
            codec = AudioCodec.MP3,
            container = AudioContainer.MP3,
            bitrateKbps = 320,
            sampleRateHz = 44100
        )

        val genericSource = PlaybackSource.Progressive(
            trackId = "generic-pod-101",
            url = server.url("/podcast/episode-42.mp3").toString(),
            audioFormat = genericAudioFormat,
            headers = mapOf(
                "Authorization" to "Bearer generic-auth-token-xyz",
                "User-Agent" to "CustomPodcastApp/2.4",
                "X-Custom-Client-Id" to "client-independent-999"
            ),
            expiresAtMs = null,
            customCacheKey = "generic:podcast:42"
        )

        assertThat(transport.canHandle(genericSource)).isTrue()

        val session = transport.createSession(genericSource) as ProgressivePlaybackSession
        assertThat(session.sessionId).contains("generic-pod-101")

        // Open data source to trigger network request
        val dataSource = session.dataSourceFactory.createDataSource()
        val dataSpec = DataSpec.Builder()
            .setUri(Uri.parse(genericSource.url))
            .build()
        dataSource.open(dataSpec)

        val request = server.takeRequest()

        // Verify headers supplied by source are preserved
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer generic-auth-token-xyz")
        assertThat(request.getHeader("User-Agent")).isEqualTo("CustomPodcastApp/2.4")
        assertThat(request.getHeader("X-Custom-Client-Id")).isEqualTo("client-independent-999")

        // Verify transport does NOT inject YouTube-specific headers
        assertThat(request.getHeader("X-YouTube-Client-Name")).isNull()
        assertThat(request.getHeader("X-YouTube-Client-Version")).isNull()
        assertThat(request.getHeader("X-Goog-Visitor-Id")).isNull()
        assertThat(request.getHeader("Cookie")).isNull()

        session.close()
    }

    @Test
    fun `seeking generates standard HTTP 206 Range request on generic source`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 2048-4095/4096")
                .setBody("SEEKED_AUDIO_FRAME_CHUNK")
        )

        val genericSource = PlaybackSource.Progressive(
            trackId = "generic-seek-test",
            url = server.url("/audio/track.flac").toString(),
            audioFormat = AudioFormat(AudioCodec.FLAC, AudioContainer.RAW, 800),
            headers = mapOf("X-Tenant-Id" to "tenant-alpha")
        )

        val session = transport.createSession(genericSource) as ProgressivePlaybackSession
        val dataSource = session.dataSourceFactory.createDataSource()

        // Seeking to byte offset 2048
        val dataSpec = DataSpec.Builder()
            .setUri(Uri.parse(genericSource.url))
            .setPosition(2048L)
            .build()
        dataSource.open(dataSpec)

        val request = server.takeRequest()
        assertThat(request.getHeader("Range")).isEqualTo("bytes=2048-")
        assertThat(request.getHeader("X-Tenant-Id")).isEqualTo("tenant-alpha")

        session.close()
    }

    @Test
    fun `expired generic source is rejected before network dispatch`() = runTest {
        val expiredGenericSource = PlaybackSource.Progressive(
            trackId = "generic-expired-01",
            url = server.url("/protected/stream.aac").toString(),
            audioFormat = AudioFormat(AudioCodec.AAC, AudioContainer.MP4_M4A, 256),
            expiresAtMs = System.currentTimeMillis() - 5000L
        )

        assertThrows(Exception::class.java) {
            kotlinx.coroutines.runBlocking {
                transport.createSession(expiredGenericSource)
            }
        }

        // Verify zero network requests were made to the server
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `generic server errors are correctly converted to HttpDataSource exception`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setBody("Access Denied: IP not allowed")
        )

        val genericSource = PlaybackSource.Progressive(
            trackId = "generic-403-test",
            url = server.url("/restricted/audio.mp3").toString(),
            audioFormat = AudioFormat(AudioCodec.MP3, AudioContainer.MP3, 128)
        )

        val session = transport.createSession(genericSource) as ProgressivePlaybackSession
        val dataSource = session.dataSourceFactory.createDataSource()

        val dataSpec = DataSpec.Builder()
            .setUri(Uri.parse(genericSource.url))
            .build()

        val exception = assertThrows(HttpDataSource.InvalidResponseCodeException::class.java) {
            dataSource.open(dataSpec)
        }

        assertThat(exception.responseCode).isEqualTo(403)
        session.close()
    }
}
