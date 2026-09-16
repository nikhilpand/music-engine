package com.aurora.engine.provider.ytmusic.recovery

import com.aurora.engine.provider.ytmusic.transport.TransportType
import org.junit.jupiter.api.Test
import com.google.common.truth.Truth.assertThat

class RecoveryManagerTest {

    @Test
    fun `first 403 triggers REAUTH`() {
        val mgr = RecoveryManager()
        mgr.checkpoint("track1", positionMs = 30_000, byteOffset = 500_000, durationMs = 180_000,
            currentTransport = TransportType.PROGRESSIVE, currentClientName = "VISIONOS")

        val action = mgr.onError("track1", PlaybackErrorType.HTTP_403,
            currentClientName = "VISIONOS", currentTransport = TransportType.PROGRESSIVE,
            availableClients = 3, currentClientIndex = 0)

        assertThat(action.strategy).isEqualTo(RecoveryStrategy.REAUTH)
        assertThat(action.resumePositionMs).isEqualTo(30_000)
        assertThat(action.resumeByteOffset).isEqualTo(500_000)
    }

    @Test
    fun `second 403 triggers ROTATE_CLIENT`() {
        val mgr = RecoveryManager()
        mgr.checkpoint("track1", 30_000, 500_000, 180_000, TransportType.PROGRESSIVE, "VISIONOS")

        mgr.onError("track1", PlaybackErrorType.HTTP_403, "VISIONOS", TransportType.PROGRESSIVE, 3, 0)
        val action = mgr.onError("track1", PlaybackErrorType.HTTP_403, "VISIONOS", TransportType.PROGRESSIVE, 3, 0)

        assertThat(action.strategy).isEqualTo(RecoveryStrategy.ROTATE_CLIENT)
    }

    @Test
    fun `third 403 triggers ROTATE_TRANSPORT`() {
        val mgr = RecoveryManager()
        mgr.checkpoint("track1", 30_000, 500_000, 180_000, TransportType.PROGRESSIVE, "VISIONOS")

        repeat(2) { mgr.onError("track1", PlaybackErrorType.HTTP_403, "VISIONOS", TransportType.PROGRESSIVE, 3, 0) }
        val action = mgr.onError("track1", PlaybackErrorType.HTTP_403, "VISIONOS", TransportType.PROGRESSIVE, 3, 0)

        assertThat(action.strategy).isEqualTo(RecoveryStrategy.ROTATE_TRANSPORT)
    }

    @Test
    fun `stream expired triggers REAUTH`() {
        val mgr = RecoveryManager()
        mgr.checkpoint("track1", 60_000, 1_000_000, 180_000, TransportType.PROGRESSIVE, "VISIONOS")

        val action = mgr.onError("track1", PlaybackErrorType.STREAM_EXPIRED, "VISIONOS", TransportType.PROGRESSIVE, 3, 0)

        assertThat(action.strategy).isEqualTo(RecoveryStrategy.REAUTH)
        assertThat(action.resumePositionMs).isEqualTo(60_000)
    }

    @Test
    fun `network change includes stabilization delay`() {
        val mgr = RecoveryManager()
        mgr.checkpoint("track1", 45_000, 700_000, 180_000, TransportType.PROGRESSIVE, "VISIONOS")

        val action = mgr.onError("track1", PlaybackErrorType.NETWORK_CHANGE, "VISIONOS", TransportType.PROGRESSIVE, 3, 0)

        assertThat(action.strategy).isEqualTo(RecoveryStrategy.REAUTH)
        assertThat(action.backoffMs).isEqualTo(500)
    }

    @Test
    fun `SABR protocol error triggers transport rotation`() {
        val mgr = RecoveryManager()
        val action = mgr.onError("track1", PlaybackErrorType.SABR_PROTOCOL_ERROR, "ANDROID_MUSIC", TransportType.SABR, 3, 0)

        assertThat(action.strategy).isEqualTo(RecoveryStrategy.ROTATE_TRANSPORT)
    }

    @Test
    fun `cipher failure rotates to next client`() {
        val mgr = RecoveryManager()
        val action = mgr.onError("track1", PlaybackErrorType.CIPHER_FAILURE, "WEB_REMIX", TransportType.PROGRESSIVE, 3, 1)

        assertThat(action.strategy).isEqualTo(RecoveryStrategy.ROTATE_CLIENT)
    }

    @Test
    fun `cipher failure with no alternatives gives up`() {
        val mgr = RecoveryManager()
        val action = mgr.onError("track1", PlaybackErrorType.CIPHER_FAILURE, "WEB_REMIX", TransportType.PROGRESSIVE, 1, 0)

        assertThat(action.strategy).isEqualTo(RecoveryStrategy.GIVE_UP)
    }

    @Test
    fun `recovery success resets consecutive errors`() {
        val mgr = RecoveryManager()
        mgr.onError("track1", PlaybackErrorType.HTTP_403, "VISIONOS", TransportType.PROGRESSIVE, 3, 0)
        mgr.onError("track1", PlaybackErrorType.HTTP_403, "VISIONOS", TransportType.PROGRESSIVE, 3, 0)

        mgr.onRecoverySuccess("track1")

        // Next error should be treated as first (consecutive reset)
        val action = mgr.onError("track1", PlaybackErrorType.HTTP_403, "VISIONOS", TransportType.PROGRESSIVE, 3, 0)
        assertThat(action.strategy).isEqualTo(RecoveryStrategy.REAUTH)
    }

    @Test
    fun `exhausted attempts result in GIVE_UP`() {
        val mgr = RecoveryManager(maxRecoveryAttempts = 3)

        repeat(4) { mgr.onError("track1", PlaybackErrorType.HTTP_403, "VISIONOS", TransportType.PROGRESSIVE, 1, 0) }

        assertThat(
            mgr.onError("track1", PlaybackErrorType.HTTP_403, "VISIONOS", TransportType.PROGRESSIVE, 1, 0).strategy
        ).isEqualTo(RecoveryStrategy.GIVE_UP)
    }

    @Test
    fun `checkpoint is preserved through errors`() {
        val mgr = RecoveryManager()
        mgr.checkpoint("track1", 120_000, 2_000_000, 240_000, TransportType.SABR, "ANDROID_MUSIC")

        val checkpoint = mgr.getCheckpoint("track1")
        assertThat(checkpoint).isNotNull()
        assertThat(checkpoint!!.positionMs).isEqualTo(120_000)
        assertThat(checkpoint.byteOffset).isEqualTo(2_000_000)
    }

    @Test
    fun `clear removes state for track`() {
        val mgr = RecoveryManager()
        mgr.checkpoint("track1", 10_000, 100_000, 180_000, TransportType.PROGRESSIVE, "TEST")
        mgr.clear("track1")
        assertThat(mgr.getCheckpoint("track1")).isNull()
    }
}
