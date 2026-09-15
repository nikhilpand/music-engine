package com.aurora.engine.transport.progressive

import com.aurora.engine.core.model.AudioCodec
import com.aurora.engine.core.model.AudioContainer
import com.aurora.engine.core.model.AudioFormat
import com.aurora.engine.core.model.ErrorCategory
import com.aurora.engine.core.model.PlaybackSource
import com.aurora.engine.transport.progressive.validation.UrlExpirationValidator
import com.aurora.engine.transport.progressive.validation.UrlExpiredException
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.assertThrows

class UrlExpirationTest {

    private val fixedClock = { 1_000_000L }

    @Test
    fun `valid source with future expiration passes validation`() {
        val validator = UrlExpirationValidator(clock = fixedClock)
        val source = PlaybackSource.Progressive(
            trackId = "track-future",
            url = "https://example.com/audio.webm",
            audioFormat = AudioFormat(AudioCodec.OPUS, AudioContainer.WEBM, 160, 48_000, 2),
            expiresAtMs = 1_500_000L
        )

        val result = validator.validate(source)
        assertThat(result.isSuccess).isTrue()
    }

    @Test
    fun `expired source fails validation with UrlExpiredException`() {
        val validator = UrlExpirationValidator(clock = fixedClock)
        val source = PlaybackSource.Progressive(
            trackId = "track-expired",
            url = "https://example.com/audio.webm",
            audioFormat = AudioFormat(AudioCodec.OPUS, AudioContainer.WEBM, 160, 48_000, 2),
            expiresAtMs = 999_999L // Expired relative to 1_000_000L
        )

        val result = validator.validate(source)
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(UrlExpiredException::class.java)

        val error = validator.toPlaybackError(source, result.exceptionOrNull())
        assertThat(error.category).isEqualTo(ErrorCategory.SOURCE_EXPIRED)
        assertThat(error.isRecoverable).isTrue()
        assertThat(error.code).isEqualTo("SOURCE_URL_EXPIRED")
    }

    @Test
    fun `source with safety margin rejects near-expiry sources`() {
        // Expiration is at 1_010_000, clock is 1_000_000, safety margin is 15_000
        val validator = UrlExpirationValidator(clock = fixedClock, safetyMarginMs = 15_000L)
        val source = PlaybackSource.Progressive(
            trackId = "track-marginal",
            url = "https://example.com/audio.webm",
            audioFormat = AudioFormat(AudioCodec.OPUS, AudioContainer.WEBM, 160, 48_000, 2),
            expiresAtMs = 1_010_000L
        )

        val result = validator.validate(source)
        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `source with null expiresAtMs always passes validation`() {
        val validator = UrlExpirationValidator(clock = fixedClock)
        val source = PlaybackSource.Progressive(
            trackId = "track-no-expiry",
            url = "https://example.com/audio.webm",
            audioFormat = AudioFormat(AudioCodec.OPUS, AudioContainer.WEBM, 160, 48_000, 2),
            expiresAtMs = null
        )

        val result = validator.validate(source)
        assertThat(result.isSuccess).isTrue()
    }

    @Test
    fun `transport rejects expired source before creating session`() {
        val validator = UrlExpirationValidator(clock = fixedClock)
        val transport = ProgressivePlaybackTransport(expirationValidator = validator)
        val source = PlaybackSource.Progressive(
            trackId = "track-expired-transport",
            url = "https://example.com/audio.webm",
            audioFormat = AudioFormat(AudioCodec.OPUS, AudioContainer.WEBM, 160, 48_000, 2),
            expiresAtMs = 900_000L
        )

        assertThrows(UrlExpiredException::class.java) {
            runBlocking {
                transport.createSession(source)
            }
        }
    }
}
