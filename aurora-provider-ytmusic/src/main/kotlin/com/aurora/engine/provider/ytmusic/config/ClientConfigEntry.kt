package com.aurora.engine.provider.ytmusic.config

import kotlinx.serialization.Serializable

/**
 * A single YouTube InnerTube client configuration entry.
 *
 * Each entry describes a client identity (name, version, user-agent) along with
 * its capability flags and priority for stream resolution and metadata fetching.
 *
 * ## Priority Semantics
 *
 * - [priority]: Rank for stream URL resolution. Higher = preferred.
 * - [metadataPriority]: Rank for metadata/catalog fetching (/next, /browse).
 *   Only relevant when [supportsMetadata] is `true`.
 *
 * ## Capability Flags
 *
 * - [requiresCipher]: Client's stream URLs are signature-ciphered (requires CipherService).
 * - [requiresPoToken]: Client requires Proof-of-Origin token (not yet implemented; skip in Phase 5).
 * - [supportsSabr]: Client supports SABR (Server-Adaptive Bitrate) transport.
 * - [supportsAudioOnly]: Client can request audio-only streams.
 * - [supportsMetadata]: Client can fetch track/album/playlist metadata.
 */
@Serializable
data class ClientConfigEntry(
    val clientName: String,
    val clientVersion: String,
    val userAgent: String,
    val osName: String = "",
    val osVersion: String = "",
    val platform: String = "",
    val clientScreen: String? = null,
    val hl: String = "en",
    val gl: String = "US",
    val requiresCipher: Boolean = false,
    val requiresPoToken: Boolean = false,
    val supportsSabr: Boolean = false,
    val supportsAudioOnly: Boolean = true,
    val supportsMetadata: Boolean = false,
    val metadataPriority: Int = 0,
    val enabled: Boolean = true,
    val priority: Int = 0
) {
    init {
        require(clientName.isNotBlank()) { "clientName cannot be blank" }
        require(clientVersion.isNotBlank()) { "clientVersion cannot be blank" }
    }
}

fun ClientConfigEntry.toInnerTubeClientConfig(): com.aurora.engine.provider.ytmusic.session.InnerTubeClientConfig =
    com.aurora.engine.provider.ytmusic.session.InnerTubeClientConfig(
        clientName = clientName,
        clientVersion = clientVersion,
        clientScreen = clientScreen,
        userAgent = userAgent,
        osName = osName.ifBlank { "Android" },
        osVersion = osVersion.ifBlank { "14" },
        platform = platform.ifBlank { "MOBILE" },
        hl = hl,
        gl = gl,
        requiresCipher = requiresCipher,
        requiresPoToken = requiresPoToken,
        supportsSabr = supportsSabr
    )
