package com.aurora.engine.player.android.controller

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.aurora.engine.core.model.EngineState
import com.aurora.engine.core.model.ErrorCategory
import com.aurora.engine.core.model.PlaybackError
import com.aurora.engine.core.model.PlaybackSource
import com.aurora.engine.core.model.Track
import com.aurora.engine.core.orchestrator.EngineStateMachine
import com.aurora.engine.player.android.audio.AudioFocusListener
import com.aurora.engine.player.android.audio.AudioFocusManager
import com.aurora.engine.player.android.diagnostics.PlaybackDiagnosticsCollector
import com.aurora.engine.player.android.error.PlaybackErrorMapper
import com.aurora.engine.player.android.network.NetworkConnectivityMonitor
import com.aurora.engine.player.android.network.NetworkStatus
import com.aurora.engine.player.android.recovery.PlaybackRecoveryCoordinator
import com.aurora.engine.player.android.recovery.RecoveryAction
import com.aurora.engine.player.android.service.AuroraMediaSessionService
import com.aurora.engine.transport.progressive.ProgressivePlaybackTransport
import com.aurora.engine.transport.progressive.session.ProgressivePlaybackSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class AuroraAndroidPlayerController(
    private val context: Context,
    val stateMachine: EngineStateMachine,
    private val progressiveTransport: ProgressivePlaybackTransport = ProgressivePlaybackTransport(),
    val recoveryCoordinator: PlaybackRecoveryCoordinator = PlaybackRecoveryCoordinator(),
    val diagnostics: PlaybackDiagnosticsCollector = PlaybackDiagnosticsCollector(),
    val exoPlayerProvider: ((Context) -> ExoPlayer)? = null
) : AudioFocusListener {

    private var exoPlayer: ExoPlayer? = null
    private var activeSession: ProgressivePlaybackSession? = null
    private var synchronizer: PlayerStateSynchronizer? = null
    private val isReleased = AtomicBoolean(false)

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var progressPollingJob: Job? = null

    // Rapid seek coalescing
    private var pendingSeekPositionMs: Long? = null
    private var seekJob: Job? = null

    // Audio focus management
    private val audioFocusManager = AudioFocusManager(context, this)
    private var volumeMultiplier = 1.0f

    // Network connectivity monitoring & handover
    val networkMonitor = NetworkConnectivityMonitor(context)
    private val networkStatusListener: (NetworkStatus) -> Unit = { status ->
        onNetworkStatusChanged(status)
    }

    // Active track & source tracking
    private var currentTrack: Track? = null
    private var currentSource: PlaybackSource? = null

    val isInitialized: Boolean
        get() = exoPlayer != null && !isReleased.get()

    val currentPosition: Long
        get() = exoPlayer?.currentPosition ?: 0L

    val duration: Long
        get() = exoPlayer?.duration?.takeIf { it > 0 } ?: 0L

    val isPlaying: Boolean
        get() = exoPlayer?.isPlaying == true

    val playWhenReady: Boolean
        get() = exoPlayer?.playWhenReady == true

    fun initialize(): ExoPlayer {
        if (exoPlayer != null && !isReleased.get()) {
            return exoPlayer!!
        }

        val player = exoPlayerProvider?.invoke(context) ?: run {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build()

            ExoPlayer.Builder(context)
                .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ false)
                .setUsePlatformDiagnostics(false)
                .build()
        }

        val sync = PlayerStateSynchronizer(
            stateMachine = stateMachine,
            onErrorOccurred = { exception ->
                handleInternalPlayerError(exception)
            }
        )
        player.addListener(sync)
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> diagnostics.onPlayerReady()
                    Player.STATE_BUFFERING -> diagnostics.onBufferingStarted(player.currentPosition)
                }
            }

            override fun onRenderedFirstFrame() {
                diagnostics.onFirstFrameRendered()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    startProgressPolling()
                } else {
                    stopProgressPolling()
                }
            }
        })

        this.exoPlayer = player
        this.synchronizer = sync
        this.isReleased.set(false)

        AuroraMediaSessionService.activePlayer = player

        // Start network monitoring
        networkMonitor.startMonitoring()
        networkMonitor.addListener(networkStatusListener)

        return player
    }

    suspend fun prepare(
        source: PlaybackSource,
        track: Track? = null,
        playWhenReady: Boolean = true,
        initialSeekPositionMs: Long = 0L
    ) {
        check(!isReleased.get()) { "PlayerController has been released" }
        val player = exoPlayer ?: initialize()

        this.currentTrack = track
        this.currentSource = source
        diagnostics.onPrepareStarted(source)

        // Request audio focus if playing when ready
        if (playWhenReady) {
            audioFocusManager.requestAudioFocus()
        }

        // Transition engine state to PREPARING
        if (stateMachine.canTransitionTo(EngineState.PREPARING)) {
            stateMachine.transitionTo(
                targetState = EngineState.PREPARING,
                track = track,
                source = source
            )
        }

        // Release prior session if active
        activeSession?.release()

        // Create Media3 progressive session
        val session = progressiveTransport.createSession(source) as ProgressivePlaybackSession
        session.prepare()
        this.activeSession = session

        synchronizer?.setCurrentTrackAndSource(track, source)

        // Set media source to ExoPlayer
        player.setMediaSource(session.mediaSource)
        player.playWhenReady = playWhenReady
        if (initialSeekPositionMs > 0L) {
            player.seekTo(initialSeekPositionMs)
        }
        player.prepare()
    }

    fun play() {
        check(!isReleased.get()) { "PlayerController has been released" }
        val player = exoPlayer ?: return
        audioFocusManager.requestAudioFocus()
        player.playWhenReady = true
        synchronizer?.syncPlayingOrPausedState(playWhenReady = true)
    }

    fun pause() {
        check(!isReleased.get()) { "PlayerController has been released" }
        val player = exoPlayer ?: return
        player.playWhenReady = false
        synchronizer?.syncPlayingOrPausedState(playWhenReady = false)
    }

    fun resume() {
        play()
    }

    fun seekTo(positionMs: Long) {
        check(!isReleased.get()) { "PlayerController has been released" }
        val player = exoPlayer ?: return
        val targetPos = positionMs.coerceAtLeast(0L)
        val fromPos = player.currentPosition

        diagnostics.onSeekStarted(fromPos, targetPos)
        pendingSeekPositionMs = targetPos
        seekJob?.cancel()

        seekJob = scope.launch {
            // Debounce rapid seeks by 50ms
            delay(50)
            val pos = pendingSeekPositionMs ?: targetPos
            player.seekTo(pos)
            stateMachine.updatePosition(pos, player.bufferedPosition, player.duration)
            diagnostics.onSeekCompleted()
            pendingSeekPositionMs = null
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        check(!isReleased.get()) { "PlayerController has been released" }
        val clamped = speed.coerceIn(0.25f, 4.0f)
        exoPlayer?.playbackParameters = PlaybackParameters(clamped)
    }

    fun setVolume(volume: Float) {
        check(!isReleased.get()) { "PlayerController has been released" }
        val clamped = volume.coerceIn(0.0f, 1.0f)
        exoPlayer?.volume = clamped * volumeMultiplier
    }

    fun setRepeatMode(repeatMode: Int) {
        exoPlayer?.repeatMode = repeatMode
    }

    fun setShuffleModeEnabled(enabled: Boolean) {
        exoPlayer?.shuffleModeEnabled = enabled
    }

    fun stop() {
        exoPlayer?.stop()
        audioFocusManager.abandonAudioFocus()
        if (stateMachine.canTransitionTo(EngineState.IDLE)) {
            stateMachine.transitionTo(EngineState.IDLE)
        }
    }

    fun release() {
        if (isReleased.compareAndSet(false, true)) {
            networkMonitor.removeListener(networkStatusListener)
            networkMonitor.stopMonitoring()

            progressPollingJob?.cancel()
            seekJob?.cancel()

            audioFocusManager.abandonAudioFocus()

            activeSession?.close()
            activeSession = null

            exoPlayer?.stop()
            exoPlayer?.clearMediaItems()
            synchronizer?.let { exoPlayer?.removeListener(it) }
            exoPlayer?.release()
            exoPlayer = null
            synchronizer = null

            AuroraMediaSessionService.activePlayer = null

            if (stateMachine.canTransitionTo(EngineState.IDLE)) {
                stateMachine.transitionTo(EngineState.IDLE)
            }
        }
    }

    // AudioFocusListener callbacks
    override fun onPauseRequested(transient: Boolean) {
        pause()
    }

    override fun onResumeRequested() {
        volumeMultiplier = 1.0f
        exoPlayer?.volume = 1.0f
        play()
    }

    override fun onVolumeMultiplierChanged(multiplier: Float) {
        volumeMultiplier = multiplier
        exoPlayer?.volume = multiplier
    }

    private fun handleInternalPlayerError(playbackException: PlaybackException) {
        val source = currentSource ?: return
        val pos = exoPlayer?.currentPosition ?: 0L

        val error = PlaybackErrorMapper.map(playbackException, source)
        diagnostics.onError(error)

        scope.launch {
            val decision = recoveryCoordinator.handlePlaybackError(
                error = error,
                currentSource = source,
                playbackPositionMs = pos
            )

            diagnostics.onRecoveryAttempt(
                attemptNumber = recoveryCoordinator.currentRetryCount,
                error = error,
                action = decision.action.name
            )

            when (decision.action) {
                RecoveryAction.RETRY_SAME_SOURCE,
                RecoveryAction.COOLDOWN_RETRY -> {
                    recoveryCoordinator.applyDecisionDelay(decision)
                    prepare(
                        source = source,
                        track = currentTrack,
                        playWhenReady = true,
                        initialSeekPositionMs = decision.resumePositionMs
                    )
                }
                RecoveryAction.PREPARE_NEW_SOURCE,
                RecoveryAction.RE_RESOLVE_SOURCE,
                RecoveryAction.FALLBACK_RESOLUTION,
                RecoveryAction.FORMAT_FALLBACK -> {
                    val nextSource = decision.newSource ?: return@launch
                    recoveryCoordinator.applyDecisionDelay(decision)
                    prepare(
                        source = nextSource,
                        track = currentTrack,
                        playWhenReady = true,
                        initialSeekPositionMs = decision.resumePositionMs
                    )
                }
                RecoveryAction.TERMINAL_AUTH_REQUIRED,
                RecoveryAction.FAIL -> {
                    // Retain terminal error in state machine
                }
            }
        }
    }

    private fun onNetworkStatusChanged(status: NetworkStatus) {
        if (status.isConnected && !isReleased.get()) {
            val state = stateMachine.currentState
            val lastError = stateMachine.currentSnapshot.error
            if (state == EngineState.STALLED || (state == EngineState.ERROR && lastError?.category == ErrorCategory.NETWORK_FAILURE)) {
                val source = currentSource ?: return
                scope.launch {
                    val pos = recoveryCoordinator.lastPreservedPositionMs
                    prepare(
                        source = source,
                        track = currentTrack,
                        playWhenReady = true,
                        initialSeekPositionMs = pos
                    )
                }
            }
        }
    }

    private fun startProgressPolling() {
        if (progressPollingJob?.isActive == true) return
        progressPollingJob = scope.launch {
            while (isActive && !isReleased.get()) {
                exoPlayer?.let { player ->
                    synchronizer?.updateProgress(player)
                }
                delay(250)
            }
        }
    }

    private fun stopProgressPolling() {
        progressPollingJob?.cancel()
        progressPollingJob = null
    }
}
