package com.aurora.engine.provider.ytmusic.transport

import com.aurora.engine.provider.ytmusic.config.ClientConfigEntry

/**
 * Selects the optimal transport type for a given stream resolution based on
 * client capabilities, network conditions, and transport health.
 *
 * ## Design Rationale
 *
 * Phase 4 used a hardcoded `Progressive > SABR` preference. This is incorrect:
 * - SABR provides better adaptive bitrate and seek behavior for capable clients.
 * - Progressive is simpler but lacks mid-stream quality adaptation.
 * - The optimal choice depends on client capability, network type, and transport health.
 *
 * ## Selection Algorithm
 *
 * 1. Filter to transports the client actually supports.
 * 2. Apply network heuristics (e.g., prefer Progressive on metered/slow connections).
 * 3. Apply health penalties (if SABR has been failing, prefer Progressive).
 * 4. Return the selected transport with a reason for diagnostics.
 */
class TransportSelector {

    @Volatile
    private var sabrHealthy = true

    @Volatile
    private var progressiveHealthy = true

    @Volatile
    private var networkType = NetworkType.UNKNOWN

    /**
     * Select the best transport for the given client and current conditions.
     */
    fun select(
        client: ClientConfigEntry,
        preferSabr: Boolean = false
    ): TransportDecision {
        val sabrCapable = client.supportsSabr
        val sabrAvailable = sabrCapable && sabrHealthy
        val progressiveAvailable = progressiveHealthy

        // If client doesn't support SABR, Progressive is the only option
        if (!sabrCapable) {
            return TransportDecision(
                type = TransportType.PROGRESSIVE,
                reason = "Client ${client.clientName} does not support SABR"
            )
        }

        // If SABR is unhealthy, fall back to Progressive
        if (!sabrAvailable && progressiveAvailable) {
            return TransportDecision(
                type = TransportType.PROGRESSIVE,
                reason = "SABR transport unhealthy, falling back to Progressive"
            )
        }

        // If Progressive is unhealthy but SABR is available, use SABR
        if (sabrAvailable && !progressiveAvailable) {
            return TransportDecision(
                type = TransportType.SABR,
                reason = "Progressive transport unhealthy, using SABR"
            )
        }

        // Both available — use network heuristics and preference
        if (sabrAvailable && progressiveAvailable) {
            // On metered connections, prefer Progressive (simpler, more predictable)
            if (networkType == NetworkType.METERED && !preferSabr) {
                return TransportDecision(
                    type = TransportType.PROGRESSIVE,
                    reason = "Metered network, preferring Progressive for predictability"
                )
            }

            // SABR-capable client with good health on non-metered network
            if (preferSabr || networkType == NetworkType.WIFI) {
                return TransportDecision(
                    type = TransportType.SABR,
                    reason = "SABR preferred: capable client on ${networkType.name} network"
                )
            }

            // Default: Progressive is the safer choice for unknown network conditions
            return TransportDecision(
                type = TransportType.PROGRESSIVE,
                reason = "Default Progressive on ${networkType.name} network"
            )
        }

        // Both unhealthy — still try Progressive as it's simpler
        return TransportDecision(
            type = TransportType.PROGRESSIVE,
            reason = "Both transports unhealthy, attempting Progressive as fallback"
        )
    }

    /**
     * Report transport health after a playback attempt.
     */
    fun recordTransportResult(type: TransportType, success: Boolean) {
        when (type) {
            TransportType.SABR -> sabrHealthy = if (success) true else sabrHealthy // only degrade on consecutive failures
            TransportType.PROGRESSIVE -> progressiveHealthy = if (success) true else progressiveHealthy
        }
        // For real consecutive failure tracking, use an atomic counter:
        if (!success) {
            when (type) {
                TransportType.SABR -> sabrHealthy = false
                TransportType.PROGRESSIVE -> progressiveHealthy = false
            }
        } else {
            when (type) {
                TransportType.SABR -> sabrHealthy = true
                TransportType.PROGRESSIVE -> progressiveHealthy = true
            }
        }
    }

    /**
     * Update the current network type. Call from connectivity change listener.
     */
    fun onNetworkTypeChanged(type: NetworkType) {
        networkType = type
        // Reset transport health on network change — the previous failures
        // may have been network-related, not transport-related.
        sabrHealthy = true
        progressiveHealthy = true
    }

    fun getNetworkType(): NetworkType = networkType

    fun isSabrHealthy(): Boolean = sabrHealthy
    fun isProgressiveHealthy(): Boolean = progressiveHealthy
}

data class TransportDecision(
    val type: TransportType,
    val reason: String
)

enum class TransportType {
    PROGRESSIVE,
    SABR
}

enum class NetworkType {
    WIFI,
    METERED,
    UNKNOWN
}
