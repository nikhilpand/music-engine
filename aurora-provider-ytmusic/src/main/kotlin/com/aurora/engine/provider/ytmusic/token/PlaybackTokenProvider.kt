package com.aurora.engine.provider.ytmusic.token

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

interface PlaybackTokenProvider {
    suspend fun getPoToken(videoId: String): String?
    suspend fun getVisitorData(): String?
    fun updateVisitorData(visitorData: String)
    fun invalidate(videoId: String)
    fun clear()
}

class DefaultPlaybackTokenProvider(
    private val tokenTtlMs: Long = 3_600_000L, // 1 hour
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val poTokenGenerator: (suspend (String) -> String?)? = null
) : PlaybackTokenProvider {

    private data class CachedToken(
        val token: String,
        val acquiredAtMs: Long,
        val expiresAtMs: Long
    ) {
        fun isExpired(nowMs: Long): Boolean = nowMs >= expiresAtMs
    }

    private val tokenCache = ConcurrentHashMap<String, CachedToken>()
    private val visitorDataRef = AtomicReference<String?>(null)

    override suspend fun getPoToken(videoId: String): String? {
        val now = clock()
        val cached = tokenCache[videoId]
        if (cached != null && !cached.isExpired(now)) {
            return cached.token
        }

        val generator = poTokenGenerator ?: return null
        val generatedToken = generator(videoId) ?: return null

        tokenCache[videoId] = CachedToken(
            token = generatedToken,
            acquiredAtMs = now,
            expiresAtMs = now + tokenTtlMs
        )

        return generatedToken
    }

    override suspend fun getVisitorData(): String? {
        return visitorDataRef.get()
    }

    override fun updateVisitorData(visitorData: String) {
        if (visitorData.isNotBlank()) {
            visitorDataRef.set(visitorData)
        }
    }

    override fun invalidate(videoId: String) {
        tokenCache.remove(videoId)
    }

    override fun clear() {
        tokenCache.clear()
        visitorDataRef.set(null)
    }

    fun getCachedTokenCount(): Int = tokenCache.size
}
