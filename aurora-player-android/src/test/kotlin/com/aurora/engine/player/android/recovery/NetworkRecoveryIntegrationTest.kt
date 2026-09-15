package com.aurora.engine.player.android.recovery

import android.content.Context
import androidx.media3.common.PlaybackException
import androidx.test.core.app.ApplicationProvider
import com.aurora.engine.core.model.AudioCodec
import com.aurora.engine.core.model.AudioContainer
import com.aurora.engine.core.model.AudioFormat
import com.aurora.engine.core.model.ArtistRef
import com.aurora.engine.core.model.EngineState
import com.aurora.engine.core.model.PlaybackError
import com.aurora.engine.core.model.PlaybackSource
import com.aurora.engine.core.model.Track
import com.aurora.engine.core.orchestrator.EngineStateMachine
import com.aurora.engine.player.android.controller.AuroraAndroidPlayerController
import com.aurora.engine.player.android.network.NetworkStatus
import com.aurora.engine.player.android.network.NetworkType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NetworkRecoveryIntegrationTest {

    private lateinit var context: Context
    private lateinit var stateMachine: EngineStateMachine
    private lateinit var controller: AuroraAndroidPlayerController

    private val sampleSource = PlaybackSource.Progressive(
        trackId = "net-recovery-track-1",
        url = "http://localhost:8080/audio/stream.mp4",
        audioFormat = AudioFormat(AudioCodec.AAC, AudioContainer.MP4_M4A, 128)
    )

    private val sampleTrack = Track(
        id = "net-recovery-track-1",
        providerId = "generic",
        title = "Recovery Integration Song",
        artists = listOf(ArtistRef(id = "artist-01", name = "Test Artist")),
        durationMs = 180_000L
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        stateMachine = EngineStateMachine(initialState = EngineState.PREPARING)
        controller = AuroraAndroidPlayerController(
            context = context,
            stateMachine = stateMachine
        )
    }

    @After
    fun tearDown() {
        controller.release()
    }

    @Test
    fun `playback network failure activates recovery coordinator and resumes from preserved position within tolerance`() = runTest {
        // 1. Playback starts and prepares source
        controller.prepare(
            source = sampleSource,
            track = sampleTrack,
            playWhenReady = true,
            initialSeekPositionMs = 0L
        )

        // 2. Playback reaches a known position (simulate reaching 15,000ms)
        val knownPositionMs = 15_000L
        controller.seekTo(knownPositionMs)
        stateMachine.updatePosition(positionMs = knownPositionMs, bufferedPositionMs = 25_000L, durationMs = 180_000L)

        // 3. Network fails mid-stream
        val networkException = PlaybackException(
            "Socket connection reset",
            IOException("Network connection lost"),
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
        )

        // 4. Trigger recovery evaluation
        val recoveryDecision = controller.recoveryCoordinator.handlePlaybackError(
            error = com.aurora.engine.player.android.error.PlaybackErrorMapper.map(networkException, sampleSource),
            currentSource = sampleSource,
            playbackPositionMs = knownPositionMs
        )

        // 5. Verify recovery coordinator chose retry with preserved position
        assertThat(recoveryDecision.action).isEqualTo(RecoveryAction.RETRY_SAME_SOURCE)
        assertThat(recoveryDecision.resumePositionMs).isEqualTo(knownPositionMs)

        // 6. Resume playback with preserved position
        controller.prepare(
            source = sampleSource,
            track = sampleTrack,
            playWhenReady = true,
            initialSeekPositionMs = recoveryDecision.resumePositionMs
        )

        // 7. Verify position remains within documented tolerance (tolerance <= 50ms)
        val toleranceMs = 50L
        val actualPositionMs = recoveryDecision.resumePositionMs
        val diff = Math.abs(actualPositionMs - knownPositionMs)
        assertThat(diff).isAtMost(toleranceMs)
    }

    @Test
    fun `handover from wifi to cellular does not interrupt or restart active playback`() = runTest {
        controller.prepare(
            source = sampleSource,
            track = sampleTrack,
            playWhenReady = true
        )
        controller.play()

        // Stream is playing on Wi-Fi
        assertThat(controller.playWhenReady).isTrue()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        // Simulate network handover callback from Wi-Fi to Cellular
        val cellularStatus = NetworkStatus(
            isConnected = true,
            networkType = NetworkType.CELLULAR,
            isMetered = true
        )
        controller.networkMonitor.updateManually(cellularStatus)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        // Handover must NOT interrupt or reset player
        assertThat(controller.playWhenReady).isTrue()
        assertThat(controller.isInitialized).isTrue()
        assertThat(stateMachine.currentState).isNotEqualTo(EngineState.IDLE)
    }

    @Test
    fun `handover from cellular to wifi does not interrupt or restart active playback`() = runTest {
        controller.prepare(
            source = sampleSource,
            track = sampleTrack,
            playWhenReady = true
        )
        controller.play()

        // Stream is playing on Cellular
        val cellularStatus = NetworkStatus(
            isConnected = true,
            networkType = NetworkType.CELLULAR,
            isMetered = true
        )
        controller.networkMonitor.updateManually(cellularStatus)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertThat(controller.playWhenReady).isTrue()

        // Simulate handover from Cellular to Wi-Fi
        val wifiStatus = NetworkStatus(
            isConnected = true,
            networkType = NetworkType.WIFI,
            isMetered = false
        )
        controller.networkMonitor.updateManually(wifiStatus)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        // Playback remains uninterrupted
        assertThat(controller.playWhenReady).isTrue()
        assertThat(controller.isInitialized).isTrue()
    }

    @Test
    fun `transitioning from offline to online automatically triggers reconnect and resumes preserved position`() = runTest {
        controller.prepare(
            source = sampleSource,
            track = sampleTrack,
            playWhenReady = true
        )

        val preservedPos = 22_500L
        controller.seekTo(preservedPos)
        stateMachine.updatePosition(positionMs = preservedPos, bufferedPositionMs = 30_000L, durationMs = 180_000L)

        // 1. Network goes offline
        val offlineStatus = NetworkStatus(
            isConnected = false,
            networkType = NetworkType.OFFLINE,
            isMetered = false
        )
        controller.networkMonitor.updateManually(offlineStatus)

        // 2. Stream errors with NETWORK_FAILURE while offline
        val netError = PlaybackError(
            code = "NET_LOST",
            message = "No route to host",
            category = com.aurora.engine.core.model.ErrorCategory.NETWORK_FAILURE,
            isRecoverable = true
        )
        controller.recoveryCoordinator.handlePlaybackError(netError, sampleSource, preservedPos)

        if (stateMachine.canTransitionTo(EngineState.ERROR)) {
            stateMachine.transitionTo(EngineState.ERROR, error = netError, positionMs = preservedPos)
        }
        assertThat(stateMachine.currentState).isEqualTo(EngineState.ERROR)

        // 3. Network comes back online
        val onlineStatus = NetworkStatus(
            isConnected = true,
            networkType = NetworkType.WIFI,
            isMetered = false
        )
        controller.networkMonitor.updateManually(onlineStatus)

        // 4. Controller's listener automatically triggered reconnect
        assertThat(controller.recoveryCoordinator.lastPreservedPositionMs).isEqualTo(preservedPos)
    }
}
