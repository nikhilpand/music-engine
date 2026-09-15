package com.aurora.engine.core.strategy

import com.aurora.engine.core.model.AudioCodec
import com.aurora.engine.core.model.QualityProfile

data class StrategyCapabilities(
    val supportsProgressive: Boolean = true,
    val supportsSabr: Boolean = false,
    val supportsHls: Boolean = false,
    val requiresCipherTransform: Boolean = false,
    val requiresPoToken: Boolean = false,
    val supportedCodecs: Set<AudioCodec> = setOf(AudioCodec.OPUS, AudioCodec.AAC),
    val supportedQualities: Set<QualityProfile> = setOf(
        QualityProfile.LOW,
        QualityProfile.MEDIUM,
        QualityProfile.HIGH
    ),
    val requiresAuthentication: Boolean = false
) {
    fun canSupport(quality: QualityProfile): Boolean {
        return quality == QualityProfile.AUTO || supportedQualities.contains(quality)
    }

    fun canSupportAnyCodec(codecs: Collection<AudioCodec>): Boolean {
        return codecs.any { supportedCodecs.contains(it) }
    }
}

interface PlaybackStrategy {
    val id: String
    val priority: Int
    val capabilities: StrategyCapabilities
    val isEnabled: Boolean get() = true
    val isRetired: Boolean get() = false
    val version: String get() = "1.0.0"
}
