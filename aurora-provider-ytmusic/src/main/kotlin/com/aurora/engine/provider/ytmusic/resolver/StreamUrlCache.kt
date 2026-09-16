package com.aurora.engine.provider.ytmusic.resolver

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe, generation-tracked cache for resolved stream URLs.
 *
 * ## Problem
 *
 * When the user rapidly skips tracks, multiple stream resolution requests may be in-flight
 * simultaneously. Without generation tracking, a stale resolution result can overwrite a
 * fresh one, leading to 403 errors from expired URLs.
 *
 * ## Design
 *
 * Each media ID has a monotonically increasing **generation counter**. When a new resolution
 * starts, [startResolution] returns a generation token. When the resolution completes,
 * [completeResolution] stores the result **only if** the generation matches (i.e., no newer
 * resolution has been started in the meantime).
 *
 * ## Cache Entry Metadata
 *
 * Each [CacheEntry] stores:
 * - The resolved URL(s) and their expiry time.
 * - Which client config was used (for debugging and health tracking).
 * - Which transport type the URL is suitable for (SABR vs Progressive).
 * - A network generation stamp to detect stale WiFi→cellular entries.
 */
class StreamUrlCache {

    private val entries = ConcurrentHashMap<String, CacheEntry>()
    private val generationCounters = ConcurrentHashMap<String, AtomicLong>()
    private val activeResolutions = ConcurrentHashMap<String, Long>()

    @Volatile
    var networkGeneration: Long = 0L
        private set

    /**
     * Increment the network generation. Call this when network connectivity changes
     * (WiFi ↔ cellular). All entries from previous generations become stale.
     */
    fun onNetworkChanged() {
        networkGeneration++
    }

    /**
     * Start a new resolution for [mediaId]. Returns a generation token that must be
     * passed to [completeResolution]. Any in-progress resolution for the same mediaId
     * with a lower generation is implicitly cancelled.
     */
    fun startResolution(mediaId: String): Long {
        val counter = generationCounters.computeIfAbsent(mediaId) { AtomicLong(0) }
        val generation = counter.incrementAndGet()
        activeResolutions[mediaId] = generation
        return generation
    }

    /**
     * Complete a resolution and store the result, but **only if** the generation still matches.
     *
     * @return `true` if the entry was stored, `false` if it was discarded (stale generation).
     */
    fun completeResolution(
        mediaId: String,
        generation: Long,
        url: String,
        expiresAtMs: Long,
        clientName: String,
        transportType: TransportType,
        formatId: String? = null
    ): Boolean {
        val currentGeneration = activeResolutions[mediaId] ?: return false
        if (generation != currentGeneration) return false

        entries[mediaId] = CacheEntry(
            url = url,
            expiresAtMs = expiresAtMs,
            clientName = clientName,
            transportType = transportType,
            formatId = formatId,
            networkGeneration = networkGeneration,
            resolvedAtMs = System.currentTimeMillis(),
            generation = generation
        )
        activeResolutions.remove(mediaId)
        return true
    }

    /**
     * Get a cached entry if it exists, is not expired, and matches the current network generation.
     */
    fun get(mediaId: String): CacheEntry? {
        val entry = entries[mediaId] ?: return null

        // Expired?
        if (System.currentTimeMillis() >= entry.expiresAtMs) {
            entries.remove(mediaId)
            return null
        }

        // Stale network generation?
        if (entry.networkGeneration != networkGeneration) {
            entries.remove(mediaId)
            return null
        }

        return entry
    }

    /**
     * Explicitly invalidate a cached entry. Used when a 403 is received or
     * the user seeks beyond the cached URL's byte range.
     */
    fun invalidate(mediaId: String) {
        entries.remove(mediaId)
    }

    /**
     * Clear the entire cache. Used during teardown or account switch.
     */
    fun clear() {
        entries.clear()
        generationCounters.clear()
        activeResolutions.clear()
    }

    /**
     * Number of valid cached entries.
     */
    val size: Int get() = entries.size

    /**
     * Check if a resolution is currently in-flight for [mediaId].
     */
    fun isResolving(mediaId: String): Boolean = activeResolutions.containsKey(mediaId)

    data class CacheEntry(
        val url: String,
        val expiresAtMs: Long,
        val clientName: String,
        val transportType: TransportType,
        val formatId: String?,
        val networkGeneration: Long,
        val resolvedAtMs: Long,
        val generation: Long
    ) {
        fun isExpired(): Boolean = System.currentTimeMillis() >= expiresAtMs

        fun remainingMs(): Long = (expiresAtMs - System.currentTimeMillis()).coerceAtLeast(0)
    }

    enum class TransportType {
        PROGRESSIVE,
        SABR
    }
}
