package com.aurora.engine.core.strategy

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class HealthTrackerTest {

    @Test
    @DisplayName("New strategy starts with baseline score of 100")
    fun testBaselineScore() {
        val tracker = HealthTracker()
        val score = tracker.getScore("new_strategy")
        assertThat(score).isEqualTo(100.0)
    }

    @Test
    @DisplayName("Success recording updates count, latency, and maintains high health score")
    fun testRecordSuccess() {
        val tracker = HealthTracker()
        tracker.recordSuccess("strat_a", latencyMs = 120L)
        tracker.recordSuccess("strat_a", latencyMs = 80L)

        val snapshot = tracker.getSnapshot("strat_a")
        assertThat(snapshot.totalRequests).isEqualTo(2L)
        assertThat(snapshot.successCount).isEqualTo(2L)
        assertThat(snapshot.failureCount).isEqualTo(0L)
        assertThat(snapshot.averageLatencyMs).isEqualTo(100L)
        assertThat(snapshot.score).isEqualTo(100.0)
    }

    @Test
    @DisplayName("Failure classification: severe failure penalizes score more than network glitch")
    fun testFailureClassificationSeverity() {
        var currentTime = 10_000L
        val tracker = HealthTracker(clock = { currentTime })

        // Strategy A suffers a network timeout (penalty weight 1.5)
        tracker.recordFailure("strat_timeout", FailureType.TIMEOUT, latencyMs = 2000L)
        val timeoutScore = tracker.getScore("strat_timeout")

        // Strategy B suffers HTTP 403 provider block (penalty weight 5.0)
        tracker.recordFailure("strat_403", FailureType.HTTP_403_PROVIDER_REJECTION, latencyMs = 150L)
        val botBlockedScore = tracker.getScore("strat_403")

        // 403 penalty must be significantly worse than timeout
        assertThat(botBlockedScore).isLessThan(timeoutScore)
    }

    @Test
    @DisplayName("Exponential decay: health penalty decays over time restoring score")
    fun testExponentialDecay() {
        var currentTime = 100_000L
        val halfLife = 60_000L // 1 minute half life
        val tracker = HealthTracker(decayHalfLifeMs = halfLife, clock = { currentTime })

        // Incur a penalty
        tracker.recordFailure("decay_strat", FailureType.HTTP_403_PROVIDER_REJECTION, latencyMs = 200L)
        val initialPenaltyScore = tracker.getScore("decay_strat")
        assertThat(initialPenaltyScore).isLessThan(70.0)

        // Reset consecutive failures with a single success
        tracker.recordSuccess("decay_strat", latencyMs = 100L)

        val scoreAfterSuccess = tracker.getScore("decay_strat")

        // Advance time by 2 half-lives (120 seconds)
        currentTime += 120_000L
        val decayedScore = tracker.getScore("decay_strat")

        // Score must have substantially recovered towards 100.0
        assertThat(decayedScore).isGreaterThan(scoreAfterSuccess)
    }
}
