package com.aurora.engine.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class AudioCodec {
    OPUS,
    AAC,
    FLAC,
    VORBIS,
    MP3,
    UNKNOWN
}

@Serializable
enum class AudioContainer {
    WEBM,
    MP4_M4A,
    OGG,
    MP3,
    MATROSKA,
    RAW
}

@Serializable
enum class QualityProfile(val targetBitrateRangeKbps: IntRange) {
    LOW(32..64),
    MEDIUM(96..160),
    HIGH(220..320),
    LOSSLESS(700..1500),
    AUTO(0..Int.MAX_VALUE)
}

@Serializable
data class AudioFormat(
    val codec: AudioCodec,
    val container: AudioContainer,
    val bitrateKbps: Int? = null,
    val sampleRateHz: Int? = null,
    val channelCount: Int? = 2,
    val contentLengthBytes: Long? = null,
    val mimeType: String? = null
) {
    val qualityProfile: QualityProfile
        get() = when {
            codec == AudioCodec.FLAC -> QualityProfile.LOSSLESS
            bitrateKbps == null -> QualityProfile.AUTO
            bitrateKbps in QualityProfile.LOW.targetBitrateRangeKbps -> QualityProfile.LOW
            bitrateKbps in QualityProfile.MEDIUM.targetBitrateRangeKbps -> QualityProfile.MEDIUM
            bitrateKbps >= QualityProfile.HIGH.targetBitrateRangeKbps.first -> QualityProfile.HIGH
            else -> QualityProfile.LOW
        }
}
