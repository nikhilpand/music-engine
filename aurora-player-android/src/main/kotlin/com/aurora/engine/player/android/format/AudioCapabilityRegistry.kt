package com.aurora.engine.player.android.format

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import com.aurora.engine.core.model.AudioCodec
import com.aurora.engine.core.model.AudioContainer
import com.aurora.engine.core.model.AudioFormat
import com.aurora.engine.core.model.PlaybackSource

/**
 * Inspects device media decoders to verify hardware/software decoding capabilities for
 * audio formats and containers (e.g., Opus in WebM, AAC in M4A/MP4).
 */
class AudioCapabilityRegistry(
    private val codecListProvider: () -> Array<MediaCodecInfo> = {
        try {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
        } catch (_: Throwable) {
            emptyArray()
        }
    }
) {

    /**
     * Determines whether the current device supports decoding the specified [format].
     */
    fun isFormatSupported(format: AudioFormat): Boolean {
        val mimeType = resolveMimeType(format) ?: return false
        val codecs = codecListProvider()

        // If codec list is unavailable (e.g., in some test environments), default to standard Android audio capabilities
        if (codecs.isEmpty()) {
            return isDefaultSupported(format.codec)
        }

        return codecs.any { codecInfo ->
            !codecInfo.isEncoder && codecInfo.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
        }
    }

    /**
     * Filters and sorts playback sources by device compatibility.
     * Supported formats are preserved; unsupported formats are deprioritized or filtered out.
     */
    fun filterSupportedSources(sources: List<PlaybackSource>): List<PlaybackSource> {
        val (supported, unsupported) = sources.partition { isFormatSupported(it.audioFormat) }
        return supported + unsupported
    }

    /**
     * Recommends the next best fallback source from candidates if the current source fails due to a decoder error.
     */
    fun selectDecoderFallback(failedSource: PlaybackSource, candidates: List<PlaybackSource>): PlaybackSource? {
        val failedCodec = failedSource.audioFormat.codec
        return candidates.firstOrNull { candidate ->
            candidate != failedSource &&
                candidate.audioFormat.codec != failedCodec &&
                isFormatSupported(candidate.audioFormat)
        }
    }

    private fun resolveMimeType(format: AudioFormat): String? {
        format.mimeType?.let { return it }
        return when (format.codec) {
            AudioCodec.OPUS -> "audio/opus"
            AudioCodec.AAC -> "audio/mp4a-latm"
            AudioCodec.FLAC -> "audio/flac"
            AudioCodec.VORBIS -> "audio/vorbis"
            AudioCodec.MP3 -> "audio/mpeg"
            AudioCodec.UNKNOWN -> when (format.container) {
                AudioContainer.WEBM -> "audio/webm"
                AudioContainer.MP4_M4A -> "audio/mp4"
                AudioContainer.OGG -> "audio/ogg"
                AudioContainer.MP3 -> "audio/mpeg"
                else -> null
            }
        }
    }

    private fun isDefaultSupported(codec: AudioCodec): Boolean {
        return when (codec) {
            AudioCodec.OPUS, AudioCodec.AAC, AudioCodec.MP3, AudioCodec.FLAC, AudioCodec.VORBIS -> true
            AudioCodec.UNKNOWN -> true
        }
    }
}
