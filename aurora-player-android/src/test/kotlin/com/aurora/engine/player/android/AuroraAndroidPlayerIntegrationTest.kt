package com.aurora.engine.player.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aurora.engine.core.model.EngineState
import com.aurora.engine.core.orchestrator.EngineStateMachine
import com.aurora.engine.player.android.controller.AuroraAndroidPlayerController
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuroraAndroidPlayerIntegrationTest {

    private lateinit var context: Context
    private lateinit var stateMachine: EngineStateMachine
    private lateinit var controller: AuroraAndroidPlayerController

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
    fun `controller initialize creates player instance`() {
        val player = controller.initialize()
        assertThat(player).isNotNull()
        assertThat(controller.isInitialized).isTrue()
    }

    @Test
    fun `volume and speed adjustments update player`() {
        controller.initialize()
        controller.setVolume(0.75f)
        controller.setPlaybackSpeed(1.25f)
        assertThat(controller.isInitialized).isTrue()
    }

    @Test
    fun `play and pause update player playWhenReady`() {
        val player = controller.initialize()
        controller.play()
        assertThat(player.playWhenReady).isTrue()

        controller.pause()
        assertThat(player.playWhenReady).isFalse()
    }

    @Test
    fun `stop halts player and transitions state machine to IDLE`() {
        controller.initialize()
        controller.stop()
        assertThat(stateMachine.currentState).isEqualTo(EngineState.IDLE)
    }

    @Test
    fun `release destroys player instance and marks controller uninitialized`() {
        controller.initialize()
        assertThat(controller.isInitialized).isTrue()

        controller.release()
        assertThat(controller.isInitialized).isFalse()
    }

    @Test
    fun `prepare progressive source configures player media items`() = kotlinx.coroutines.test.runTest {
        val audioFormat = com.aurora.engine.core.model.AudioFormat(
            codec = com.aurora.engine.core.model.AudioCodec.AAC,
            container = com.aurora.engine.core.model.AudioContainer.MP4_M4A,
            bitrateKbps = 128
        )
        val progressiveSource = com.aurora.engine.core.model.PlaybackSource.Progressive(
            trackId = "track-integration-test",
            url = "http://localhost:8080/audio.mp4",
            audioFormat = audioFormat,
            headers = mapOf("User-Agent" to "Aurora/1.0")
        )
        val track = com.aurora.engine.core.model.Track(
            id = "track-integration-test",
            providerId = "ytmusic",
            title = "Integration Song",
            artists = listOf(com.aurora.engine.core.model.ArtistRef(id = "artist-1", name = "Aurora Artist")),
            durationMs = 180_000L
        )

        controller.prepare(
            source = progressiveSource,
            track = track,
            playWhenReady = false
        )

        val player = controller.initialize()
        assertThat(player.mediaItemCount).isEqualTo(1)
        assertThat(player.playWhenReady).isFalse()
    }
}
