package com.aurora.engine.core.provider

import com.aurora.engine.core.model.AudioCodec
import com.aurora.engine.core.model.PlaybackError
import com.aurora.engine.core.model.PlaybackSource
import com.aurora.engine.core.model.QualityProfile
import com.aurora.engine.core.model.Track
import kotlinx.serialization.Serializable

@Serializable
enum class NetworkType {
    WIFI,
    CELLULAR_5G,
    CELLULAR_4G,
    CELLULAR_OTHER,
    ETHERNET,
    OFFLINE,
    UNKNOWN
}

data class ResolutionContext(
    val track: Track,
    val targetQuality: QualityProfile = QualityProfile.AUTO,
    val preferredCodecs: List<AudioCodec> = listOf(AudioCodec.OPUS, AudioCodec.AAC),
    val forcedStrategyId: String? = null,
    val isPrefetch: Boolean = false,
    val networkType: NetworkType = NetworkType.UNKNOWN,
    val timeoutMs: Long = 10_000L
) {
    val resolutionKey: String
        get() = "${track.providerId}:${track.id}:${targetQuality.name}"
}

sealed interface ResolutionResult {
    data class Success(
        val sources: List<PlaybackSource>,
        val strategyId: String,
        val expiresAtMs: Long? = null,
        val latencyMs: Long = 0L,
        val resolvedAtMs: Long = System.currentTimeMillis()
    ) : ResolutionResult {
        init {
            require(sources.isNotEmpty()) { "ResolutionResult.Success must contain at least one PlaybackSource" }
            require(strategyId.isNotBlank()) { "strategyId cannot be blank" }
        }

        val primarySource: PlaybackSource
            get() = sources.first()
    }

    data class Failure(
        val error: PlaybackError,
        val strategyId: String? = null,
        val canFallback: Boolean = true,
        val latencyMs: Long = 0L
    ) : ResolutionResult
}
