package com.aurora.engine.transport.sabr.config

/**
 * Tunable configuration for the SABR transport.
 *
 * All values have production-reasonable defaults for audio-only streaming.
 * Fields are immutable; create a new instance to change values.
 */
data class SabrTransportConfig(
    // --- Continuation (demand-driven) ---
    /** Trigger continuation request when unbuffered time ahead of playback < this threshold. */
    val bufferLowThresholdMs: Long = 5_000L,

    /** Safety net: force continuation if no server data received for this long. */
    val maxSilenceTimeoutMs: Long = 30_000L,

    /** Overall session timeout for the initial connection. */
    val sessionTimeoutMs: Long = 30_000L,

    // --- Recovery ---
    /** Maximum continuation retry attempts before escalating to session-level recovery. */
    val maxContinuationRetries: Int = 3,

    // --- Buffer ---
    /** Maximum in-memory buffer capacity in bytes (~8MB ≈ 30s of 256kbps audio). */
    val memoryBufferCapacityBytes: Long = 8L * 1024 * 1024,

    /** Maximum time SabrDataSource.read() will block waiting for data before throwing IOException. */
    val bufferReadTimeoutMs: Long = 5_000L,

    // --- Network ---
    /** HTTP request timeout for SABR POST requests. */
    val httpTimeoutMs: Long = 15_000L,

    // --- Seek ---
    /** Minimum buffer target after seek before playback is considered resumable. */
    val seekBufferTargetMs: Long = 3_000L,

    // --- UMP ---
    /** Maximum allowed size of a single UMP part payload (protection against malformed streams). */
    val maxUmpPartSize: Int = 512 * 1024,

    /** Maximum reassembly buffer for partial UMP parts spanning multiple feeds. */
    val maxReassemblyBufferSize: Int = 256 * 1024
)
