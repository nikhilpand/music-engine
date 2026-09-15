package com.aurora.engine.player.android.controller

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.aurora.engine.core.model.EngineState
import com.aurora.engine.core.model.PlaybackSource
import com.aurora.engine.core.model.Track
import com.aurora.engine.core.orchestrator.EngineStateMachine
import com.aurora.engine.player.android.error.PlaybackErrorMapper
import java.util.concurrent.atomic.AtomicBoolean

class PlayerStateSynchronizer(
    private val stateMachine: EngineStateMachine,
    private val errorMapper: PlaybackErrorMapper = PlaybackErrorMapper,
    private val onErrorOccurred: ((PlaybackException) -> Unit)? = null
) : Player.Listener {

    private val wasPlayingBeforeBuffering = AtomicBoolean(false)
    private var currentTrack: Track? = null
    private var currentSource: PlaybackSource? = null

    fun setCurrentTrackAndSource(track: Track?, source: PlaybackSource?) {
        this.currentTrack = track
        this.currentSource = source
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        when (playbackState) {
            Player.STATE_IDLE -> {
                // If there's an error, onPlayerError handles it; otherwise idle
                if (stateMachine.currentState != EngineState.ERROR &&
                    stateMachine.currentState != EngineState.IDLE
                ) {
                    if (stateMachine.canTransitionTo(EngineState.IDLE)) {
                        stateMachine.transitionTo(
                            targetState = EngineState.IDLE,
                            track = currentTrack,
                            source = currentSource
                        )
                    }
                }
            }

            Player.STATE_BUFFERING -> {
                if (wasPlayingBeforeBuffering.get() || stateMachine.currentState == EngineState.PLAYING) {
                    // Buffer starved mid-playback -> STALLED
                    wasPlayingBeforeBuffering.set(true)
                    if (stateMachine.canTransitionTo(EngineState.STALLED)) {
                        stateMachine.transitionTo(
                            targetState = EngineState.STALLED,
                            track = currentTrack,
                            source = currentSource
                        )
                    }
                } else {
                    // Normal initial buffering or buffering while paused/preparing
                    if (stateMachine.canTransitionTo(EngineState.BUFFERING)) {
                        stateMachine.transitionTo(
                            targetState = EngineState.BUFFERING,
                            track = currentTrack,
                            source = currentSource
                        )
                    }
                }
            }

            Player.STATE_READY -> {
                wasPlayingBeforeBuffering.set(false)
            }

            Player.STATE_ENDED -> {
                wasPlayingBeforeBuffering.set(false)
                if (stateMachine.canTransitionTo(EngineState.ENDED)) {
                    stateMachine.transitionTo(
                        targetState = EngineState.ENDED,
                        track = currentTrack,
                        source = currentSource
                    )
                }
            }
        }
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        syncPlayingOrPausedState(playWhenReady)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) {
            wasPlayingBeforeBuffering.set(false)
            if (stateMachine.canTransitionTo(EngineState.PLAYING)) {
                stateMachine.transitionTo(
                    targetState = EngineState.PLAYING,
                    track = currentTrack,
                    source = currentSource,
                    isPlaying = true
                )
            }
        } else if (stateMachine.currentState == EngineState.PLAYING) {
            if (stateMachine.canTransitionTo(EngineState.PAUSED)) {
                stateMachine.transitionTo(
                    targetState = EngineState.PAUSED,
                    track = currentTrack,
                    source = currentSource,
                    isPlaying = false
                )
            }
        }
    }

    fun syncPlayingOrPausedState(playWhenReady: Boolean) {
        val current = stateMachine.currentState
        if (playWhenReady) {
            if (current != EngineState.PLAYING && stateMachine.canTransitionTo(EngineState.PLAYING)) {
                stateMachine.transitionTo(
                    targetState = EngineState.PLAYING,
                    track = currentTrack,
                    source = currentSource,
                    isPlaying = true
                )
            }
        } else {
            if (current == EngineState.PLAYING && stateMachine.canTransitionTo(EngineState.PAUSED)) {
                stateMachine.transitionTo(
                    targetState = EngineState.PAUSED,
                    track = currentTrack,
                    source = currentSource,
                    isPlaying = false
                )
            }
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        wasPlayingBeforeBuffering.set(false)
        val playbackError = errorMapper.map(
            playbackException = error,
            currentSource = currentSource
        )

        if (stateMachine.canTransitionTo(EngineState.ERROR)) {
            stateMachine.transitionTo(
                targetState = EngineState.ERROR,
                track = currentTrack,
                source = currentSource,
                error = playbackError
            )
        }

        onErrorOccurred?.invoke(error)
    }

    fun updateProgress(player: Player) {
        val positionMs = player.currentPosition.coerceAtLeast(0L)
        val bufferedMs = player.bufferedPosition.coerceAtLeast(0L)
        val durationMs = if (player.duration > 0) player.duration else 0L
        stateMachine.updatePosition(positionMs, bufferedMs, durationMs)
    }

    companion object {
        /**
         * Canonical Media3 state to Aurora EngineState mapping table:
         * - STATE_IDLE -> IDLE
         * - STATE_BUFFERING -> BUFFERING (or STALLED if mid-playback)
         * - STATE_READY -> PLAYING (if playWhenReady=true) / PAUSED (if playWhenReady=false)
         * - STATE_ENDED -> ENDED
         */
        val MEDIA3_TO_AURORA_STATE_MAP: Map<Int, EngineState> = mapOf(
            Player.STATE_IDLE to EngineState.IDLE,
            Player.STATE_BUFFERING to EngineState.BUFFERING,
            Player.STATE_READY to EngineState.READY,
            Player.STATE_ENDED to EngineState.ENDED
        )
    }
}
