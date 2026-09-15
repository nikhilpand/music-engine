package com.aurora.engine.core.orchestrator

import com.aurora.engine.core.model.AudioCodec
import com.aurora.engine.core.model.AudioContainer
import com.aurora.engine.core.model.AudioFormat
import com.aurora.engine.core.model.PlaybackSource
import com.aurora.engine.core.model.Track
import com.aurora.engine.core.provider.PlaybackProvider
import com.aurora.engine.core.provider.ResolutionContext
import com.aurora.engine.core.provider.ResolutionResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class ResolutionCoordinatorTest {

    private val sampleTrack = Track(
        id = "song_abc",
        providerId = "ytmusic",
        title = "Deduplication Song",
        artists = listOf(com.aurora.engine.core.model.ArtistRef("art_1", "Artist")),
        durationMs = 200_000L
    )

    private val sampleSource = PlaybackSource.Progressive(
        trackId = "song_abc",
        url = "https://example.com/stream.webm",
        audioFormat = AudioFormat(AudioCodec.OPUS, AudioContainer.WEBM, bitrateKbps = 160)
    )

    private class MockPlaybackProvider(val delayMs: Long = 50L) : PlaybackProvider {
        override val providerId: String = "ytmusic"
        val callCount = AtomicInteger(0)

        override suspend fun resolvePlayback(context: ResolutionContext): ResolutionResult {
            callCount.incrementAndGet()
            if (delayMs > 0) {
                delay(delayMs)
            }
            return ResolutionResult.Success(
                sources = listOf(
                    PlaybackSource.Progressive(
                        trackId = context.track.id,
                        url = "https://example.com/stream.webm",
                        audioFormat = AudioFormat(AudioCodec.OPUS, AudioContainer.WEBM, bitrateKbps = 160)
                    )
                ),
                strategyId = "WEB_REMIX"
            )
        }
    }

    @Test
    @DisplayName("Concurrent resolutions for the same resolution key are deduplicated to a single provider call")
    fun testConcurrentDeduplication() = runBlocking {
        val provider = MockPlaybackProvider(delayMs = 100L)
        val coordinator = ResolutionCoordinator(provider = provider)

        val context = ResolutionContext(track = sampleTrack)

        // Launch 25 concurrent resolution requests for the exact same track
        val deferredList = (1..25).map {
            async {
                coordinator.resolve(context, scope = this)
            }
        }

        val results = deferredList.awaitAll()

        // Verify that all 25 callers received a successful result
        assertThat(results).hasSize(25)
        for (result in results) {
            assertThat(result).isInstanceOf(ResolutionResult.Success::class.java)
            val success = result as ResolutionResult.Success
            assertThat(success.sources.first().trackId).isEqualTo("song_abc")
        }

        // CRITICAL CHECK: The underlying provider must have been invoked exactly ONCE!
        assertThat(provider.callCount.get()).isEqualTo(1)
    }

    @Test
    @DisplayName("Subsequent call within TTL hits in-memory cache without calling provider")
    fun testCacheHit() = runBlocking {
        val provider = MockPlaybackProvider(delayMs = 0L)
        val coordinator = ResolutionCoordinator(provider = provider, defaultCacheTtlMs = 60_000L)
        val context = ResolutionContext(track = sampleTrack)

        val first = coordinator.resolve(context, scope = this)
        val second = coordinator.resolve(context, scope = this)

        assertThat(first).isInstanceOf(ResolutionResult.Success::class.java)
        assertThat(second).isInstanceOf(ResolutionResult.Success::class.java)
        assertThat(provider.callCount.get()).isEqualTo(1)
    }

    @Test
    @DisplayName("Bypassing cache forces a fresh provider call")
    fun testBypassCache() = runBlocking {
        val provider = MockPlaybackProvider(delayMs = 0L)
        val coordinator = ResolutionCoordinator(provider = provider)
        val context = ResolutionContext(track = sampleTrack)

        coordinator.resolve(context, scope = this)
        coordinator.resolve(context, scope = this, bypassCache = true)

        assertThat(provider.callCount.get()).isEqualTo(2)
    }
}
