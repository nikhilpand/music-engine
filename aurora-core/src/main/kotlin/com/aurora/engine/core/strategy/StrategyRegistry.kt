package com.aurora.engine.core.strategy

import com.aurora.engine.core.provider.ResolutionContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class StrategyRegistry(
    val healthTracker: HealthTracker = HealthTracker(),
    private val defaultFailureThreshold: Int = 3,
    private val defaultResetTimeoutMs: Long = 30_000L,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val strategies = CopyOnWriteArrayList<PlaybackStrategy>()
    private val circuitBreakers = ConcurrentHashMap<String, CircuitBreaker>()

    fun register(strategy: PlaybackStrategy) {
        // Replace existing strategy if already present (supports dynamic updates)
        strategies.removeIf { it.id == strategy.id }
        strategies.add(strategy)
        circuitBreakers.computeIfAbsent(strategy.id) {
            CircuitBreaker(
                strategyId = strategy.id,
                failureThreshold = defaultFailureThreshold,
                resetTimeoutMs = defaultResetTimeoutMs,
                clock = clock
            )
        }
    }

    fun updateStrategy(strategy: PlaybackStrategy) {
        register(strategy)
    }

    fun setStrategyEnabled(strategyId: String, enabled: Boolean) {
        val existing = getStrategy(strategyId) ?: return
        val updated = object : PlaybackStrategy {
            override val id: String = existing.id
            override val priority: Int = existing.priority
            override val capabilities: StrategyCapabilities = existing.capabilities
            override val isEnabled: Boolean = enabled
            override val isRetired: Boolean = existing.isRetired
            override val version: String = existing.version
        }
        register(updated)
    }

    fun retireStrategy(strategyId: String) {
        val existing = getStrategy(strategyId) ?: return
        val updated = object : PlaybackStrategy {
            override val id: String = existing.id
            override val priority: Int = existing.priority
            override val capabilities: StrategyCapabilities = existing.capabilities
            override val isEnabled: Boolean = existing.isEnabled
            override val isRetired: Boolean = true
            override val version: String = existing.version
        }
        register(updated)
    }

    fun rehabilitate(strategyId: String) {
        circuitBreakers[strategyId]?.reset()
        healthTracker.rehabilitate(strategyId)
    }

    fun unregister(strategyId: String) {
        strategies.removeIf { it.id == strategyId }
        circuitBreakers.remove(strategyId)
        healthTracker.reset(strategyId)
    }

    fun getStrategy(strategyId: String): PlaybackStrategy? {
        return strategies.firstOrNull { it.id == strategyId }
    }

    fun getCircuitBreaker(strategyId: String): CircuitBreaker? {
        return circuitBreakers[strategyId]
    }

    fun getAllStrategies(): List<PlaybackStrategy> {
        return strategies.toList()
    }

    fun getEligibleStrategies(context: ResolutionContext): List<PlaybackStrategy> {
        // If a specific strategy is explicitly requested, evaluate it directly
        val forcedId = context.forcedStrategyId
        if (forcedId != null) {
            val strategy = getStrategy(forcedId) ?: return emptyList()
            if (!strategy.isEnabled || strategy.isRetired) return emptyList()
            val cb = circuitBreakers[forcedId]
            return if (cb?.canExecute() != false) listOf(strategy) else emptyList()
        }

        return strategies
            .asSequence()
            .filter { it.isEnabled && !it.isRetired }
            .filter { strategy ->
                // Capability matching
                strategy.capabilities.canSupport(context.targetQuality) &&
                    strategy.capabilities.canSupportAnyCodec(context.preferredCodecs)
            }
            .filter { strategy ->
                // Circuit breaker check
                val cb = circuitBreakers[strategy.id]
                cb?.canExecute() ?: true
            }
            .sortedByDescending { strategy ->
                // Composite score calculation: 40% base priority, 60% dynamic health score
                val healthScore = healthTracker.getScore(strategy.id)
                (strategy.priority * 0.4) + (healthScore * 0.6)
            }
            .toList()
    }

    fun reportSuccess(strategyId: String, latencyMs: Long) {
        healthTracker.recordSuccess(strategyId, latencyMs)
        circuitBreakers[strategyId]?.recordSuccess()
    }

    fun reportFailure(strategyId: String, failureType: FailureType, latencyMs: Long) {
        healthTracker.recordFailure(strategyId, failureType, latencyMs)
        circuitBreakers[strategyId]?.recordFailure(failureType)
    }

    fun clear() {
        strategies.clear()
        circuitBreakers.clear()
        healthTracker.clear()
    }
}
