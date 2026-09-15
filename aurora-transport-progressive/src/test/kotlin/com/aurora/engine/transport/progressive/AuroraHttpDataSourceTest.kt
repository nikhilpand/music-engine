package com.aurora.engine.transport.progressive

import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import com.aurora.engine.transport.progressive.datasource.AuroraHttpDataSourceFactory
import com.google.common.truth.Truth.assertThat
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
class AuroraHttpDataSourceTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `dataSource attaches custom headers and user agent`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("AUDIO_PAYLOAD_CHUNK_01")
        )

        val factory = AuroraHttpDataSourceFactory(
            userAgent = "AuroraTestAgent/1.0",
            additionalHeaders = mapOf(
                "X-Aurora-Auth" to "SecretToken123",
                "X-Custom-Client" to "AndroidPlayer"
            )
        )

        val dataSource = factory.createDataSource()
        val dataSpec = DataSpec.Builder()
            .setUri(Uri.parse(server.url("/stream.webm").toString()))
            .build()

        val bytesToRead = dataSource.open(dataSpec)
        assertThat(bytesToRead).isGreaterThan(0L)

        val recordedRequest = server.takeRequest()
        assertThat(recordedRequest.getHeader("User-Agent")).isEqualTo("AuroraTestAgent/1.0")
        assertThat(recordedRequest.getHeader("X-Aurora-Auth")).isEqualTo("SecretToken123")
        assertThat(recordedRequest.getHeader("X-Custom-Client")).isEqualTo("AndroidPlayer")

        val buffer = ByteArray(64)
        val readCount = dataSource.read(buffer, 0, buffer.size)
        val bodyRead = String(buffer, 0, readCount)
        assertThat(bodyRead).isEqualTo("AUDIO_PAYLOAD_CHUNK_01")

        dataSource.close()
    }

    @Test
    fun `dataSource handles 206 Partial Content range requests`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 100-199/1000")
                .setBody("PARTIAL_AUDIO_BYTES")
        )

        val factory = AuroraHttpDataSourceFactory()
        val dataSource = factory.createDataSource()

        val dataSpec = DataSpec.Builder()
            .setUri(Uri.parse(server.url("/stream.webm").toString()))
            .setPosition(100)
            .setLength(100)
            .build()

        val bytesToRead = dataSource.open(dataSpec)
        assertThat(bytesToRead).isGreaterThan(0L)

        val recordedRequest = server.takeRequest()
        val rangeHeader = recordedRequest.getHeader("Range")
        assertThat(rangeHeader).isNotNull()
        assertThat(rangeHeader).contains("bytes=100-")

        val buffer = ByteArray(64)
        val readCount = dataSource.read(buffer, 0, buffer.size)
        assertThat(String(buffer, 0, readCount)).isEqualTo("PARTIAL_AUDIO_BYTES")

        dataSource.close()
    }

    @Test
    fun `dataSource throws InvalidResponseCodeException on HTTP 403`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setBody("Forbidden")
        )

        val factory = AuroraHttpDataSourceFactory()
        val dataSource = factory.createDataSource()
        val dataSpec = DataSpec.Builder()
            .setUri(Uri.parse(server.url("/stream.webm").toString()))
            .build()

        val exception = assertThrows(HttpDataSource.InvalidResponseCodeException::class.java) {
            dataSource.open(dataSpec)
        }
        assertThat(exception.responseCode).isEqualTo(403)
        dataSource.close()
    }

    @Test
    fun `dataSource throws InvalidResponseCodeException on HTTP 404`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setBody("Not Found")
        )

        val factory = AuroraHttpDataSourceFactory()
        val dataSource = factory.createDataSource()
        val dataSpec = DataSpec.Builder()
            .setUri(Uri.parse(server.url("/stream.webm").toString()))
            .build()

        val exception = assertThrows(HttpDataSource.InvalidResponseCodeException::class.java) {
            dataSource.open(dataSpec)
        }
        assertThat(exception.responseCode).isEqualTo(404)
        dataSource.close()
    }
}
