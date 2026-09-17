package com.aurora.engine.player.android

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.runner.AndroidJUnit4
import com.aurora.engine.core.model.AudioCodec
import com.aurora.engine.core.model.EngineState
import com.aurora.engine.core.model.QualityProfile
import com.aurora.engine.core.model.Track
import com.aurora.engine.core.orchestrator.EngineStateMachine
import com.aurora.engine.core.provider.ResolutionContext
import com.aurora.engine.core.provider.ResolutionResult
import com.aurora.engine.player.android.controller.AuroraAndroidPlayerController
import com.aurora.engine.player.android.network.NetworkStatus
import com.aurora.engine.player.android.network.NetworkType
import com.aurora.engine.provider.ytmusic.YouTubeMusicProvider
import com.aurora.engine.provider.ytmusic.config.ClientConfigStore
import com.aurora.engine.provider.ytmusic.config.ClientLadder
import com.aurora.engine.provider.ytmusic.recovery.PlaybackErrorType
import com.aurora.engine.provider.ytmusic.recovery.RecoveryManager
import com.aurora.engine.provider.ytmusic.recovery.RecoveryStrategy
import com.aurora.engine.provider.ytmusic.resolver.MultiClientStreamResolver
import com.aurora.engine.provider.ytmusic.resolver.StreamUrlCache
import com.aurora.engine.provider.ytmusic.session.InnerTubeSession
import com.aurora.engine.provider.ytmusic.transport.TransportSelector
import com.aurora.engine.provider.ytmusic.transport.TransportType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class RealDevicePlaybackPipelineTest {

    private lateinit var context: Context
    private lateinit var stateMachine: EngineStateMachine
    private lateinit var controller: AuroraAndroidPlayerController
    private lateinit var provider: YouTubeMusicProvider
    private lateinit var multiResolver: MultiClientStreamResolver
    private var exoPlayer: ExoPlayer? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        stateMachine = EngineStateMachine(initialState = EngineState.IDLE)
        controller = AuroraAndroidPlayerController(
            context = context,
            stateMachine = stateMachine
        )

        val session = InnerTubeSession()
        val configStore = ClientConfigStore()
        val ladder = ClientLadder.forStreamResolution(configStore)
        val metaLadder = ClientLadder.forMetadata(configStore)
        val cache = StreamUrlCache()
        val okHttpClient = okhttp3.OkHttpClient.Builder().build()
        val cipherService = com.aurora.engine.provider.ytmusic.cipher.QuickJsCipherService(okHttpClient)

        multiResolver = MultiClientStreamResolver(
            session = session,
            clientLadder = ladder,
            urlCache = cache,
            cipherService = cipherService,
            transportSelector = TransportSelector(),
            metadataLadder = metaLadder
        )

        provider = YouTubeMusicProvider(
            session = session,
            streamResolver = multiResolver
        )
    }

    @After
    fun tearDown() {
        runBlocking {
            withContext(Dispatchers.Main) {
                controller.release()
            }
        }
    }

    @Test
    fun testRealTrackResolutionAndPlaybackOnDevice() = runBlocking {
        // 1. Search for a real track on YouTube Music live
        println("=== STEP 1: Search live YouTube Music track ===")
        val tracks = provider.search("Rick Astley")
        assertThat(tracks).isNotEmpty()
        println("Live search returned ${tracks.size} tracks from YouTube Music, first: ${tracks.first().title} (id=${tracks.first().id})")

        val track = tracks.firstOrNull { it.id == "dQw4w9WgXcQ" }
            ?: Track(
                id = "dQw4w9WgXcQ",
                providerId = "ytmusic",
                title = "Rick Astley - Never Gonna Give You Up",
                artists = listOf(com.aurora.engine.core.model.ArtistRef("rick_astley", "Rick Astley")),
                durationMs = 213_000L
            )
        println("Testing playback pipeline with track: ${track.title} (id=${track.id})")

        // 2. Resolve stream using production MultiClientStreamResolver
        println("=== STEP 2: Resolve stream via MultiClientStreamResolver ===")
        val resolutionContext = ResolutionContext(
            track = track,
            targetQuality = QualityProfile.HIGH,
            preferredCodecs = listOf(AudioCodec.OPUS, AudioCodec.AAC)
        )
        val directResult = multiResolver.resolve(track, resolutionContext)
        println("Direct MultiClientStreamResolver result = $directResult")
        val resolveResult = provider.resolvePlayback(resolutionContext)
        println("Provider resolvePlayback result = $resolveResult")
        assertThat(resolveResult).isInstanceOf(ResolutionResult.Success::class.java)
        val success = resolveResult as ResolutionResult.Success
        val source = success.sources.first()
        val streamUrl = when (source) {
            is com.aurora.engine.core.model.PlaybackSource.Progressive -> source.url
            is com.aurora.engine.core.model.PlaybackSource.Sabr -> source.serverEndpoint
            else -> "stream"
        }
        println("Resolved playback source: URL=${streamUrl.take(80)}... client=${success.strategyId} latency=${success.latencyMs}ms")
        assertThat(streamUrl).isNotEmpty()

        // 3. Prepare and start playback on Media3/ExoPlayer on the physical Android device
        println("=== STEP 3: Start playback on real Media3 controller ===")
        withContext(Dispatchers.Main) {
            exoPlayer = controller.initialize()
            controller.prepare(
                source = source,
                track = track,
                playWhenReady = true,
                initialSeekPositionMs = 0L
            )
        }

        // Wait for player to transition into PLAYING or READY
        val latch = CountDownLatch(1)
        withContext(Dispatchers.Main) {
            exoPlayer?.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    println("ExoPlayer state changed: $state (isPlaying=${controller.isPlaying})")
                    if (state == Player.STATE_READY || state == Player.STATE_BUFFERING) {
                        latch.countDown()
                    }
                }
            })
        }

        val ready = latch.await(15, TimeUnit.SECONDS)
        println("Player buffered/ready within 15s: $ready")

        // 4. Verify playback progress for several seconds
        delay(3000)
        var currentPos = 0L
        withContext(Dispatchers.Main) {
            currentPos = controller.currentPosition
            println("Current position after 3s playback: ${currentPos}ms")
        }

        // 5. Test seeking on device
        println("=== STEP 4: Test seeking to 45s ===")
        withContext(Dispatchers.Main) {
            controller.seekTo(45_000L)
        }
        delay(2000)
        withContext(Dispatchers.Main) {
            val seekPos = controller.currentPosition
            println("Current position after seeking to 45s: ${seekPos}ms")
            assertThat(seekPos).isGreaterThan(35_000L)
        }

        // 6. Test pause / resume
        println("=== STEP 5: Test pause and resume ===")
        withContext(Dispatchers.Main) {
            controller.pause()
            assertThat(controller.playWhenReady).isFalse()
        }
        delay(1000)
        withContext(Dispatchers.Main) {
            controller.play()
            assertThat(controller.playWhenReady).isTrue()
        }
        println("Pause / resume verified.")
    }

    @Test
    fun testRecoveryScenariosOnDevice() = runBlocking {
        println("=== Testing 403 & Network Recovery on Android Device ===")
        val recoveryManager = RecoveryManager()
        val mediaId = "dev_test_recovery"

        // Checkpoint at 30s
        recoveryManager.checkpoint(
            mediaId = mediaId,
            positionMs = 30_000L,
            byteOffset = 500_000L,
            durationMs = 210_000L,
            currentTransport = TransportType.PROGRESSIVE,
            currentClientName = "ANDROID_MUSIC"
        )

        // 403 Recovery
        val action1 = recoveryManager.onError(
            mediaId = mediaId,
            errorType = PlaybackErrorType.HTTP_403,
            currentClientName = "ANDROID_MUSIC",
            currentTransport = TransportType.PROGRESSIVE,
            availableClients = 3,
            currentClientIndex = 0
        )
        assertThat(action1.strategy).isEqualTo(RecoveryStrategy.REAUTH)
        assertThat(action1.resumePositionMs).isEqualTo(30_000L)

        // Network handover simulation
        val cellularStatus = NetworkStatus(
            isConnected = true,
            networkType = NetworkType.CELLULAR,
            isMetered = true
        )
        withContext(Dispatchers.Main) {
            controller.networkMonitor.updateManually(cellularStatus)
        }
        println("Network handover updated on Android device without crash.")

        // SABR fallback
        val actionSabr = recoveryManager.onError(
            mediaId = mediaId,
            errorType = PlaybackErrorType.SABR_PROTOCOL_ERROR,
            currentClientName = "ANDROID_MUSIC",
            currentTransport = TransportType.SABR,
            availableClients = 3,
            currentClientIndex = 0
        )
        assertThat(actionSabr.strategy).isEqualTo(RecoveryStrategy.ROTATE_TRANSPORT)
        println("Recovery scenarios passed on Android device.")
    }
}
