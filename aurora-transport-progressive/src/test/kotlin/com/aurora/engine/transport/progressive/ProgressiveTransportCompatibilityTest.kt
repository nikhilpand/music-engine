package com.aurora.engine.transport.progressive

import com.aurora.engine.core.model.AudioCodec
import com.aurora.engine.core.model.AudioContainer
import com.aurora.engine.core.model.AudioFormat
import com.aurora.engine.core.model.PlaybackSource
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

class ProgressiveTransportCompatibilityTest {

    private lateinit var transport: ProgressivePlaybackTransport

    @Before
    fun setUp() {
        transport = ProgressivePlaybackTransport()
    }

    @Test
    fun `transportId must be transport-progressive`() {
        assertThat(transport.transportId).isEqualTo("transport-progressive")
    }

    @Test
    fun `canHandle returns true for Progressive source`() {
        val source = PlaybackSource.Progressive(
            trackId = "track-123",
            url = "https://example.com/audio.webm",
            audioFormat = AudioFormat(AudioCodec.OPUS, AudioContainer.WEBM, 160, 48_000, 2)
        )
        assertThat(transport.canHandle(source)).isTrue()
    }

    @Test
    fun `canHandle returns false for Sabr source`() {
        val source = PlaybackSource.Sabr(
            trackId = "track-sabr",
            serverEndpoint = "https://sabr.example.com",
            clientContextJson = "{}",
            audioFormat = AudioFormat(AudioCodec.OPUS, AudioContainer.WEBM, 160, 48_000, 2)
        )
        assertThat(transport.canHandle(source)).isFalse()
    }

    @Test
    fun `canHandle returns false for Hls source`() {
        val source = PlaybackSource.Hls(
            trackId = "track-hls",
            manifestUrl = "https://example.com/master.m3u8",
            audioFormat = AudioFormat(AudioCodec.AAC, AudioContainer.MP4_M4A, 128, 44_100, 2)
        )
        assertThat(transport.canHandle(source)).isFalse()
    }

    @Test
    fun `canHandle returns false for Local source`() {
        val source = PlaybackSource.Local(
            trackId = "track-local",
            filePath = "/sdcard/music/test.mp3",
            audioFormat = AudioFormat(AudioCodec.MP3, AudioContainer.MP3, 320, 44_100, 2)
        )
        assertThat(transport.canHandle(source)).isFalse()
    }
}
