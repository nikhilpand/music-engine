package com.aurora.engine.provider.ytmusic.transport

import com.aurora.engine.provider.ytmusic.config.ClientConfigEntry
import org.junit.jupiter.api.Test
import com.google.common.truth.Truth.assertThat

class TransportSelectorTest {

    private val sabrClient = ClientConfigEntry(
        clientName = "ANDROID_MUSIC",
        clientVersion = "6.42.52",
        userAgent = "test",
        supportsSabr = true,
        priority = 90
    )

    private val progressiveClient = ClientConfigEntry(
        clientName = "VISIONOS",
        clientVersion = "0.1",
        userAgent = "test",
        supportsSabr = false,
        priority = 85
    )

    @Test
    fun `non-SABR client always gets Progressive`() {
        val selector = TransportSelector()
        val decision = selector.select(progressiveClient)

        assertThat(decision.type).isEqualTo(TransportType.PROGRESSIVE)
        assertThat(decision.reason).contains("does not support SABR")
    }

    @Test
    fun `SABR client on WiFi prefers SABR`() {
        val selector = TransportSelector()
        selector.onNetworkTypeChanged(NetworkType.WIFI)

        val decision = selector.select(sabrClient)
        assertThat(decision.type).isEqualTo(TransportType.SABR)
    }

    @Test
    fun `SABR client on metered prefers Progressive`() {
        val selector = TransportSelector()
        selector.onNetworkTypeChanged(NetworkType.METERED)

        val decision = selector.select(sabrClient)
        assertThat(decision.type).isEqualTo(TransportType.PROGRESSIVE)
    }

    @Test
    fun `SABR client on metered with explicit preference gets SABR`() {
        val selector = TransportSelector()
        selector.onNetworkTypeChanged(NetworkType.METERED)

        val decision = selector.select(sabrClient, preferSabr = true)
        assertThat(decision.type).isEqualTo(TransportType.SABR)
    }

    @Test
    fun `unhealthy SABR falls back to Progressive`() {
        val selector = TransportSelector()
        selector.onNetworkTypeChanged(NetworkType.WIFI)
        selector.recordTransportResult(TransportType.SABR, success = false)

        val decision = selector.select(sabrClient)
        assertThat(decision.type).isEqualTo(TransportType.PROGRESSIVE)
        assertThat(decision.reason).contains("unhealthy")
    }

    @Test
    fun `network change resets health`() {
        val selector = TransportSelector()
        selector.recordTransportResult(TransportType.SABR, success = false)
        assertThat(selector.isSabrHealthy()).isFalse()

        selector.onNetworkTypeChanged(NetworkType.WIFI)
        assertThat(selector.isSabrHealthy()).isTrue()
    }

    @Test
    fun `successful result restores health`() {
        val selector = TransportSelector()
        selector.recordTransportResult(TransportType.SABR, success = false)
        assertThat(selector.isSabrHealthy()).isFalse()

        selector.recordTransportResult(TransportType.SABR, success = true)
        assertThat(selector.isSabrHealthy()).isTrue()
    }
}
