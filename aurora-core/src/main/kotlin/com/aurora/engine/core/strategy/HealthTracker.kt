package com.aurora.engine.core.strategy

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

enum class FailureType(
    val severityWeight: Double,
    val isCircuitTripping: Boolean,
    val penalizesStrategy: Boolean = severityWeight > 0.0
) {
    SUCCESS(0.0, false, false),
    AUTHENTICATION_REQUIRED(0.0, false, false),
    NETWORK_FAILURE(0.0, false, false),
    UNPLAYABLE(0.0, false, false),
    CONTENT_RESTRICTION(0.0, false, false),
    TIMEOUT(1.5, false, true),
    TRANSFORMATION_REQUIRED(1.5, false, true),
    TOKEN_FAILURE(1.5, false, true),
    INVALID_RESPONSE(3.0, true, true),
    RATE_LIMITED(4.0, true, true),
    PROVIDER_REJECTION(4.5, true, true),
    BOT_DETECTION(5.0, true, true),

    // Backward compatibility aliases
    HTTP_403_PROVIDER_REJECTION(5.0, true, true),
    HTTP_429_RATE_LIMITED(4.0, true, true),
    STARTUP_FAILURE(2.0, false, true),
    MID_STREAM_FAILURE(1.5, false, true)
}

data class StrategyHealthSnapshot(
    val strategyId: String,
    val totalRequests: Long,
    val successCount: Long,
    val failureCount: Long,
    val consecutiveFailures: Int,
    val score: Double,
    val averageLatencyMs: Long,
    val lastSuccessTimestampMs: Long,
    val lastFailureTimestampMs: Long
)

class HealthTracker(
    private val decayHalfLifeMs: Long = 300_000L, // 5 minutes
    private val baselineScore: Double = 100.0,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val strategyStats = ConcurrentHashMap<String, StrategyRecord>()

    private class StrategyRecord(
        val totalRequests: AtomicLong = AtomicLong(0),
        val successCount: AtomicLong = AtomicLong(0),
        val failureCount: AtomicLong = AtomicLong(0),
        val totalLatencyMs: AtomicLong = AtomicLong(0),
        val consecutiveFailures: AtomicLong = AtomicLong(0),
        val lastSuccessTime: AtomicLong = AtomicLong(0),
        val lastFailureTime: AtomicLong = AtomicLong(0),
        val decayedPenalty: AtomicReference<Double> = AtomicReference(0.0),
        val lastPenaltyUpdateTime: AtomicLong = AtomicLong(0)
    )

    fun recordSuccess(strategyId: String, latencyMs: Long) {
        val record = strategyStats.computeIfAbsent(strategyId) { StrategyRecord() }
        val now = clock()

        record.totalRequests.incrementAndGet()
        record.successCount.incrementAndGet()
        record.totalLatencyMs.addAndGet(max(0L, latencyMs))
        record.consecutiveFailures.set(0)
        record.lastSuccessTime.set(now)

        applyDecay(record, now, additionalPenalty = 0.0)
    }

    fun recordFailure(strategyId: String, failureType: FailureType, latencyMs: Long) {
        val record = strategyStats.computeIfAbsent(strategyId) { StrategyRecord() }
        val now = clock()

        record.totalRequests.incrementAndGet()
        record.failureCount.incrementAndGet()
        record.totalLatencyMs.addAndGet(max(0L, latencyMs))
        if (failureType.penalizesStrategy) {
            record.consecutiveFailures.incrementAndGet()
        }
        record.lastFailureTime.set(now)

        val penalty = if (failureType.penalizesStrategy) failureType.severityWeight * 10.0 else 0.0
        applyDecay(record, now, additionalPenalty = penalty)
    }

    fun getScore(strategyId: String): Double {
        val record = strategyStats[strategyId] ?: return baselineScore
        val now = clock()
        val currentPenalty = getDecayedPenalty(record, now)
        val consecutiveFailures = record.consecutiveFailures.get()

        // Formula: baseline - decayed penalty - exponential penalty for consecutive failures
        val consecutiveMultiplier = if (consecutiveFailures > 0) consecutiveFailures * 15.0 else 0.0
        val finalScore = baselineScore - currentPenalty - consecutiveMultiplier

        return finalScore.coerceIn(0.0, baselineScore)
    }

    fun getSnapshot(strategyId: String): StrategyHealthSnapshot {
        val record = strategyStats.computeIfAbsent(strategyId) { StrategyRecord() }
        val requests = record.totalRequests.get()
        val totalLatency = record.totalLatencyMs.get()
        val avgLatency = if (requests > 0) totalLatency / requests else 0L

        return StrategyHealthSnapshot(
            strategyId = strategyId,
            totalRequests = requests,
            successCount = record.successCount.get(),
            failureCount = record.failureCount.get(),
            consecutiveFailures = record.consecutiveFailures.get().toInt(),
            score = getScore(strategyId),
            averageLatencyMs = avgLatency,
            lastSuccessTimestampMs = record.lastSuccessTime.get(),
            lastFailureTimestampMs = record.lastFailureTime.get()
        )
    }

    fun rehabilitate(strategyId: String) {
        val record = strategyStats[strategyId] ?: return
        record.consecutiveFailures.set(0)
        record.decayedPenalty.set(0.0)
        record.lastPenaltyUpdateTime.set(clock())
    }

    fun reset(strategyId: String) {
        strategyStats.remove(strategyId)
    }

    fun clear() {
        strategyStats.clear()
    }

    private fun applyDecay(record: StrategyRecord, now: Long, additionalPenalty: Double) {
        while (true) {
            val lastUpdate = record.lastPenaltyUpdateTime.get()
            val current = record.decayedPenalty.get()
            val elapsed = max(0L, now - lastUpdate)

            val lambda = ln(2.0) / decayHalfLifeMs
            val decayed = if (lastUpdate == 0L) 0.0 else current * exp(-lambda * elapsed)
            val updated = min(100.0, decayed + additionalPenalty)

            if (record.decayedPenalty.compareAndSet(current, updated)) {
                record.lastPenaltyUpdateTime.set(now)
                break
            }
        }
    }

    private fun getDecayedPenalty(record: StrategyRecord, now: Long): Double {
        val lastUpdate = record.lastPenaltyUpdateTime.get()
        if (lastUpdate == 0L) return 0.0
        val current = record.decayedPenalty.get()
        val elapsed = max(0L, now - lastUpdate)
        val lambda = ln(2.0) / decayHalfLifeMs
        return current * exp(-lambda * elapsed)
    }
}
