package com.aurora.engine.player.android.recovery

import com.aurora.engine.core.model.AudioCodec
import com.aurora.engine.core.model.AudioContainer
import com.aurora.engine.core.model.AudioFormat
import com.aurora.engine.core.model.ErrorCategory
import com.aurora.engine.core.model.PlaybackError
import com.aurora.engine.core.model.PlaybackSource
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test

class PlaybackRecoveryCoordinatorTest {

    private val sampleSource = PlaybackSource.Progressive(
        trackId = "track-recovery-1",
        url = "https://example.com/stream.webm",
        audioFormat = AudioFormat(AudioCodec.OPUS, AudioContainer.WEBM, 160, 48_000, 2)
    )

    private val freshSource = PlaybackSource.Progressive(
        trackId = "track-recovery-1",
        url = "https://example.com/fresh_stream.webm",
        audioFormat = AudioFormat(AudioCodec.OPUS, AudioContainer.WEBM, 160, 48_000, 2)
    )

    private val fallbackSource = PlaybackSource.Progressive(
        trackId = "track-recovery-1",
        url = "https://example.com/fallback_stream.m4a",
        audioFormat = AudioFormat(AudioCodec.AAC, AudioContainer.MP4_M4A, 128, 44_100, 2)
    )

    private lateinit var fakeHandler: FakeRecoveryHandler
    private lateinit var coordinator: PlaybackRecoveryCoordinator

    class FakeRecoveryHandler(
        var freshSourceToReturn: PlaybackSource? = null,
        var fallbackSourceToReturn: PlaybackSource? = null
    ) : PlaybackRecoveryHandler {
        override suspend fun reResolveSource(expiredSource: PlaybackSource): PlaybackSource? {
            return freshSourceToReturn
        }

        override suspend fun fallbackSource(failedSource: PlaybackSource, error: PlaybackError): PlaybackSource? {
            return fallbackSourceToReturn
        }

        override suspend fun onProviderRejection(failedSource: PlaybackSource, error: PlaybackError): PlaybackSource? {
            return fallbackSourceToReturn
        }

        override suspend fun fallbackFormat(failedSource: PlaybackSource, error: PlaybackError): PlaybackSource? {
            return fallbackSourceToReturn
        }
    }

    @Before
    fun setUp() {
        fakeHandler = FakeRecoveryHandler(
            freshSourceToReturn = freshSource,
            fallbackSourceToReturn = fallbackSource
        )
        coordinator = PlaybackRecoveryCoordinator(
            maxRetries = 3,
            initialBackoffMs = 200L,
            backoffMultiplier = 2.0,
            recoveryHandler = fakeHandler
        )
    }

    @Test
    fun `transient network error triggers exponential backoff and preserves position`() = runBlocking {
        val error = PlaybackError(
            code = "IO_TIMEOUT",
            message = "Connection timed out",
            category = ErrorCategory.NETWORK_FAILURE,
            isRecoverable = true
        )

        val decision1 = coordinator.handlePlaybackError(error, sampleSource, playbackPositionMs = 5000L)
        assertThat(decision1.action).isEqualTo(RecoveryAction.RETRY_SAME_SOURCE)
        assertThat(decision1.delayMs).isEqualTo(200L)
        assertThat(decision1.resumePositionMs).isEqualTo(5000L)
        assertThat(coordinator.currentRetryCount).isEqualTo(1)

        val decision2 = coordinator.handlePlaybackError(error, sampleSource, playbackPositionMs = 5000L)
        assertThat(decision2.action).isEqualTo(RecoveryAction.RETRY_SAME_SOURCE)
        assertThat(decision2.delayMs).isEqualTo(400L)
        assertThat(coordinator.currentRetryCount).isEqualTo(2)

        val decision3 = coordinator.handlePlaybackError(error, sampleSource, playbackPositionMs = 5000L)
        assertThat(decision3.action).isEqualTo(RecoveryAction.RETRY_SAME_SOURCE)
        assertThat(decision3.delayMs).isEqualTo(800L)
        assertThat(coordinator.currentRetryCount).isEqualTo(3)

        // 4th failure exceeds maxRetries -> FAIL
        val decision4 = coordinator.handlePlaybackError(error, sampleSource, playbackPositionMs = 5000L)
        assertThat(decision4.action).isEqualTo(RecoveryAction.FAIL)
    }

    @Test
    fun `SOURCE_EXPIRED triggers re-resolution and resets retry budget`() = runBlocking {
        val error = PlaybackError(
            code = "SOURCE_URL_EXPIRED",
            message = "Signed URL expired",
            category = ErrorCategory.SOURCE_EXPIRED,
            isRecoverable = true
        )

        val decision = coordinator.handlePlaybackError(error, sampleSource, playbackPositionMs = 12000L)

        assertThat(decision.action).isEqualTo(RecoveryAction.RE_RESOLVE_SOURCE)
        assertThat(decision.newSource).isEqualTo(freshSource)
        assertThat(decision.resumePositionMs).isEqualTo(12000L)
        assertThat(coordinator.currentRetryCount).isEqualTo(0)
    }

    @Test
    fun `PROVIDER_REJECTION triggers fallback resolution and candidate invalidation`() = runBlocking {
        val error = PlaybackError(
            code = "HTTP_403_PROVIDER_REJECTION",
            message = "Provider rejected stream",
            category = ErrorCategory.PROVIDER_REJECTION,
            isRecoverable = true
        )

        val decision = coordinator.handlePlaybackError(error, sampleSource, playbackPositionMs = 8000L)

        assertThat(decision.action).isEqualTo(RecoveryAction.FALLBACK_RESOLUTION)
        assertThat(decision.newSource).isEqualTo(fallbackSource)
        assertThat(decision.resumePositionMs).isEqualTo(8000L)
        assertThat(coordinator.currentRetryCount).isEqualTo(0)
    }

    @Test
    fun `RATE_LIMITED triggers cooldown delay`() = runBlocking {
        val error = PlaybackError(
            code = "HTTP_429_RATE_LIMITED",
            message = "Too Many Requests",
            category = ErrorCategory.RATE_LIMITED,
            isRecoverable = true
        )

        val decision = coordinator.handlePlaybackError(error, sampleSource, playbackPositionMs = 10000L)

        assertThat(decision.action).isEqualTo(RecoveryAction.COOLDOWN_RETRY)
        assertThat(decision.delayMs).isEqualTo(1000L)
        assertThat(decision.resumePositionMs).isEqualTo(10000L)
        assertThat(coordinator.currentRetryCount).isEqualTo(1)
    }

    @Test
    fun `DECODER_FAILURE triggers compatible format fallback`() = runBlocking {
        val error = PlaybackError(
            code = "DECODER_INIT_FAILED",
            message = "Opus codec failure",
            category = ErrorCategory.DECODER_FAILURE,
            isRecoverable = true
        )

        val decision = coordinator.handlePlaybackError(error, sampleSource, playbackPositionMs = 3000L)

        assertThat(decision.action).isEqualTo(RecoveryAction.FORMAT_FALLBACK)
        assertThat(decision.newSource).isEqualTo(fallbackSource)
        assertThat(decision.resumePositionMs).isEqualTo(3000L)
        assertThat(coordinator.currentRetryCount).isEqualTo(0)
    }

    @Test
    fun `AUTHENTICATION_REQUIRED produces terminal auth required action without retries`() = runBlocking {
        val error = PlaybackError(
            code = "HTTP_403_AUTHENTICATION_REQUIRED",
            message = "Login required",
            category = ErrorCategory.AUTHENTICATION_REQUIRED,
            isRecoverable = false
        )

        val decision = coordinator.handlePlaybackError(error, sampleSource, playbackPositionMs = 2500L)

        assertThat(decision.action).isEqualTo(RecoveryAction.TERMINAL_AUTH_REQUIRED)
        assertThat(decision.resumePositionMs).isEqualTo(2500L)
        assertThat(coordinator.currentRetryCount).isEqualTo(0)
    }

    @Test
    fun `unrecoverable error fails immediately without retrying`() = runBlocking {
        val error = PlaybackError(
            code = "GEO_RESTRICTED",
            message = "Track unplayable in country",
            category = ErrorCategory.CONTENT_RESTRICTION,
            isRecoverable = false
        )

        val decision = coordinator.handlePlaybackError(error, sampleSource, playbackPositionMs = 0L)

        assertThat(decision.action).isEqualTo(RecoveryAction.FAIL)
        assertThat(coordinator.currentRetryCount).isEqualTo(0)
    }
}
