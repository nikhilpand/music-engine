package com.aurora.engine.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PlaybackSourceTest {

    @Test
    @DisplayName("PlaybackSource implementations enforce customCacheKey default to aurora:track:trackId")
    fun testCustomCacheKeyDefaults() {
        val format = AudioFormat(AudioCodec.OPUS, AudioContainer.WEBM, bitrateKbps = 160)

        val progressive = PlaybackSource.Progressive("track_99", "https://cdn.example.com/audio", format)
        val sabr = PlaybackSource.Sabr("track_99", "https://sabr.example.com", "{}", null, format)
        val hls = PlaybackSource.Hls("track_99", "https://hls.example.com/live.m3u8", format)
        val local = PlaybackSource.Local("track_99", "/data/audio/cache.bin", format)

        assertThat(progressive.customCacheKey).isEqualTo("aurora:track:track_99")
        assertThat(sabr.customCacheKey).isEqualTo("aurora:track:track_99")
        assertThat(hls.customCacheKey).isEqualTo("aurora:track:track_99")
        assertThat(local.customCacheKey).isEqualTo("aurora:track:track_99")
    }

    @Test
    @DisplayName("isExpired correctly computes expiration relative to given timestamp")
    fun testExpiration() {
        val format = AudioFormat(AudioCodec.AAC, AudioContainer.MP4_M4A, bitrateKbps = 128)
        val source = PlaybackSource.Progressive(
            trackId = "t1",
            url = "https://example.com/audio",
            audioFormat = format,
            expiresAtMs = 5000L
        )

        assertThat(source.isExpired(4999L)).isFalse()
        assertThat(source.isExpired(5000L)).isTrue()
        assertThat(source.isExpired(5001L)).isTrue()

        val nonExpiring = PlaybackSource.Local("t1", "/path", format)
        assertThat(nonExpiring.isExpired(9999999L)).isFalse()
    }

    @Test
    @DisplayName("Track model enforces non-blank id and non-negative duration")
    fun testTrackValidation() {
        assertThrows<IllegalArgumentException> {
            Track(
                id = "",
                providerId = "ytmusic",
                title = "Title",
                artists = listOf(ArtistRef("a1", "Artist")),
                durationMs = 1000L
            )
        }

        assertThrows<IllegalArgumentException> {
            Track(
                id = "valid_id",
                providerId = "ytmusic",
                title = "Title",
                artists = listOf(ArtistRef("a1", "Artist")),
                durationMs = -50L
            )
        }
    }

    @Test
    @DisplayName("AudioFormat computes QualityProfile correctly based on codec and bitrate")
    fun testQualityProfileComputation() {
        val flac = AudioFormat(AudioCodec.FLAC, AudioContainer.MATROSKA)
        assertThat(flac.qualityProfile).isEqualTo(QualityProfile.LOSSLESS)

        val highOpus = AudioFormat(AudioCodec.OPUS, AudioContainer.WEBM, bitrateKbps = 256)
        assertThat(highOpus.qualityProfile).isEqualTo(QualityProfile.HIGH)

        val mediumAac = AudioFormat(AudioCodec.AAC, AudioContainer.MP4_M4A, bitrateKbps = 128)
        assertThat(mediumAac.qualityProfile).isEqualTo(QualityProfile.MEDIUM)

        val lowOpus = AudioFormat(AudioCodec.OPUS, AudioContainer.WEBM, bitrateKbps = 48)
        assertThat(lowOpus.qualityProfile).isEqualTo(QualityProfile.LOW)
    }
}
