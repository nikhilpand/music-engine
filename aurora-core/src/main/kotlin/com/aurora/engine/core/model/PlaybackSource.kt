package com.aurora.engine.core.model

import kotlinx.serialization.Serializable

@Serializable
sealed interface PlaybackSource {
    val trackId: String
    val audioFormat: AudioFormat
    val expiresAtMs: Long?
    val customCacheKey: String

    fun isExpired(nowMs: Long): Boolean {
        val expiry = expiresAtMs ?: return false
        return nowMs >= expiry
    }

    @Serializable
    data class Progressive(
        override val trackId: String,
        val url: String,
        override val audioFormat: AudioFormat,
        val headers: Map<String, String> = emptyMap(),
        override val expiresAtMs: Long? = null,
        override val customCacheKey: String = "aurora:track:$trackId"
    ) : PlaybackSource {
        init {
            require(trackId.isNotBlank()) { "trackId cannot be blank" }
            require(url.isNotBlank()) { "url cannot be blank" }
        }
    }

    @Serializable
    data class Sabr(
        override val trackId: String,
        val serverEndpoint: String,
        val clientContextJson: String,
        val ustreamerConfig: String? = null,
        override val audioFormat: AudioFormat,
        override val expiresAtMs: Long? = null,
        override val customCacheKey: String = "aurora:track:$trackId"
    ) : PlaybackSource {
        init {
            require(trackId.isNotBlank()) { "trackId cannot be blank" }
            require(serverEndpoint.isNotBlank()) { "serverEndpoint cannot be blank" }
        }
    }

    @Serializable
    data class Hls(
        override val trackId: String,
        val manifestUrl: String,
        override val audioFormat: AudioFormat,
        val headers: Map<String, String> = emptyMap(),
        override val expiresAtMs: Long? = null,
        override val customCacheKey: String = "aurora:track:$trackId"
    ) : PlaybackSource {
        init {
            require(trackId.isNotBlank()) { "trackId cannot be blank" }
            require(manifestUrl.isNotBlank()) { "manifestUrl cannot be blank" }
        }
    }

    @Serializable
    data class Local(
        override val trackId: String,
        val filePath: String,
        override val audioFormat: AudioFormat,
        override val customCacheKey: String = "aurora:track:$trackId"
    ) : PlaybackSource {
        override val expiresAtMs: Long? = null
        init {
            require(trackId.isNotBlank()) { "trackId cannot be blank" }
            require(filePath.isNotBlank()) { "filePath cannot be blank" }
        }
    }
}
