package com.aurora.engine.transport.sabr

import com.aurora.engine.core.model.AudioCodec
import com.aurora.engine.core.model.AudioContainer
import com.aurora.engine.core.model.AudioFormat
import com.aurora.engine.core.model.PlaybackSource
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SabrPlaybackTransportTest {

    private val transport = SabrPlaybackTransport()

    private val sabrSource = PlaybackSource.Sabr(
        trackId = "test-track-1",
        serverEndpoint = "https://rr1.googlevideo.com/videoplayback",
        clientContextJson = """{"videoId":"dQw4w9WgXcQ"}""",
        audioFormat = AudioFormat(
            codec = AudioCodec.OPUS,
            container = AudioContainer.WEBM,
            bitrateKbps = 128,
            sampleRateHz = 48_000,
            mimeType = "audio/webm"
        )
    )

    private val progressiveSource = PlaybackSource.Progressive(
        trackId = "test-track-2",
        url = "https://example.com/audio.mp3",
        audioFormat = AudioFormat(
            codec = AudioCodec.MP3,
            container = AudioContainer.MP3,
            bitrateKbps = 320,
            sampleRateHz = 44_100,
            mimeType = "audio/mpeg"
        )
    )

    @Test
    fun `transportId is correct`() {
        assertThat(transport.transportId).isEqualTo("aurora-transport-sabr")
    }

    @Test
    fun `canHandle returns true for Sabr source`() {
        assertThat(transport.canHandle(sabrSource)).isTrue()
    }

    @Test
    fun `canHandle returns false for Progressive source`() {
        assertThat(transport.canHandle(progressiveSource)).isFalse()
    }

    @Test
    fun `createSession with Sabr source succeeds`() = runTest {
        val session = transport.createSession(sabrSource)
        assertThat(session).isInstanceOf(SabrPlaybackSession::class.java)
        assertThat(session.source).isEqualTo(sabrSource)
        assertThat(session.sessionId).startsWith("sabr-")
        session.close()
    }

    @Test
    fun `createSession with non-Sabr source throws`() = runTest {
        assertThrows<IllegalArgumentException> {
            transport.createSession(progressiveSource)
        }
    }
}
