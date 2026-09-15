package com.aurora.engine.provider.ytmusic.token

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class PlaybackTokenProviderTest {

    @Test
    @DisplayName("TokenProvider caches tokens by videoId and respects TTL")
    fun testTokenCachingAndTtl() = runBlocking {
        var currentTime = 1000L
        val callCount = AtomicInteger(0)

        val provider = DefaultPlaybackTokenProvider(
            tokenTtlMs = 60_000L,
            clock = { currentTime },
            poTokenGenerator = { videoId ->
                callCount.incrementAndGet()
                "token_for_${videoId}_at_$currentTime"
            }
        )

        // First call generates token
        val t1 = provider.getPoToken("video_1")
        assertThat(t1).isEqualTo("token_for_video_1_at_1000")
        assertThat(callCount.get()).isEqualTo(1)

        // Second call within TTL returns cached token without calling generator
        val t2 = provider.getPoToken("video_1")
        assertThat(t2).isEqualTo("token_for_video_1_at_1000")
        assertThat(callCount.get()).isEqualTo(1)

        // Advance time past TTL (60s)
        currentTime += 60_001L

        // Third call regenerates token
        val t3 = provider.getPoToken("video_1")
        assertThat(t3).isEqualTo("token_for_video_1_at_61001")
        assertThat(callCount.get()).isEqualTo(2)
    }

    @Test
    @DisplayName("Invalidating token forces fresh acquisition on next request")
    fun testInvalidate() = runBlocking {
        val callCount = AtomicInteger(0)
        val provider = DefaultPlaybackTokenProvider(
            poTokenGenerator = { callCount.incrementAndGet(); "token_val" }
        )

        provider.getPoToken("vid_a")
        assertThat(callCount.get()).isEqualTo(1)

        provider.invalidate("vid_a")
        provider.getPoToken("vid_a")
        assertThat(callCount.get()).isEqualTo(2)
    }

    @Test
    @DisplayName("VisitorData can be updated and retrieved safely")
    fun testVisitorData() = runBlocking {
        val provider = DefaultPlaybackTokenProvider()
        assertThat(provider.getVisitorData()).isNull()

        provider.updateVisitorData("CgtYWGpQcGlrNXk1QSib9_WwBjIKCgJVUxIEGgAgTw%3D%3D")
        assertThat(provider.getVisitorData()).isEqualTo("CgtYWGpQcGlrNXk1QSib9_WwBjIKCgJVUxIEGgAgTw%3D%3D")
    }
}
