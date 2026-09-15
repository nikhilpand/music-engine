package com.aurora.engine.transport.sabr.config

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SabrTransportConfigTest {

    @Test
    fun `default config has production-reasonable values`() {
        val config = SabrTransportConfig()

        assertThat(config.bufferLowThresholdMs).isEqualTo(5_000L)
        assertThat(config.maxSilenceTimeoutMs).isEqualTo(30_000L)
        assertThat(config.sessionTimeoutMs).isEqualTo(30_000L)
        assertThat(config.maxContinuationRetries).isEqualTo(3)
        assertThat(config.memoryBufferCapacityBytes).isEqualTo(8L * 1024 * 1024)
        assertThat(config.bufferReadTimeoutMs).isEqualTo(5_000L)
        assertThat(config.httpTimeoutMs).isEqualTo(15_000L)
        assertThat(config.seekBufferTargetMs).isEqualTo(3_000L)
        assertThat(config.maxUmpPartSize).isEqualTo(512 * 1024)
        assertThat(config.maxReassemblyBufferSize).isEqualTo(256 * 1024)
    }

    @Test
    fun `config is a data class with copy support`() {
        val base = SabrTransportConfig()
        val custom = base.copy(
            bufferLowThresholdMs = 10_000L,
            maxContinuationRetries = 5
        )

        assertThat(custom.bufferLowThresholdMs).isEqualTo(10_000L)
        assertThat(custom.maxContinuationRetries).isEqualTo(5)
        // Unchanged fields remain the same
        assertThat(custom.httpTimeoutMs).isEqualTo(base.httpTimeoutMs)
    }

    @Test
    fun `config equality and hashCode`() {
        val a = SabrTransportConfig()
        val b = SabrTransportConfig()
        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
    }
}
