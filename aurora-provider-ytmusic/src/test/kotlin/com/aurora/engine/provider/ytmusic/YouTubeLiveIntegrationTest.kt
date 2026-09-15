package com.aurora.engine.provider.ytmusic

import com.aurora.engine.core.model.Track
import com.aurora.engine.core.provider.ResolutionContext
import com.aurora.engine.core.provider.ResolutionResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Opt-in live integration tests hitting actual YouTube Music InnerTube servers.
 * Excluded by default from `./gradlew test` (runs only via `./gradlew liveTest`).
 */
@Tag("live-integration")
class YouTubeLiveIntegrationTest {

    @Test
    @DisplayName("Live InnerTube search returns real results")
    fun testLiveSearch() = runBlocking {
        val provider = YouTubeMusicProvider()
        val results = provider.search("The Weeknd")
        assertThat(results).isNotEmpty()
        val first = results.first()
        assertThat(first.id).isNotEmpty()
        assertThat(first.title).isNotEmpty()
    }

    @Test
    @DisplayName("Live track resolution resolves valid playable streams")
    fun testLiveResolvePlayback() = runBlocking {
        val provider = YouTubeMusicProvider()
        val track = Track(
            id = "d38H_rJ0Zek",
            providerId = "ytmusic",
            title = "Starboy",
            artists = listOf(com.aurora.engine.core.model.ArtistRef("art_1", "The Weeknd")),
            durationMs = 230_000L
        )

        val context = ResolutionContext(track = track)
        val result = provider.resolvePlayback(context)

        assertThat(result).isInstanceOf(ResolutionResult.Success::class.java)
        val success = result as ResolutionResult.Success
        assertThat(success.sources).isNotEmpty()
        assertThat(success.primarySource.customCacheKey).isEqualTo("aurora:track:d38H_rJ0Zek")
    }
}
