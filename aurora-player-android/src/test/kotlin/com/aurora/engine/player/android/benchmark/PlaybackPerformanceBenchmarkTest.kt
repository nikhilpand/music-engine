package com.aurora.engine.player.android.benchmark

import android.content.Context
import androidx.media3.common.PlaybackException
import androidx.test.core.app.ApplicationProvider
import com.aurora.engine.core.model.ArtistRef
import com.aurora.engine.core.model.AudioCodec
import com.aurora.engine.core.model.AudioContainer
import com.aurora.engine.core.model.AudioFormat
import com.aurora.engine.core.model.EngineState
import com.aurora.engine.core.model.PlaybackSource
import com.aurora.engine.core.model.Track
import com.aurora.engine.core.orchestrator.EngineStateMachine
import com.aurora.engine.player.android.controller.AuroraAndroidPlayerController
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
class PlaybackPerformanceBenchmarkTest {

    private lateinit var context: Context
    private lateinit var stateMachine: EngineStateMachine
    private lateinit var controller: AuroraAndroidPlayerController

    private val benchmarkSource = PlaybackSource.Progressive(
        trackId = "benchmark-track-1",
        url = "http://localhost:8080/audio/benchmark.mp4",
        audioFormat = AudioFormat(AudioCodec.AAC, AudioContainer.MP4_M4A, 128)
    )

    private val benchmarkTrack = Track(
        id = "benchmark-track-1",
        providerId = "benchmark-provider",
        title = "Benchmark Audio Stream",
        artists = listOf(ArtistRef(id = "bench-artist", name = "Benchmarker")),
        durationMs = 240_000L
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
    fun `measure prepare and startup latency`() = runTest {
        val startNano = System.nanoTime()
        controller.prepare(
            source = benchmarkSource,
            track = benchmarkTrack,
            playWhenReady = true
        )
        val prepTimeMs = (System.nanoTime() - startNano) / 1_000_000L

        // Trigger ready & frame rendered
        controller.diagnostics.onPlayerReady()
        controller.diagnostics.onFirstFrameRendered()

        val snapshot = controller.diagnostics.getSnapshot()
        val recordedPrepMs = snapshot.prepareLatencyMs ?: prepTimeMs
        val recordedStartupMs = snapshot.startupLatencyMs ?: prepTimeMs

        println("=== BENCHMARK: PREPARE & STARTUP LATENCY ===")
        println("Measured Prepare Latency: ${prepTimeMs}ms (recorded: ${recordedPrepMs}ms)")
        println("Measured Startup Latency: ${recordedStartupMs}ms")

        assertThat(prepTimeMs).isLessThan(1000L)
    }

    @Test
    fun `measure seek latency`() = runTest {
        controller.prepare(
            source = benchmarkSource,
            track = benchmarkTrack,
            playWhenReady = true
        )

        val seekStartNano = System.nanoTime()
        controller.seekTo(30_000L)
        val seekTimeMs = (System.nanoTime() - seekStartNano) / 1_000_000L

        println("=== BENCHMARK: SEEK LATENCY ===")
        println("Measured Seek Latency: ${seekTimeMs}ms")

        assertThat(seekTimeMs).isLessThan(500L)
    }

    @Test
    fun `measure recovery latency`() = runTest {
        controller.prepare(
            source = benchmarkSource,
            track = benchmarkTrack,
            playWhenReady = true
        )
        controller.seekTo(15_000L)

        val recoveryStartNano = System.nanoTime()
        val error = PlaybackException(
            "Connection reset",
            IOException("Socket reset"),
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
        )

        val decision = controller.recoveryCoordinator.handlePlaybackError(
            error = com.aurora.engine.player.android.error.PlaybackErrorMapper.map(error, benchmarkSource),
            currentSource = benchmarkSource,
            playbackPositionMs = 15_000L
        )

        controller.prepare(
            source = benchmarkSource,
            track = benchmarkTrack,
            playWhenReady = true,
            initialSeekPositionMs = decision.resumePositionMs
        )
        val recoveryTimeMs = (System.nanoTime() - recoveryStartNano) / 1_000_000L

        println("=== BENCHMARK: RECOVERY LATENCY ===")
        println("Measured Recovery Latency: ${recoveryTimeMs}ms")

        assertThat(recoveryTimeMs).isLessThan(1000L)
        assertThat(decision.resumePositionMs).isEqualTo(15_000L)
    }

    @Test
    fun `measure memory usage during playback lifecycle`() = runTest {
        val runtime = Runtime.getRuntime()
        System.gc()
        val memBeforeBytes = runtime.totalMemory() - runtime.freeMemory()

        controller.initialize()
        controller.prepare(
            source = benchmarkSource,
            track = benchmarkTrack,
            playWhenReady = true
        )
        controller.seekTo(45_000L)

        val memDuringBytes = runtime.totalMemory() - runtime.freeMemory()
        val memDeltaMb = (memDuringBytes - memBeforeBytes) / (1024 * 1024)

        println("=== BENCHMARK: MEMORY USAGE ===")
        println("Memory Before: ${memBeforeBytes / (1024 * 1024)}MB")
        println("Memory During: ${memDuringBytes / (1024 * 1024)}MB")
        println("Memory Delta: ${memDeltaMb}MB")

        // Player overhead must be reasonable (< 50MB)
        assertThat(memDeltaMb).isLessThan(50L)
    }
}
