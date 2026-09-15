package com.aurora.engine.player.android.controller

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.aurora.engine.core.model.ArtistRef
import com.aurora.engine.core.model.AudioCodec
import com.aurora.engine.core.model.AudioContainer
import com.aurora.engine.core.model.AudioFormat
import com.aurora.engine.core.model.EngineState
import com.aurora.engine.core.model.PlaybackSource
import com.aurora.engine.core.model.Track
import com.aurora.engine.core.orchestrator.EngineStateMachine
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

class PlayerStateSynchronizerTest {

    private lateinit var stateMachine: EngineStateMachine
    private lateinit var synchronizer: PlayerStateSynchronizer

    private val sampleTrack = Track(
        id = "track-sync-1",
        providerId = "ytmusic",
        title = "Sync Test Track",
        artists = listOf(ArtistRef(id = "artist-1", name = "Sync Artist")),
        durationMs = 120_000L
    )
    private val sampleSource = PlaybackSource.Progressive(
        trackId = "track-sync-1",
        url = "https://example.com/audio.webm",
        audioFormat = AudioFormat(AudioCodec.OPUS, AudioContainer.WEBM, 160, 48_000, 2)
    )

    @Before
    fun setUp() {
        stateMachine = EngineStateMachine(initialState = EngineState.PREPARING)
        synchronizer = PlayerStateSynchronizer(stateMachine)
        synchronizer.setCurrentTrackAndSource(sampleTrack, sampleSource)
    }

    @Test
    fun `transition to BUFFERING when in idle or preparing`() {
        stateMachine.transitionTo(EngineState.PREPARING, sampleTrack, sampleSource)
        synchronizer.onPlaybackStateChanged(Player.STATE_BUFFERING)

        assertThat(stateMachine.currentState).isEqualTo(EngineState.BUFFERING)
    }

    @Test
    fun `transition to STALLED when buffering occurs during PLAYING`() {
        stateMachine.transitionTo(EngineState.PREPARING, sampleTrack, sampleSource)
        stateMachine.transitionTo(EngineState.PLAYING, sampleTrack, sampleSource, isPlaying = true)

        synchronizer.onPlaybackStateChanged(Player.STATE_BUFFERING)

        assertThat(stateMachine.currentState).isEqualTo(EngineState.STALLED)
    }

    @Test
    fun `onIsPlayingChanged true transitions to PLAYING`() {
        stateMachine.transitionTo(EngineState.PREPARING, sampleTrack, sampleSource)
        synchronizer.onIsPlayingChanged(true)

        assertThat(stateMachine.currentState).isEqualTo(EngineState.PLAYING)
        assertThat(stateMachine.currentSnapshot.isPlaying).isTrue()
    }

    @Test
    fun `onIsPlayingChanged false transitions to PAUSED`() {
        stateMachine.transitionTo(EngineState.PREPARING, sampleTrack, sampleSource)
        stateMachine.transitionTo(EngineState.PLAYING, sampleTrack, sampleSource, isPlaying = true)

        synchronizer.onIsPlayingChanged(false)

        assertThat(stateMachine.currentState).isEqualTo(EngineState.PAUSED)
        assertThat(stateMachine.currentSnapshot.isPlaying).isFalse()
    }

    @Test
    fun `STATE_ENDED transitions to ENDED`() {
        stateMachine.transitionTo(EngineState.PREPARING, sampleTrack, sampleSource)
        stateMachine.transitionTo(EngineState.PLAYING, sampleTrack, sampleSource, isPlaying = true)

        synchronizer.onPlaybackStateChanged(Player.STATE_ENDED)

        assertThat(stateMachine.currentState).isEqualTo(EngineState.ENDED)
    }

    @Test
    fun `onPlayerError transitions to ERROR with mapped PlaybackError`() {
        var errorCallbackTriggered = false
        val customSynchronizer = PlayerStateSynchronizer(
            stateMachine = stateMachine,
            onErrorOccurred = { errorCallbackTriggered = true }
        )
        customSynchronizer.setCurrentTrackAndSource(sampleTrack, sampleSource)

        stateMachine.transitionTo(EngineState.PREPARING, sampleTrack, sampleSource)

        val exception = PlaybackException(
            "Test Error",
            null,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
        )

        customSynchronizer.onPlayerError(exception)

        assertThat(stateMachine.currentState).isEqualTo(EngineState.ERROR)
        assertThat(stateMachine.currentSnapshot.error).isNotNull()
        assertThat(errorCallbackTriggered).isTrue()
    }
}
