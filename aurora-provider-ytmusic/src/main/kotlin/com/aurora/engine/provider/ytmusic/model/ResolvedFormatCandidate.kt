package com.aurora.engine.provider.ytmusic.model

import com.aurora.engine.core.model.AudioCodec
import com.aurora.engine.core.model.AudioFormat
import com.aurora.engine.core.model.PlaybackSource

data class CipherInfo(
    val encryptedSignature: String,
    val signatureParam: String,
    val baseUrl: String,
    val isDeciphered: Boolean = false
)

data class TransportHints(
    val isProgressiveCapable: Boolean = true,
    val isSabrCapable: Boolean = false,
    val isHlsCapable: Boolean = false,
    val serverEndpoint: String? = null,
    val ustreamerConfig: String? = null,
    val clientContextJson: String? = null,
    val headers: Map<String, String> = emptyMap()
)

data class ResolvedFormatCandidate(
    val formatId: String,
    val url: String?,
    val mimeType: String?,
    val codec: AudioCodec,
    val bitrateKbps: Int?,
    val sampleRateHz: Int?,
    val channelCount: Int?,
    val durationMs: Long?,
    val expiresAtMs: Long?,
    val cipherInfo: CipherInfo? = null,
    val requiresNTransform: Boolean = false,
    val requiresPoToken: Boolean = false,
    val transportHints: TransportHints = TransportHints(),
    val strategyId: String,
    val rawMetadata: Map<String, String> = emptyMap(),
    val audioFormat: AudioFormat
) {
    val requiresCipherTransform: Boolean
        get() = cipherInfo != null && !cipherInfo.isDeciphered

    fun toPlaybackSources(trackId: String): List<PlaybackSource> {
        val sources = mutableListOf<PlaybackSource>()

        // Progressive source if URL is resolved and usable
        if (transportHints.isProgressiveCapable && !url.isNullOrBlank()) {
            sources.add(
                PlaybackSource.Progressive(
                    trackId = trackId,
                    url = url,
                    audioFormat = audioFormat,
                    headers = transportHints.headers,
                    expiresAtMs = expiresAtMs,
                    customCacheKey = "aurora:track:$trackId"
                )
            )
        }

        // SABR source if endpoint and context are present
        if (transportHints.isSabrCapable && !transportHints.serverEndpoint.isNullOrBlank() && !transportHints.clientContextJson.isNullOrBlank()) {
            sources.add(
                PlaybackSource.Sabr(
                    trackId = trackId,
                    serverEndpoint = transportHints.serverEndpoint,
                    clientContextJson = transportHints.clientContextJson,
                    ustreamerConfig = transportHints.ustreamerConfig,
                    audioFormat = audioFormat,
                    expiresAtMs = expiresAtMs,
                    customCacheKey = "aurora:track:$trackId"
                )
            )
        }

        return sources
    }
}
