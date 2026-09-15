package com.aurora.engine.provider.ytmusic.strategy

import com.aurora.engine.core.model.AudioCodec
import com.aurora.engine.core.model.QualityProfile
import com.aurora.engine.core.strategy.PlaybackStrategy
import com.aurora.engine.core.strategy.StrategyCapabilities
import com.aurora.engine.provider.ytmusic.session.InnerTubeClientConfig

data class YouTubePlaybackStrategy(
    override val id: String,
    override val priority: Int,
    override val capabilities: StrategyCapabilities,
    val clientConfig: InnerTubeClientConfig,
    override val isEnabled: Boolean = true,
    override val isRetired: Boolean = false,
    override val version: String = "1.0.0"
) : PlaybackStrategy {
    companion object {
        val ANDROID_MUSIC_STRATEGY = YouTubePlaybackStrategy(
            id = "ytmusic:android_music",
            priority = 90,
            capabilities = StrategyCapabilities(
                supportsProgressive = true,
                supportsSabr = true,
                requiresCipherTransform = false,
                requiresPoToken = false,
                supportedCodecs = setOf(AudioCodec.OPUS, AudioCodec.AAC),
                supportedQualities = setOf(QualityProfile.LOW, QualityProfile.MEDIUM, QualityProfile.HIGH)
            ),
            clientConfig = InnerTubeClientConfig.ANDROID_MUSIC
        )

        val VISIONOS_STRATEGY = YouTubePlaybackStrategy(
            id = "ytmusic:visionos",
            priority = 85,
            capabilities = StrategyCapabilities(
                supportsProgressive = true,
                supportsSabr = false,
                requiresCipherTransform = false,
                requiresPoToken = false,
                supportedCodecs = setOf(AudioCodec.AAC, AudioCodec.OPUS),
                supportedQualities = setOf(QualityProfile.LOW, QualityProfile.MEDIUM, QualityProfile.HIGH)
            ),
            clientConfig = InnerTubeClientConfig.VISIONOS
        )

        val WEB_REMIX_STRATEGY = YouTubePlaybackStrategy(
            id = "ytmusic:web_remix",
            priority = 80,
            capabilities = StrategyCapabilities(
                supportsProgressive = true,
                supportsSabr = false,
                requiresCipherTransform = true,
                requiresPoToken = true,
                supportedCodecs = setOf(AudioCodec.OPUS, AudioCodec.AAC),
                supportedQualities = setOf(QualityProfile.LOW, QualityProfile.MEDIUM, QualityProfile.HIGH)
            ),
            clientConfig = InnerTubeClientConfig.WEB_REMIX
        )

        val TVHTML5_STRATEGY = YouTubePlaybackStrategy(
            id = "ytmusic:tvhtml5",
            priority = 70,
            capabilities = StrategyCapabilities(
                supportsProgressive = true,
                supportsSabr = false,
                requiresCipherTransform = false,
                requiresPoToken = false,
                supportedCodecs = setOf(AudioCodec.OPUS, AudioCodec.AAC),
                supportedQualities = setOf(QualityProfile.LOW, QualityProfile.MEDIUM)
            ),
            clientConfig = InnerTubeClientConfig.TVHTML5
        )

        val DEFAULT_STRATEGIES = listOf(
            ANDROID_MUSIC_STRATEGY,
            VISIONOS_STRATEGY,
            WEB_REMIX_STRATEGY,
            TVHTML5_STRATEGY
        )
    }
}
