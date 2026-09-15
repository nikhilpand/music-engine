package com.aurora.engine.core.strategy

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class CircuitBreakerTest {

    @Test
    @DisplayName("CircuitBreaker starts CLOSED and permits execution")
    fun testInitialState() {
        val cb = CircuitBreaker(strategyId = "test_strat", failureThreshold = 3)
        assertThat(cb.currentState).isEqualTo(CircuitState.CLOSED)
        assertThat(cb.canExecute()).isTrue()
    }

    @Test
    @DisplayName("Consecutive normal failures open the circuit when threshold reached")
    fun testOpensOnThreshold() {
        val cb = CircuitBreaker(strategyId = "test_strat", failureThreshold = 3)

        cb.recordFailure(FailureType.TIMEOUT)
        assertThat(cb.currentState).isEqualTo(CircuitState.CLOSED)
        assertThat(cb.canExecute()).isTrue()

        cb.recordFailure(FailureType.TIMEOUT)
        assertThat(cb.currentState).isEqualTo(CircuitState.CLOSED)
        assertThat(cb.canExecute()).isTrue()

        cb.recordFailure(FailureType.TIMEOUT)
        // 3 failures reached threshold
        assertThat(cb.currentState).isEqualTo(CircuitState.OPEN)
        assertThat(cb.canExecute()).isFalse()
    }

    @Test
    @DisplayName("Network and authentication failures do not poison circuit breaker")
    fun testNetworkAndAuthDoNotPoison() {
        val cb = CircuitBreaker(strategyId = "test_strat", failureThreshold = 2)

        cb.recordFailure(FailureType.NETWORK_FAILURE)
        cb.recordFailure(FailureType.AUTHENTICATION_REQUIRED)
        cb.recordFailure(FailureType.NETWORK_FAILURE)

        assertThat(cb.getConsecutiveFailures()).isEqualTo(0)
        assertThat(cb.currentState).isEqualTo(CircuitState.CLOSED)
        assertThat(cb.canExecute()).isTrue()
    }

    @Test
    @DisplayName("Severe provider 403 failure increments penalty weight and trips faster")
    fun testSevereProviderRejectionTripsFaster() {
        val cb = CircuitBreaker(strategyId = "test_strat", failureThreshold = 3)

        // Severe failure adds 2 to consecutive count
        cb.recordFailure(FailureType.HTTP_403_PROVIDER_REJECTION)
        assertThat(cb.getConsecutiveFailures()).isEqualTo(2)
        assertThat(cb.currentState).isEqualTo(CircuitState.CLOSED)

        // Second failure pushes over threshold (2 + 2 = 4 >= 3)
        cb.recordFailure(FailureType.HTTP_403_PROVIDER_REJECTION)
        assertThat(cb.currentState).isEqualTo(CircuitState.OPEN)
        assertThat(cb.canExecute()).isFalse()
    }

    @Test
    @DisplayName("Circuit recovers via HALF_OPEN after reset timeout and closes on success")
    fun testCircuitRecoveryFlow() {
        var currentTime = 1000L
        val cb = CircuitBreaker(
            strategyId = "test_strat",
            failureThreshold = 2,
            resetTimeoutMs = 10_000L,
            clock = { currentTime }
        )

        cb.recordFailure(FailureType.TIMEOUT)
        cb.recordFailure(FailureType.TIMEOUT)
        assertThat(cb.currentState).isEqualTo(CircuitState.OPEN)
        assertThat(cb.canExecute()).isFalse()

        // Advance time past reset timeout
        currentTime += 10_001L

        // Should transition to HALF_OPEN and allow a trial request
        assertThat(cb.currentState).isEqualTo(CircuitState.HALF_OPEN)
        assertThat(cb.canExecute()).isTrue()
        // Second call before trial finishes is denied
        assertThat(cb.canExecute()).isFalse()

        // Trial succeeded! Circuit closes
        cb.recordSuccess()
        assertThat(cb.currentState).isEqualTo(CircuitState.CLOSED)
        assertThat(cb.getConsecutiveFailures()).isEqualTo(0)
        assertThat(cb.canExecute()).isTrue()
    }

    @Test
    @DisplayName("Trial failure in HALF_OPEN state immediately re-opens circuit")
    fun testHalfOpenTrialFailure() {
        var currentTime = 1000L
        val cb = CircuitBreaker(
            strategyId = "test_strat",
            failureThreshold = 1,
            resetTimeoutMs = 5_000L,
            clock = { currentTime }
        )

        cb.recordFailure(FailureType.HTTP_429_RATE_LIMITED)
        assertThat(cb.currentState).isEqualTo(CircuitState.OPEN)

        currentTime += 5_001L
        assertThat(cb.currentState).isEqualTo(CircuitState.HALF_OPEN)
        assertThat(cb.canExecute()).isTrue()

        // Trial fails
        cb.recordFailure(FailureType.HTTP_429_RATE_LIMITED)
        assertThat(cb.currentState).isEqualTo(CircuitState.OPEN)
        assertThat(cb.canExecute()).isFalse()
    }
}
