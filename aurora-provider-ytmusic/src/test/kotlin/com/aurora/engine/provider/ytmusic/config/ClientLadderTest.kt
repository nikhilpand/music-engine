package com.aurora.engine.provider.ytmusic.config

import org.junit.jupiter.api.Test
import com.google.common.truth.Truth.assertThat

class ClientLadderTest {

    private fun buildLadder(): ClientLadder {
        val clients = listOf(
            ClientConfigEntry("A", "1.0", "Agent/A", priority = 100, supportsSabr = true),
            ClientConfigEntry("B", "1.0", "Agent/B", priority = 80, supportsMetadata = true),
            ClientConfigEntry("C", "1.0", "Agent/C", priority = 60)
        )
        return ClientLadder(clients)
    }

    @Test
    fun `nextClient returns highest priority`() {
        val ladder = buildLadder()
        assertThat(ladder.nextClient()?.clientName).isEqualTo("A")
    }

    @Test
    fun `nextClient with filter returns filtered result`() {
        val ladder = buildLadder()
        val result = ladder.nextClient { it.supportsMetadata }
        assertThat(result?.clientName).isEqualTo("B")
    }

    @Test
    fun `quarantined client is skipped`() {
        val ladder = buildLadder()
        repeat(ClientLadder.QUARANTINE_THRESHOLD) { ladder.recordFailure("A") }

        assertThat(ladder.isQuarantined("A")).isTrue()
        assertThat(ladder.nextClient()?.clientName).isEqualTo("B")
    }

    @Test
    fun `success resets penalty`() {
        val ladder = buildLadder()
        repeat(ClientLadder.QUARANTINE_THRESHOLD) { ladder.recordFailure("A") }
        assertThat(ladder.isQuarantined("A")).isTrue()

        ladder.recordSuccess("A")
        assertThat(ladder.isQuarantined("A")).isFalse()
        assertThat(ladder.nextClient()?.clientName).isEqualTo("A")
    }

    @Test
    fun `resetAll clears all penalties`() {
        val ladder = buildLadder()
        repeat(ClientLadder.QUARANTINE_THRESHOLD) { ladder.recordFailure("A") }
        repeat(ClientLadder.QUARANTINE_THRESHOLD) { ladder.recordFailure("B") }

        ladder.resetAll()
        assertThat(ladder.isQuarantined("A")).isFalse()
        assertThat(ladder.isQuarantined("B")).isFalse()
    }

    @Test
    fun `all quarantined returns null`() {
        val ladder = buildLadder()
        listOf("A", "B", "C").forEach { name ->
            repeat(ClientLadder.QUARANTINE_THRESHOLD) { ladder.recordFailure(name) }
        }
        assertThat(ladder.nextClient()).isNull()
    }

    @Test
    fun `health snapshot reflects penalties`() {
        val ladder = buildLadder()
        ladder.recordFailure("A")
        ladder.recordFailure("A")

        val snapshot = ladder.getHealthSnapshot()
        assertThat(snapshot["A"]).isEqualTo(2)
        assertThat(snapshot["B"]).isEqualTo(0)
    }

    @Test
    fun `availableClients respects filter and quarantine`() {
        val ladder = buildLadder()
        repeat(ClientLadder.QUARANTINE_THRESHOLD) { ladder.recordFailure("A") }

        val available = ladder.availableClients()
        assertThat(available.map { it.clientName }).containsExactly("B", "C")
    }

    @Test
    fun `size includes quarantined clients`() {
        val ladder = buildLadder()
        repeat(ClientLadder.QUARANTINE_THRESHOLD) { ladder.recordFailure("A") }
        assertThat(ladder.size).isEqualTo(3)
    }
}
