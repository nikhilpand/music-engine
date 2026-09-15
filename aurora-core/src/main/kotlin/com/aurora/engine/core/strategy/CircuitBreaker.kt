package com.aurora.engine.core.strategy

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

enum class CircuitState {
    CLOSED,
    HALF_OPEN,
    OPEN
}

class CircuitBreaker(
    val strategyId: String,
    val failureThreshold: Int = 3,
    val resetTimeoutMs: Long = 30_000L,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val state = AtomicReference(CircuitState.CLOSED)
    private val consecutiveFailures = AtomicInteger(0)
    private val lastOpenedTimestamp = AtomicLong(0L)
    private val halfOpenTrialPermitted = AtomicReference(false)

    val currentState: CircuitState
        get() {
            val current = state.get()
            if (current == CircuitState.OPEN) {
                val now = clock()
                val openedAt = lastOpenedTimestamp.get()
                if (now - openedAt >= resetTimeoutMs) {
                    if (state.compareAndSet(CircuitState.OPEN, CircuitState.HALF_OPEN)) {
                        halfOpenTrialPermitted.set(true)
                        return CircuitState.HALF_OPEN
                    }
                }
            }
            return state.get()
        }

    fun canExecute(): Boolean {
        return when (currentState) {
            CircuitState.CLOSED -> true
            CircuitState.HALF_OPEN -> {
                // Allow a single trial request in HALF_OPEN state
                halfOpenTrialPermitted.compareAndSet(true, false)
            }
            CircuitState.OPEN -> false
        }
    }

    fun recordSuccess() {
        consecutiveFailures.set(0)
        state.set(CircuitState.CLOSED)
        halfOpenTrialPermitted.set(false)
    }

    fun recordFailure(failureType: FailureType) {
        if (!failureType.penalizesStrategy) {
            return
        }

        val now = clock()
        val current = currentState

        if (current == CircuitState.HALF_OPEN) {
            // Trial request failed; immediately reopen
            lastOpenedTimestamp.set(now)
            state.set(CircuitState.OPEN)
            halfOpenTrialPermitted.set(false)
            return
        }

        // Weight severe provider rejections more heavily (e.g. 403 or 429 immediately trips or adds 2)
        val increment = if (failureType.isCircuitTripping) 2 else 1
        val failures = consecutiveFailures.addAndGet(increment)

        if (failures >= failureThreshold) {
            lastOpenedTimestamp.set(now)
            state.set(CircuitState.OPEN)
        }
    }

    fun reset() {
        consecutiveFailures.set(0)
        state.set(CircuitState.CLOSED)
        halfOpenTrialPermitted.set(false)
        lastOpenedTimestamp.set(0L)
    }

    fun forceState(newState: CircuitState) {
        state.set(newState)
        if (newState == CircuitState.OPEN) {
            lastOpenedTimestamp.set(clock())
        } else if (newState == CircuitState.CLOSED) {
            consecutiveFailures.set(0)
        }
    }

    fun getConsecutiveFailures(): Int = consecutiveFailures.get()

    fun getLastOpenedTimestamp(): Long = lastOpenedTimestamp.get()
}
