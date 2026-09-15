package com.aurora.engine.transport.sabr.model

/**
 * Internal session state machine for SABR transport.
 *
 * These states are internal to the SABR module and do not replace or conflict with
 * Aurora's global [com.aurora.engine.core.model.EngineState].
 *
 * State transitions:
 * ```
 * CREATED → CONNECTING → STREAMING ↔ CONTINUING
 *                           ↕              ↕
 *                        SEEKING      RELOADING
 *                           ↕
 *                       RECOVERING → STREAMING | CLOSED
 *
 * Any state → CLOSED (via release() or terminal failure)
 * ```
 */
enum class SabrSessionState {
    /** Session object created but not yet connected. */
    CREATED,

    /** Initial HTTP POST sent, awaiting first UMP response. */
    CONNECTING,

    /** Actively receiving and processing UMP data. */
    STREAMING,

    /** Sending a demand-driven continuation request to fetch more data. */
    CONTINUING,

    /** Processing a seek: aborting in-flight data, sending new position request. */
    SEEKING,

    /** Server requested session re-initialization (reload directive). */
    RELOADING,

    /** Attempting automatic recovery from a transient failure. */
    RECOVERING,

    /** Session terminated (either normally via release() or after terminal failure). */
    CLOSED
}
