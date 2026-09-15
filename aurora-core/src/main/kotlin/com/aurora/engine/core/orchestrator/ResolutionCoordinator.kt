package com.aurora.engine.core.orchestrator

import com.aurora.engine.core.provider.PlaybackProvider
import com.aurora.engine.core.provider.ResolutionContext
import com.aurora.engine.core.provider.ResolutionResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

class ResolutionCoordinator(
    private val provider: PlaybackProvider,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val defaultCacheTtlMs: Long = 300_000L // 5 minutes default in-memory cache
) {
    // Stores in-flight resolution jobs by logical resolution key
    private val inFlightResolutions = ConcurrentHashMap<String, Deferred<ResolutionResult>>()

    // Short-term in-memory cache for resolved sources
    private val resolutionCache = ConcurrentHashMap<String, CachedResolution>()

    private val mutex = Mutex()

    data class CachedResolution(
        val result: ResolutionResult.Success,
        val cachedAtMs: Long,
        val expiresAtMs: Long
    ) {
        fun isExpired(nowMs: Long): Boolean = nowMs >= expiresAtMs
    }

    suspend fun resolve(
        context: ResolutionContext,
        scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
        bypassCache: Boolean = false
    ): ResolutionResult {
        val key = context.resolutionKey
        val now = clock()

        // 1. Check in-memory cache if not bypassing
        if (!bypassCache) {
            val cached = resolutionCache[key]
            if (cached != null && !cached.isExpired(now)) {
                return cached.result
            } else if (cached != null) {
                resolutionCache.remove(key)
            }
        }

        // 2. Check or register in-flight deduplication
        var deferredToAwait: Deferred<ResolutionResult>
        var wasCreated = false

        mutex.withLock {
            val existing = inFlightResolutions[key]
            if (existing != null && existing.isActive) {
                deferredToAwait = existing
            } else {
                val newDeferred = scope.async {
                    executeResolution(context)
                }
                inFlightResolutions[key] = newDeferred
                deferredToAwait = newDeferred
                wasCreated = true
            }
        }

        try {
            val result = deferredToAwait.await()

            // 3. Update cache on success
            if (result is ResolutionResult.Success) {
                val expiry = result.expiresAtMs ?: (now + defaultCacheTtlMs)
                resolutionCache[key] = CachedResolution(
                    result = result,
                    cachedAtMs = now,
                    expiresAtMs = expiry
                )
            }

            return result
        } finally {
            // Remove from in-flight map if we were the initiator or if the job completed
            if (wasCreated || deferredToAwait.isCompleted) {
                mutex.withLock {
                    inFlightResolutions.remove(key, deferredToAwait)
                }
            }
        }
    }

    private suspend fun executeResolution(context: ResolutionContext): ResolutionResult {
        val startTime = clock()
        return try {
            val result = provider.resolvePlayback(context)
            val elapsed = clock() - startTime
            when (result) {
                is ResolutionResult.Success -> result.copy(latencyMs = elapsed)
                is ResolutionResult.Failure -> result.copy(latencyMs = elapsed)
            }
        } catch (t: Throwable) {
            val elapsed = clock() - startTime
            ResolutionResult.Failure(
                error = com.aurora.engine.core.model.PlaybackError(
                    code = "UNHANDLED_RESOLUTION_EXCEPTION",
                    message = t.message ?: "Unknown exception during resolution",
                    category = com.aurora.engine.core.model.ErrorCategory.UNKNOWN,
                    isRecoverable = false
                ),
                strategyId = context.forcedStrategyId,
                canFallback = true,
                latencyMs = elapsed
            )
        }
    }

    fun getInFlightCount(): Int = inFlightResolutions.size

    fun getCacheSize(): Int = resolutionCache.size

    fun clearCache() {
        resolutionCache.clear()
    }

    fun evict(key: String) {
        resolutionCache.remove(key)
    }
}
