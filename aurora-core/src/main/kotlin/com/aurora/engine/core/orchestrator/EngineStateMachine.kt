package com.aurora.engine.core.orchestrator

import com.aurora.engine.core.model.EngineState
import com.aurora.engine.core.model.PlaybackError
import com.aurora.engine.core.model.PlaybackSource
import com.aurora.engine.core.model.PlaybackStateSnapshot
import com.aurora.engine.core.model.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicReference

sealed interface TransitionResult {
    data class Success(val oldState: EngineState, val newState: EngineState) : TransitionResult
    data class Rejected(val currentState: EngineState, val targetState: EngineState, val reason: String) : TransitionResult
}

class EngineStateMachine(
    initialState: EngineState = EngineState.IDLE,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    companion object {
        val LEGAL_TRANSITIONS: Map<EngineState, Set<EngineState>> = mapOf(
            EngineState.IDLE to setOf(
                EngineState.RESOLVING,
                EngineState.ERROR
            ),
            EngineState.RESOLVING to setOf(
                EngineState.PREPARING,
                EngineState.IDLE,
                EngineState.ERROR
            ),
            EngineState.PREPARING to setOf(
                EngineState.READY,
                EngineState.BUFFERING,
                EngineState.PLAYING,
                EngineState.RECOVERING,
                EngineState.IDLE,
                EngineState.ERROR
            ),
            EngineState.READY to setOf(
                EngineState.PLAYING,
                EngineState.PAUSED,
                EngineState.BUFFERING,
                EngineState.IDLE,
                EngineState.ERROR
            ),
            EngineState.BUFFERING to setOf(
                EngineState.PLAYING,
                EngineState.PAUSED,
                EngineState.STALLED,
                EngineState.RECOVERING,
                EngineState.IDLE,
                EngineState.ERROR
            ),
            EngineState.PLAYING to setOf(
                EngineState.PAUSED,
                EngineState.BUFFERING,
                EngineState.STALLED,
                EngineState.ENDED,
                EngineState.RECOVERING,
                EngineState.IDLE,
                EngineState.ERROR
            ),
            EngineState.PAUSED to setOf(
                EngineState.PLAYING,
                EngineState.BUFFERING,
                EngineState.IDLE,
                EngineState.ERROR
            ),
            EngineState.STALLED to setOf(
                EngineState.PLAYING,
                EngineState.BUFFERING,
                EngineState.RECOVERING,
                EngineState.PAUSED,
                EngineState.IDLE,
                EngineState.ERROR
            ),
            EngineState.RECOVERING to setOf(
                EngineState.PREPARING,
                EngineState.BUFFERING,
                EngineState.PLAYING,
                EngineState.PAUSED,
                EngineState.IDLE,
                EngineState.ERROR
            ),
            EngineState.ENDED to setOf(
                EngineState.RESOLVING,
                EngineState.IDLE,
                EngineState.ERROR
            ),
            EngineState.ERROR to setOf(
                EngineState.IDLE,
                EngineState.RESOLVING
            )
        )
    }

    private val snapshotRef = AtomicReference(
        PlaybackStateSnapshot(
            state = initialState,
            timestampMs = clock()
        )
    )

    private val _stateFlow = MutableStateFlow(snapshotRef.get())
    val stateFlow: StateFlow<PlaybackStateSnapshot> = _stateFlow.asStateFlow()

    val currentSnapshot: PlaybackStateSnapshot
        get() = snapshotRef.get()

    val currentState: EngineState
        get() = snapshotRef.get().state

    fun canTransitionTo(targetState: EngineState): Boolean {
        val current = snapshotRef.get().state
        if (current == targetState) return true
        val allowed = LEGAL_TRANSITIONS[current] ?: emptySet()
        return allowed.contains(targetState)
    }

    @Synchronized
    fun transitionTo(
        targetState: EngineState,
        track: Track? = snapshotRef.get().currentTrack,
        source: PlaybackSource? = snapshotRef.get().currentSource,
        positionMs: Long = snapshotRef.get().positionMs,
        durationMs: Long = snapshotRef.get().durationMs,
        bufferedPositionMs: Long = snapshotRef.get().bufferedPositionMs,
        playbackSpeed: Float = snapshotRef.get().playbackSpeed,
        isPlaying: Boolean = (targetState == EngineState.PLAYING),
        error: PlaybackError? = if (targetState == EngineState.ERROR) snapshotRef.get().error else null
    ): TransitionResult {
        val current = snapshotRef.get()
        val fromState = current.state

        // Self-transition is a no-op update
        if (fromState == targetState) {
            val updated = current.copy(
                currentTrack = track,
                currentSource = source,
                positionMs = positionMs,
                durationMs = durationMs,
                bufferedPositionMs = bufferedPositionMs,
                playbackSpeed = playbackSpeed,
                isPlaying = isPlaying,
                error = error,
                timestampMs = clock()
            )
            snapshotRef.set(updated)
            _stateFlow.value = updated
            return TransitionResult.Success(fromState, targetState)
        }

        val allowed = LEGAL_TRANSITIONS[fromState] ?: emptySet()
        if (!allowed.contains(targetState)) {
            return TransitionResult.Rejected(
                currentState = fromState,
                targetState = targetState,
                reason = "Illegal transition from $fromState to $targetState. Allowed transitions: $allowed"
            )
        }

        val newSnapshot = PlaybackStateSnapshot(
            state = targetState,
            currentTrack = track,
            currentSource = source,
            positionMs = positionMs,
            durationMs = durationMs,
            bufferedPositionMs = bufferedPositionMs,
            playbackSpeed = playbackSpeed,
            isPlaying = isPlaying,
            error = error,
            timestampMs = clock()
        )

        snapshotRef.set(newSnapshot)
        _stateFlow.value = newSnapshot
        return TransitionResult.Success(fromState, targetState)
    }

    fun transitionToOrThrow(
        targetState: EngineState,
        track: Track? = snapshotRef.get().currentTrack,
        source: PlaybackSource? = snapshotRef.get().currentSource,
        positionMs: Long = snapshotRef.get().positionMs,
        durationMs: Long = snapshotRef.get().durationMs,
        bufferedPositionMs: Long = snapshotRef.get().bufferedPositionMs,
        playbackSpeed: Float = snapshotRef.get().playbackSpeed,
        isPlaying: Boolean = (targetState == EngineState.PLAYING),
        error: PlaybackError? = if (targetState == EngineState.ERROR) snapshotRef.get().error else null
    ): PlaybackStateSnapshot {
        val result = transitionTo(
            targetState = targetState,
            track = track,
            source = source,
            positionMs = positionMs,
            durationMs = durationMs,
            bufferedPositionMs = bufferedPositionMs,
            playbackSpeed = playbackSpeed,
            isPlaying = isPlaying,
            error = error
        )

        return when (result) {
            is TransitionResult.Success -> snapshotRef.get()
            is TransitionResult.Rejected -> throw IllegalStateException(result.reason)
        }
    }

    @Synchronized
    fun updatePosition(positionMs: Long, bufferedPositionMs: Long, durationMs: Long? = null) {
        val current = snapshotRef.get()
        val updated = current.copy(
            positionMs = positionMs,
            bufferedPositionMs = bufferedPositionMs,
            durationMs = durationMs ?: current.durationMs,
            timestampMs = clock()
        )
        snapshotRef.set(updated)
        _stateFlow.value = updated
    }

    @Synchronized
    fun reset() {
        val newSnapshot = PlaybackStateSnapshot(
            state = EngineState.IDLE,
            timestampMs = clock()
        )
        snapshotRef.set(newSnapshot)
        _stateFlow.value = newSnapshot
    }
}
