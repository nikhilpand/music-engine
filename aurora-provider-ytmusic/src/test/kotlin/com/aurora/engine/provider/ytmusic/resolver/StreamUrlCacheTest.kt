package com.aurora.engine.provider.ytmusic.resolver

import org.junit.jupiter.api.Test
import com.google.common.truth.Truth.assertThat

class StreamUrlCacheTest {

    @Test
    fun `basic put and get`() {
        val cache = StreamUrlCache()
        val gen = cache.startResolution("track1")
        cache.completeResolution(
            "track1", gen,
            url = "https://example.com/stream",
            expiresAtMs = System.currentTimeMillis() + 60_000,
            clientName = "VISIONOS",
            transportType = StreamUrlCache.TransportType.PROGRESSIVE
        )

        val entry = cache.get("track1")
        assertThat(entry).isNotNull()
        assertThat(entry!!.url).isEqualTo("https://example.com/stream")
        assertThat(entry.clientName).isEqualTo("VISIONOS")
        assertThat(entry.transportType).isEqualTo(StreamUrlCache.TransportType.PROGRESSIVE)
    }

    @Test
    fun `stale generation is discarded`() {
        val cache = StreamUrlCache()
        val gen1 = cache.startResolution("track1")
        val gen2 = cache.startResolution("track1") // supersedes gen1

        // Complete gen1 (stale) — should be rejected
        val stored = cache.completeResolution(
            "track1", gen1,
            url = "https://stale.com",
            expiresAtMs = System.currentTimeMillis() + 60_000,
            clientName = "OLD",
            transportType = StreamUrlCache.TransportType.PROGRESSIVE
        )
        assertThat(stored).isFalse()

        // Complete gen2 (current) — should be accepted
        val stored2 = cache.completeResolution(
            "track1", gen2,
            url = "https://fresh.com",
            expiresAtMs = System.currentTimeMillis() + 60_000,
            clientName = "NEW",
            transportType = StreamUrlCache.TransportType.SABR
        )
        assertThat(stored2).isTrue()
        assertThat(cache.get("track1")!!.url).isEqualTo("https://fresh.com")
    }

    @Test
    fun `expired entry returns null`() {
        val cache = StreamUrlCache()
        val gen = cache.startResolution("track1")
        cache.completeResolution(
            "track1", gen,
            url = "https://expired.com",
            expiresAtMs = System.currentTimeMillis() - 1, // already expired
            clientName = "TEST",
            transportType = StreamUrlCache.TransportType.PROGRESSIVE
        )

        assertThat(cache.get("track1")).isNull()
    }

    @Test
    fun `network change invalidates entries`() {
        val cache = StreamUrlCache()
        val gen = cache.startResolution("track1")
        cache.completeResolution(
            "track1", gen,
            url = "https://wifi.com",
            expiresAtMs = System.currentTimeMillis() + 60_000,
            clientName = "TEST",
            transportType = StreamUrlCache.TransportType.PROGRESSIVE
        )
        assertThat(cache.get("track1")).isNotNull()

        cache.onNetworkChanged()
        assertThat(cache.get("track1")).isNull()
    }

    @Test
    fun `invalidate removes entry`() {
        val cache = StreamUrlCache()
        val gen = cache.startResolution("track1")
        cache.completeResolution(
            "track1", gen,
            url = "https://example.com",
            expiresAtMs = System.currentTimeMillis() + 60_000,
            clientName = "TEST",
            transportType = StreamUrlCache.TransportType.PROGRESSIVE
        )

        cache.invalidate("track1")
        assertThat(cache.get("track1")).isNull()
    }

    @Test
    fun `clear removes everything`() {
        val cache = StreamUrlCache()
        for (i in 1..5) {
            val gen = cache.startResolution("track$i")
            cache.completeResolution(
                "track$i", gen,
                url = "https://example.com/$i",
                expiresAtMs = System.currentTimeMillis() + 60_000,
                clientName = "TEST",
                transportType = StreamUrlCache.TransportType.PROGRESSIVE
            )
        }
        assertThat(cache.size).isEqualTo(5)

        cache.clear()
        assertThat(cache.size).isEqualTo(0)
    }

    @Test
    fun `isResolving tracks in-flight resolutions`() {
        val cache = StreamUrlCache()
        assertThat(cache.isResolving("track1")).isFalse()

        val gen = cache.startResolution("track1")
        assertThat(cache.isResolving("track1")).isTrue()

        cache.completeResolution(
            "track1", gen,
            url = "https://example.com",
            expiresAtMs = System.currentTimeMillis() + 60_000,
            clientName = "TEST",
            transportType = StreamUrlCache.TransportType.PROGRESSIVE
        )
        assertThat(cache.isResolving("track1")).isFalse()
    }

    @Test
    fun `entry metadata is preserved`() {
        val cache = StreamUrlCache()
        val gen = cache.startResolution("track1")
        cache.completeResolution(
            "track1", gen,
            url = "https://example.com",
            expiresAtMs = System.currentTimeMillis() + 60_000,
            clientName = "ANDROID_MUSIC",
            transportType = StreamUrlCache.TransportType.SABR,
            formatId = "251"
        )

        val entry = cache.get("track1")!!
        assertThat(entry.formatId).isEqualTo("251")
        assertThat(entry.transportType).isEqualTo(StreamUrlCache.TransportType.SABR)
        assertThat(entry.remainingMs()).isGreaterThan(0)
        assertThat(entry.isExpired()).isFalse()
    }
}
