package com.aurora.engine.transport.sabr

import com.aurora.engine.core.model.AudioCodec
import com.aurora.engine.core.model.AudioContainer
import com.aurora.engine.core.model.AudioFormat
import com.aurora.engine.core.model.PlaybackSource
import com.aurora.engine.transport.sabr.config.SabrTransportConfig
import com.aurora.engine.transport.sabr.model.SabrSessionState
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SabrPlaybackSessionTest {

    private val testSource = PlaybackSource.Sabr(
        trackId = "test-track-1",
        serverEndpoint = "https://rr1.googlevideo.com/videoplayback",
        clientContextJson = """{"videoId":"dQw4w9WgXcQ"}""",
        audioFormat = AudioFormat(
            codec = AudioCodec.OPUS,
            container = AudioContainer.WEBM,
            bitrateKbps = 128,
            sampleRateHz = 48_000,
            mimeType = "audio/webm"
        )
    )

    private val config = SabrTransportConfig()
    private var session: SabrPlaybackSession? = null

    @AfterEach
    fun tearDown() {
        session?.close()
    }

    @Test
    fun `initial state is CREATED`() {
        session = SabrPlaybackSession(source = testSource, config = config)
        assertThat(session!!.state).isEqualTo(SabrSessionState.CREATED)
    }

    @Test
    fun `sessionId starts with sabr prefix`() {
        session = SabrPlaybackSession(source = testSource, config = config)
        assertThat(session!!.sessionId).startsWith("sabr-")
    }

    @Test
    fun `source is correctly stored`() {
        session = SabrPlaybackSession(source = testSource, config = config)
        assertThat(session!!.source).isEqualTo(testSource)
    }

    @Test
    fun `isPrepared is false when CREATED`() {
        session = SabrPlaybackSession(source = testSource, config = config)
        assertThat(session!!.isPrepared).isFalse()
    }

    @Test
    fun `close transitions to CLOSED`() {
        session = SabrPlaybackSession(source = testSource, config = config)
        session!!.close()
        assertThat(session!!.state).isEqualTo(SabrSessionState.CLOSED)
    }

    @Test
    fun `dataSource is accessible`() {
        session = SabrPlaybackSession(source = testSource, config = config)
        assertThat(session!!.dataSource).isNotNull()
    }

    @Test
    fun `state listener receives state changes`() {
        val stateChanges = mutableListOf<Pair<SabrSessionState, SabrSessionState>>()

        val listener = object : SabrSessionListener {
            override fun onStateChanged(
                sessionId: String,
                oldState: SabrSessionState,
                newState: SabrSessionState
            ) {
                stateChanges.add(oldState to newState)
            }
        }

        session = SabrPlaybackSession(
            source = testSource,
            config = config,
            listener = listener
        )

        session!!.close()

        assertThat(stateChanges).hasSize(1)
        assertThat(stateChanges[0]).isEqualTo(SabrSessionState.CREATED to SabrSessionState.CLOSED)
    }
}
