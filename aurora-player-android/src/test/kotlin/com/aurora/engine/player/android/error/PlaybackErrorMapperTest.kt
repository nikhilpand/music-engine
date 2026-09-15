package com.aurora.engine.player.android.error

import android.net.Uri
import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import com.aurora.engine.core.model.AudioCodec
import com.aurora.engine.core.model.AudioContainer
import com.aurora.engine.core.model.AudioFormat
import com.aurora.engine.core.model.ErrorCategory
import com.aurora.engine.core.model.PlaybackSource
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.SocketTimeoutException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackErrorMapperTest {

    private val sampleSource = PlaybackSource.Progressive(
        trackId = "track-test",
        url = "https://example.com/stream.webm",
        audioFormat = AudioFormat(AudioCodec.OPUS, AudioContainer.WEBM, 160, 48_000, 2),
        expiresAtMs = null
    )

    @Test
    fun `maps HTTP 403 on expired source metadata to SOURCE_EXPIRED`() {
        val httpException = HttpDataSource.InvalidResponseCodeException(
            403,
            "Forbidden",
            null,
            emptyMap(),
            DataSpec(Uri.parse("https://example.com")),
            ByteArray(0)
        )
        val playbackException = PlaybackException(
            "HTTP 403 Forbidden",
            httpException,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        )

        val expiredSource = sampleSource.copy(expiresAtMs = 1_000_000L)
        val error = PlaybackErrorMapper.map(playbackException, expiredSource, nowMs = 1_500_000L)

        assertThat(error.category).isEqualTo(ErrorCategory.SOURCE_EXPIRED)
        assertThat(error.isRecoverable).isTrue()
    }

    @Test
    fun `maps HTTP 403 on URL expire query param to SOURCE_EXPIRED`() {
        val httpException = HttpDataSource.InvalidResponseCodeException(
            403,
            "Forbidden",
            null,
            emptyMap(),
            DataSpec(Uri.parse("https://example.com/audio.mp4?expire=1000")),
            ByteArray(0)
        )
        val playbackException = PlaybackException(
            "HTTP 403 Forbidden",
            httpException,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        )

        val sourceWithUrlExpiry = sampleSource.copy(
            url = "https://example.com/audio.mp4?expire=1000",
            expiresAtMs = null
        )
        val error = PlaybackErrorMapper.map(playbackException, sourceWithUrlExpiry, nowMs = 2_000_000L)

        assertThat(error.category).isEqualTo(ErrorCategory.SOURCE_EXPIRED)
        assertThat(error.isRecoverable).isTrue()
        assertThat(error.code).isEqualTo("HTTP_403_URL_PARAM_EXPIRED")
    }

    @Test
    fun `maps HTTP 403 with WWW-Authenticate or login required to AUTHENTICATION_REQUIRED`() {
        val httpException = HttpDataSource.InvalidResponseCodeException(
            403,
            "Forbidden",
            null,
            mapOf("WWW-Authenticate" to listOf("Bearer error=\"invalid_token\"")),
            DataSpec(Uri.parse("https://example.com")),
            "Login required to access this resource".toByteArray(Charsets.UTF_8)
        )
        val playbackException = PlaybackException(
            "HTTP 403 Forbidden",
            httpException,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        )

        val error = PlaybackErrorMapper.map(playbackException, sampleSource, nowMs = 1_000_000L)

        assertThat(error.category).isEqualTo(ErrorCategory.AUTHENTICATION_REQUIRED)
        assertThat(error.isRecoverable).isFalse()
        assertThat(error.code).isEqualTo("HTTP_403_AUTHENTICATION_REQUIRED")
    }

    @Test
    fun `maps HTTP 403 with bot challenge to BOT_DETECTION`() {
        val httpException = HttpDataSource.InvalidResponseCodeException(
            403,
            "Forbidden",
            null,
            emptyMap(),
            DataSpec(Uri.parse("https://example.com")),
            "Solve captcha challenge before continuing".toByteArray(Charsets.UTF_8)
        )
        val playbackException = PlaybackException(
            "HTTP 403 Forbidden",
            httpException,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        )

        val error = PlaybackErrorMapper.map(playbackException, sampleSource, nowMs = 1_000_000L)

        assertThat(error.category).isEqualTo(ErrorCategory.BOT_DETECTION)
        assertThat(error.isRecoverable).isTrue()
        assertThat(error.code).isEqualTo("HTTP_403_BOT_DETECTION")
    }

    @Test
    fun `maps HTTP 403 on geo or access restricted source to PROVIDER_REJECTION`() {
        val httpException = HttpDataSource.InvalidResponseCodeException(
            403,
            "Access Denied: Geo restricted content",
            null,
            emptyMap(),
            DataSpec(Uri.parse("https://example.com")),
            ByteArray(0)
        )
        val playbackException = PlaybackException(
            "HTTP 403 Forbidden",
            httpException,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        )

        val unexpiredSource = sampleSource.copy(expiresAtMs = 5_000_000L)
        val error = PlaybackErrorMapper.map(playbackException, unexpiredSource, nowMs = 1_000_000L)

        assertThat(error.category).isEqualTo(ErrorCategory.PROVIDER_REJECTION)
        assertThat(error.isRecoverable).isTrue()
        assertThat(error.code).isEqualTo("HTTP_403_PROVIDER_REJECTION")
    }

    @Test
    fun `maps generic HTTP 403 with no indicators to UNKNOWN_FORBIDDEN`() {
        val httpException = HttpDataSource.InvalidResponseCodeException(
            403,
            "Forbidden",
            null,
            emptyMap(),
            DataSpec(Uri.parse("https://example.com")),
            ByteArray(0)
        )
        val playbackException = PlaybackException(
            "HTTP 403 Forbidden",
            httpException,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        )

        val unexpiredSource = sampleSource.copy(expiresAtMs = 5_000_000L)
        val error = PlaybackErrorMapper.map(playbackException, unexpiredSource, nowMs = 1_000_000L)

        assertThat(error.category).isEqualTo(ErrorCategory.UNKNOWN_FORBIDDEN)
        assertThat(error.isRecoverable).isFalse()
        assertThat(error.code).isEqualTo("HTTP_403_UNKNOWN_FORBIDDEN")
    }

    @Test
    fun `maps HTTP 404 to UNPLAYABLE`() {
        val httpException = HttpDataSource.InvalidResponseCodeException(
            404,
            "Not Found",
            null,
            emptyMap(),
            DataSpec(Uri.parse("https://example.com")),
            ByteArray(0)
        )
        val playbackException = PlaybackException(
            "HTTP 404 Not Found",
            httpException,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        )

        val error = PlaybackErrorMapper.map(playbackException, sampleSource)

        assertThat(error.category).isEqualTo(ErrorCategory.UNPLAYABLE)
        assertThat(error.isRecoverable).isFalse()
        assertThat(error.code).isEqualTo("HTTP_404_NOT_FOUND")
    }

    @Test
    fun `maps HTTP 429 to RATE_LIMITED`() {
        val httpException = HttpDataSource.InvalidResponseCodeException(
            429,
            "Too Many Requests",
            null,
            emptyMap(),
            DataSpec(Uri.parse("https://example.com")),
            ByteArray(0)
        )
        val playbackException = PlaybackException(
            "HTTP 429 Rate Limit",
            httpException,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        )

        val error = PlaybackErrorMapper.map(playbackException, sampleSource)

        assertThat(error.category).isEqualTo(ErrorCategory.RATE_LIMITED)
        assertThat(error.isRecoverable).isTrue()
        assertThat(error.code).isEqualTo("HTTP_429_RATE_LIMITED")
    }

    @Test
    fun `maps SocketTimeoutException to TIMEOUT`() {
        val timeoutException = SocketTimeoutException("Read timed out")
        val playbackException = PlaybackException(
            "Network timeout",
            timeoutException,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
        )

        val error = PlaybackErrorMapper.map(playbackException, sampleSource)

        assertThat(error.category).isEqualTo(ErrorCategory.TIMEOUT)
        assertThat(error.isRecoverable).isTrue()
        assertThat(error.code).isEqualTo("MEDIA3_NETWORK_TIMEOUT")
    }

    @Test
    fun `maps ParserException to MALFORMED_MEDIA`() {
        val parserException = ParserException.createForMalformedContainer("Malformed webm block", null)
        val playbackException = PlaybackException(
            "Parsing failed",
            parserException,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
        )

        val error = PlaybackErrorMapper.map(playbackException, sampleSource)

        assertThat(error.category).isEqualTo(ErrorCategory.MALFORMED_MEDIA)
        assertThat(error.isRecoverable).isFalse()
        assertThat(error.code).isEqualTo("MEDIA3_PARSER_MALFORMED")
    }

    @Test
    fun `maps DecoderInitializationException to DECODER_FAILURE`() {
        val playbackException = PlaybackException(
            "Decoder init failed",
            null,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
        )

        val error = PlaybackErrorMapper.map(playbackException, sampleSource)

        assertThat(error.category).isEqualTo(ErrorCategory.DECODER_FAILURE)
        assertThat(error.isRecoverable).isTrue()
        assertThat(error.code).isEqualTo("MEDIA3_DECODER_FAILURE")
    }

    @Test
    fun `maps General IO error to IO`() {
        val ioException = IOException("Broken pipe")
        val playbackException = PlaybackException(
            "IO Failure",
            ioException,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED
        )

        val error = PlaybackErrorMapper.map(playbackException, sampleSource)

        assertThat(error.category).isEqualTo(ErrorCategory.IO)
        assertThat(error.isRecoverable).isTrue()
        assertThat(error.code).isEqualTo("MEDIA3_IO_UNSPECIFIED")
    }
}
