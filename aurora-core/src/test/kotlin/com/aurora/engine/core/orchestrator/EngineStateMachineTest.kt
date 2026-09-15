package com.aurora.engine.core.orchestrator

import com.aurora.engine.core.model.EngineState
import com.aurora.engine.core.model.ErrorCategory
import com.aurora.engine.core.model.PlaybackError
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class EngineStateMachineTest {

    private lateinit var stateMachine: EngineStateMachine

    @BeforeEach
    fun setUp() {
        stateMachine = EngineStateMachine(initialState = EngineState.IDLE)
    }

    @Test
    @DisplayName("Initial state must be IDLE with null track and zero position")
    fun testInitialState() {
        assertThat(stateMachine.currentState).isEqualTo(EngineState.IDLE)
        val snapshot = stateMachine.currentSnapshot
        assertThat(snapshot.currentTrack).isNull()
        assertThat(snapshot.positionMs).isEqualTo(0L)
        assertThat(snapshot.isPlaying).isFalse()
    }

    @Test
    @DisplayName("Valid transition path: IDLE -> RESOLVING -> PREPARING -> READY -> PLAYING")
    fun testHappyPlaybackTransitions() {
        var result = stateMachine.transitionTo(EngineState.RESOLVING)
        assertThat(result).isInstanceOf(TransitionResult.Success::class.java)
        assertThat(stateMachine.currentState).isEqualTo(EngineState.RESOLVING)

        result = stateMachine.transitionTo(EngineState.PREPARING)
        assertThat(result).isInstanceOf(TransitionResult.Success::class.java)
        assertThat(stateMachine.currentState).isEqualTo(EngineState.PREPARING)

        result = stateMachine.transitionTo(EngineState.READY)
        assertThat(result).isInstanceOf(TransitionResult.Success::class.java)
        assertThat(stateMachine.currentState).isEqualTo(EngineState.READY)

        result = stateMachine.transitionTo(EngineState.PLAYING)
        assertThat(result).isInstanceOf(TransitionResult.Success::class.java)
        assertThat(stateMachine.currentState).isEqualTo(EngineState.PLAYING)
        assertThat(stateMachine.currentSnapshot.isPlaying).isTrue()
    }

    @Test
    @DisplayName("Invalid transitions must be rejected gracefully without state mutation")
    fun testInvalidTransitionsRejected() {
        // IDLE -> PLAYING is illegal (must go through RESOLVING / PREPARING)
        val result = stateMachine.transitionTo(EngineState.PLAYING)
        assertThat(result).isInstanceOf(TransitionResult.Rejected::class.java)
        val rejected = result as TransitionResult.Rejected
        assertThat(rejected.currentState).isEqualTo(EngineState.IDLE)
        assertThat(rejected.targetState).isEqualTo(EngineState.PLAYING)
        assertThat(stateMachine.currentState).isEqualTo(EngineState.IDLE)
    }

    @Test
    @DisplayName("transitionToOrThrow throws IllegalStateException on illegal transition")
    fun testTransitionToOrThrow() {
        val exception = assertThrows<IllegalStateException> {
            stateMachine.transitionToOrThrow(EngineState.PAUSED)
        }
        assertThat(exception.message).contains("Illegal transition from IDLE to PAUSED")
    }

    @Test
    @DisplayName("Self-transition is permitted as a no-op state update")
    fun testSelfTransition() {
        stateMachine.transitionTo(EngineState.RESOLVING)
        val result = stateMachine.transitionTo(EngineState.RESOLVING)
        assertThat(result).isInstanceOf(TransitionResult.Success::class.java)
        assertThat(stateMachine.currentState).isEqualTo(EngineState.RESOLVING)
    }

    @Test
    @DisplayName("Stall, Recovery, and Resumption transition cycle")
    fun testStallRecoveryCycle() {
        stateMachine.transitionTo(EngineState.RESOLVING)
        stateMachine.transitionTo(EngineState.PREPARING)
        stateMachine.transitionTo(EngineState.PLAYING)

        // PLAYING -> STALLED
        val stallResult = stateMachine.transitionTo(EngineState.STALLED)
        assertThat(stallResult).isInstanceOf(TransitionResult.Success::class.java)
        assertThat(stateMachine.currentState).isEqualTo(EngineState.STALLED)

        // STALLED -> RECOVERING
        val recoveryResult = stateMachine.transitionTo(EngineState.RECOVERING)
        assertThat(recoveryResult).isInstanceOf(TransitionResult.Success::class.java)
        assertThat(stateMachine.currentState).isEqualTo(EngineState.RECOVERING)

        // RECOVERING -> PLAYING
        val resumeResult = stateMachine.transitionTo(EngineState.PLAYING)
        assertThat(resumeResult).isInstanceOf(TransitionResult.Success::class.java)
        assertThat(stateMachine.currentState).isEqualTo(EngineState.PLAYING)
    }

    @Test
    @DisplayName("Error transition retains error payload and allows reset to IDLE")
    fun testErrorTransitionAndReset() {
        stateMachine.transitionTo(EngineState.RESOLVING)

        val error = PlaybackError(
            code = "STREAM_403",
            message = "Forbidden",
            category = ErrorCategory.PROVIDER_BOT_DETECTION,
            isRecoverable = false
        )

        stateMachine.transitionTo(EngineState.ERROR, error = error)
        assertThat(stateMachine.currentState).isEqualTo(EngineState.ERROR)
        assertThat(stateMachine.currentSnapshot.error).isEqualTo(error)

        // From ERROR, legal transition to IDLE
        stateMachine.transitionTo(EngineState.IDLE)
        assertThat(stateMachine.currentState).isEqualTo(EngineState.IDLE)
        assertThat(stateMachine.currentSnapshot.error).isNull()
    }

    @Test
    @DisplayName("Position and buffer updates do not change engine state")
    fun testPositionUpdates() {
        stateMachine.transitionTo(EngineState.RESOLVING)
        stateMachine.transitionTo(EngineState.PREPARING)
        stateMachine.transitionTo(EngineState.PLAYING)

        stateMachine.updatePosition(positionMs = 15000L, bufferedPositionMs = 30000L, durationMs = 180000L)
        val snapshot = stateMachine.currentSnapshot

        assertThat(snapshot.state).isEqualTo(EngineState.PLAYING)
        assertThat(snapshot.positionMs).isEqualTo(15000L)
        assertThat(snapshot.bufferedPositionMs).isEqualTo(30000L)
        assertThat(snapshot.durationMs).isEqualTo(180000L)
        assertThat(snapshot.progressFraction).isWithin(0.01f).of(15000f / 180000f)
    }
}
