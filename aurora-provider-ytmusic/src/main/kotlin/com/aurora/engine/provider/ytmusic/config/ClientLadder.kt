package com.aurora.engine.provider.ytmusic.config

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * A health-aware, capability-filtered client fallback chain.
 *
 * ## Concept
 *
 * The "ladder" is an ordered list of [ClientConfigEntry] candidates for a specific
 * use-case (stream resolution or metadata). When a client fails (403, timeout, cipher
 * failure), its health penalty increases and [nextClient] returns the next-best option.
 *
 * ## Health Model
 *
 * Each client tracks consecutive failures via an [AtomicInteger] penalty counter.
 * The penalty system is deliberately simple:
 * - [recordFailure] increments penalty by 1.
 * - [recordSuccess] resets penalty to 0.
 * - Clients with penalty >= [QUARANTINE_THRESHOLD] are skipped.
 * - [resetAll] clears all penalties (used after network changes or periodic recovery).
 *
 * ## Thread Safety
 *
 * Penalty counters use [AtomicInteger] for lock-free concurrent access. The ladder
 * itself is immutable after construction. [getHealthSnapshot] returns a stable copy
 * for diagnostics.
 */
class ClientLadder(
    private val candidates: List<ClientConfigEntry>
) {
    private val penalties = ConcurrentHashMap<String, AtomicInteger>()

    init {
        for (entry in candidates) {
            penalties[entry.clientName] = AtomicInteger(0)
        }
    }

    /**
     * Return the highest-priority client that is not quarantined.
     *
     * @param filter Optional additional filter (e.g., `{ it.supportsSabr }`).
     * @return Best available client, or `null` if all are quarantined.
     */
    fun nextClient(filter: ((ClientConfigEntry) -> Boolean)? = null): ClientConfigEntry? {
        return candidates
            .filter { entry ->
                val penalty = penalties[entry.clientName]?.get() ?: 0
                val notQuarantined = penalty < QUARANTINE_THRESHOLD
                val matchesFilter = filter?.invoke(entry) ?: true
                notQuarantined && matchesFilter
            }
            .firstOrNull()
    }

    /**
     * Return all currently available (non-quarantined) clients matching the optional filter.
     */
    fun availableClients(filter: ((ClientConfigEntry) -> Boolean)? = null): List<ClientConfigEntry> {
        return candidates.filter { entry ->
            val penalty = penalties[entry.clientName]?.get() ?: 0
            val notQuarantined = penalty < QUARANTINE_THRESHOLD
            val matchesFilter = filter?.invoke(entry) ?: true
            notQuarantined && matchesFilter
        }
    }

    /**
     * Record a failure for the given client. Increments its penalty counter.
     */
    fun recordFailure(clientName: String) {
        penalties[clientName]?.incrementAndGet()
    }

    /**
     * Record a success for the given client. Resets its penalty to 0.
     */
    fun recordSuccess(clientName: String) {
        penalties[clientName]?.set(0)
    }

    /**
     * Reset all penalties. Used after network changes or periodic recovery probes.
     */
    fun resetAll() {
        penalties.values.forEach { it.set(0) }
    }

    /**
     * Check if a specific client is currently quarantined.
     */
    fun isQuarantined(clientName: String): Boolean {
        return (penalties[clientName]?.get() ?: 0) >= QUARANTINE_THRESHOLD
    }

    /**
     * Return a diagnostic snapshot of all clients and their current penalty counts.
     */
    fun getHealthSnapshot(): Map<String, Int> {
        return penalties.mapValues { it.value.get() }
    }

    /**
     * Total number of candidates (including quarantined).
     */
    val size: Int get() = candidates.size

    companion object {
        /**
         * Consecutive failures before a client is skipped. 3 strikes and you're out.
         */
        const val QUARANTINE_THRESHOLD = 3

        /**
         * Build a ladder from a [ClientConfigStore] for stream resolution.
         * Candidates are sorted by [ClientConfigEntry.priority] descending (already done by store).
         */
        fun forStreamResolution(store: ClientConfigStore): ClientLadder {
            return ClientLadder(store.getActiveClients())
        }

        /**
         * Build a ladder from a [ClientConfigStore] for metadata fetching.
         * Candidates are sorted by [ClientConfigEntry.metadataPriority] descending.
         */
        fun forMetadata(store: ClientConfigStore): ClientLadder {
            return ClientLadder(store.getMetadataClients())
        }
    }
}
