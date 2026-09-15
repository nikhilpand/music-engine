package com.aurora.engine.transport.sabr.model

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SabrSessionStateTest {

    @Test
    fun `all expected states exist`() {
        val states = SabrSessionState.entries
        assertThat(states.map { it.name }).containsExactly(
            "CREATED",
            "CONNECTING",
            "STREAMING",
            "CONTINUING",
            "SEEKING",
            "RELOADING",
            "RECOVERING",
            "CLOSED"
        )
    }

    @Test
    fun `state count is 8`() {
        assertThat(SabrSessionState.entries).hasSize(8)
    }
}
