package com.aurora.engine.player.android.diagnostics

import com.aurora.engine.core.model.AudioCodec
import com.aurora.engine.core.model.AudioContainer
import com.aurora.engine.core.model.AudioFormat
import com.aurora.engine.core.model.ErrorCategory
import com.aurora.engine.core.model.PlaybackError
import com.aurora.engine.core.model.PlaybackSource
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

class PlaybackDiagnosticsCollectorTest {

    private var currentTime = 1000L
    private lateinit var collector: PlaybackDiagnosticsCollector

    private val sampleSource = PlaybackSource.Progressive(
        trackId = "track-diag-1",
        url = "https://rr1---sn-4g5edn6e.googlevideo.com/videoplayback?expire=1725890000&sig=SECRET_AUTH_SIG_12345",
        audioFormat = AudioFormat(AudioCodec.OPUS, AudioContainer.WEBM, 160, 48_000, 2)
    )

    @Before
    fun setUp() {
        currentTime = 1000L
        collector = PlaybackDiagnosticsCollector(clock = { currentTime })
    }

    @Test
    fun `prepare and startup latency are accurately recorded`() {
        collector.onPrepareStarted(sampleSource)

        currentTime = 1250L
        collector.onPlayerReady()

        currentTime = 1400L
        collector.onPlaybackStarted()

        val snapshot = collector.getSnapshot()
        assertThat(snapshot.trackId).isEqualTo("track-diag-1")
        assertThat(snapshot.prepareLatencyMs).isEqualTo(250L)
        assertThat(snapshot.startupLatencyMs).isEqualTo(400L)
    }

    @Test
    fun `buffering metrics calculate duration and count`() {
        collector.onBufferingStarted(positionMs = 5000L)
        currentTime = 1300L
        collector.onBufferingEnded()

        collector.onBufferingStarted(positionMs = 6000L)
        currentTime = 1500L
        collector.onBufferingEnded()

        val snapshot = collector.getSnapshot()
        assertThat(snapshot.bufferingCount).isEqualTo(2)
        // (1300-1000) + (1500-1300) = 300 + 200 = 500ms
        assertThat(snapshot.totalBufferingDurationMs).isEqualTo(500L)
    }

    @Test
    fun `seek latency is recorded`() {
        collector.onSeekStarted(fromPositionMs = 2000L, toPositionMs = 15000L)
        currentTime = 1120L
        collector.onSeekCompleted()

        val snapshot = collector.getSnapshot()
        assertThat(snapshot.seekCount).isEqualTo(1)
        assertThat(snapshot.totalSeekLatencyMs).isEqualTo(120L)
    }

    @Test
    fun `URL sanitization scrubs sensitive parameters`() {
        val sanitized = PlaybackDiagnosticsCollector.sanitizeUrl(sampleSource.url)

        assertThat(sanitized).doesNotContain("SECRET_AUTH_SIG_12345")
        assertThat(sanitized).doesNotContain("expire=")
        assertThat(sanitized).contains("rr1---sn-4g5edn6e.googlevideo.com")
        assertThat(sanitized).contains("/videoplayback")
        assertThat(sanitized).endsWith("?[redacted_query]")
    }

    @Test
    fun `error and recovery events are recorded`() {
        val error = PlaybackError(
            code = "IO_TIMEOUT",
            message = "Timeout",
            category = ErrorCategory.NETWORK_FAILURE,
            isRecoverable = true
        )
        collector.onError(error)
        collector.onRecoveryAttempt(1, error, "RETRY_SAME_SOURCE")

        val snapshot = collector.getSnapshot()
        assertThat(snapshot.recoveryAttemptCount).isEqualTo(1)
        assertThat(snapshot.lastError).isEqualTo(error)
    }

    @Test
    fun `header sanitization scrubs cookies authorization and tokens`() {
        val rawHeaders = mapOf(
            "Authorization" to "Bearer ya29.a0AfH6SMA_SECRET_TOKEN",
            "Cookie" to "VISITOR_INFO1_LIVE=abc123xyz; SID=secret_sid_999",
            "Set-Cookie" to "SESSION=sess_555",
            "X-Goog-Visitor-Id" to "visitor-private-id",
            "Accept" to "audio/webm,audio/*",
            "User-Agent" to "Aurora/1.0"
        )

        val sanitized = PlaybackDiagnosticsCollector.sanitizeHeaders(rawHeaders)

        assertThat(sanitized["Authorization"]).isEqualTo("[redacted]")
        assertThat(sanitized["Cookie"]).isEqualTo("[redacted]")
        assertThat(sanitized["Set-Cookie"]).isEqualTo("[redacted]")
        assertThat(sanitized["X-Goog-Visitor-Id"]).isEqualTo("[redacted]")
        assertThat(sanitized["Accept"]).isEqualTo("audio/webm,audio/*")
        assertThat(sanitized["User-Agent"]).isEqualTo("Aurora/1.0")
    }

    @Test
    fun `message sanitization scrubs emails and bearer credentials`() {
        val rawMessage = "User test.user@example.com failed with Bearer secret_token_xyz"
        val sanitized = PlaybackDiagnosticsCollector.sanitizeMessage(rawMessage)

        assertThat(sanitized).doesNotContain("test.user@example.com")
        assertThat(sanitized).doesNotContain("secret_token_xyz")
        assertThat(sanitized).contains("[redacted_email]")
        assertThat(sanitized).contains("Bearer=[redacted]")
    }
}
