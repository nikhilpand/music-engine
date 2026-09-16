package com.aurora.engine.provider.ytmusic.parser

import com.aurora.engine.core.model.AudioCodec
import com.aurora.engine.core.model.AudioContainer
import com.aurora.engine.core.model.AudioFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLDecoder

data class ParsedStreamFormat(
    val itag: Int,
    val directUrl: String?,
    val cipherSignature: String?,
    val cipherSignatureParam: String?,
    val cipherBaseUrl: String?,
    val audioFormat: AudioFormat,
    val approxDurationMs: Long?,
    val isAudioOnly: Boolean,
    val rawMetadata: Map<String, String> = emptyMap()
) {
    val requiresCipher: Boolean
        get() = cipherSignature != null && cipherBaseUrl != null
}

data class ParsedPlayerResponse(
    val isPlayable: Boolean,
    val status: String,
    val reason: String?,
    val expiresInSeconds: Long?,
    val formats: List<ParsedStreamFormat>,
    val serverEndpoint: String? = null,
    val ustreamerConfig: String? = null,
    val rawPlayability: Map<String, String> = emptyMap(),
    val videoTitle: String? = null,
    val videoAuthor: String? = null,
    val videoDurationMs: Long? = null
)

object PlayerResponseParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parse(rawJson: String): ParsedPlayerResponse {
        val root = try {
            json.parseToJsonElement(rawJson).jsonObject
        } catch (_: Throwable) {
            return ParsedPlayerResponse(
                isPlayable = false,
                status = "PARSE_ERROR",
                reason = "Invalid JSON response payload from player endpoint",
                expiresInSeconds = null,
                formats = emptyList()
            )
        }

        // 1. Parse PlayabilityStatus
        val playabilityObj = root["playabilityStatus"]?.jsonObject
        val status = playabilityObj?.get("status")?.jsonPrimitive?.content ?: "UNKNOWN"
        val reason = playabilityObj?.get("reason")?.jsonPrimitive?.content
        val isPlayable = status.equals("OK", ignoreCase = true)

        val rawPlayability = mutableMapOf<String, String>()
        playabilityObj?.forEach { (k, v) ->
            if (v !is JsonObject && v !is JsonArray) {
                rawPlayability[k] = v.jsonPrimitive.content
            }
        }

        if (!isPlayable) {
            return ParsedPlayerResponse(
                isPlayable = false,
                status = status,
                reason = reason,
                expiresInSeconds = null,
                formats = emptyList(),
                rawPlayability = rawPlayability
            )
        }

        // 2. Parse StreamingData
        val streamingData = root["streamingData"]?.jsonObject ?: return ParsedPlayerResponse(
            isPlayable = false,
            status = "MISSING_STREAMING_DATA",
            reason = "Response playability status is OK but streamingData is missing",
            expiresInSeconds = null,
            formats = emptyList(),
            rawPlayability = rawPlayability
        )

        val expiresInSeconds = streamingData["expiresInSeconds"]?.jsonPrimitive?.content?.toLongOrNull()
        val serverEndpoint = streamingData["serverEndpoint"]?.jsonPrimitive?.content
        val ustreamerConfig = streamingData["ustreamerConfig"]?.jsonPrimitive?.content

        val parsedFormats = mutableListOf<ParsedStreamFormat>()

        // Parse adaptiveFormats (preferred for audio-only streams)
        val adaptiveFormats = streamingData["adaptiveFormats"]?.jsonArray.orEmpty()
        for (formatElem in adaptiveFormats) {
            if (formatElem is JsonObject) {
                val parsed = parseSingleFormat(formatElem)
                if (parsed != null && parsed.isAudioOnly) {
                    parsedFormats.add(parsed)
                }
            }
        }

        // If no adaptive formats, parse legacy combined formats
        if (parsedFormats.isEmpty()) {
            val regularFormats = streamingData["formats"]?.jsonArray.orEmpty()
            for (formatElem in regularFormats) {
                if (formatElem is JsonObject) {
                    val parsed = parseSingleFormat(formatElem)
                    if (parsed != null) {
                        parsedFormats.add(parsed)
                    }
                }
            }
        }

        val videoDetails = root["videoDetails"]?.jsonObject
        val videoTitle = videoDetails?.get("title")?.jsonPrimitive?.content
        val videoAuthor = videoDetails?.get("author")?.jsonPrimitive?.content
        val videoLengthSeconds = videoDetails?.get("lengthSeconds")?.jsonPrimitive?.content?.toLongOrNull()
        val videoDurationMs = videoLengthSeconds?.times(1000L)

        return ParsedPlayerResponse(
            isPlayable = true,
            status = status,
            reason = null,
            expiresInSeconds = expiresInSeconds,
            formats = parsedFormats,
            serverEndpoint = serverEndpoint,
            ustreamerConfig = ustreamerConfig,
            rawPlayability = rawPlayability,
            videoTitle = videoTitle,
            videoAuthor = videoAuthor,
            videoDurationMs = videoDurationMs
        )
    }

    private fun parseSingleFormat(obj: JsonObject): ParsedStreamFormat? {
        val itag = obj["itag"]?.jsonPrimitive?.content?.toIntOrNull() ?: return null
        val mimeType = obj["mimeType"]?.jsonPrimitive?.content.orEmpty()

        val isAudioOnly = mimeType.startsWith("audio/", ignoreCase = true)
        if (!isAudioOnly && !mimeType.startsWith("video/", ignoreCase = true)) {
            return null
        }

        val directUrl = obj["url"]?.jsonPrimitive?.content

        // Check for cipher formats (cipher or signatureCipher)
        val cipherString = (obj["signatureCipher"] ?: obj["cipher"])?.jsonPrimitive?.content
        var cipherSig: String? = null
        var cipherParam: String? = null
        var cipherBaseUrl: String? = null

        if (!cipherString.isNullOrBlank()) {
            val pairs = cipherString.split("&")
            for (pair in pairs) {
                val kv = pair.split("=", limit = 2)
                if (kv.size == 2) {
                    val key = kv[0]
                    val value = try { URLDecoder.decode(kv[1], "UTF-8") } catch (_: Throwable) { kv[1] }
                    when (key) {
                        "s" -> cipherSig = value
                        "sp" -> cipherParam = value
                        "url" -> cipherBaseUrl = value
                    }
                }
            }
        }

        val bitrate = obj["bitrate"]?.jsonPrimitive?.content?.toIntOrNull()
        val averageBitrate = obj["averageBitrate"]?.jsonPrimitive?.content?.toIntOrNull() ?: bitrate
        val bitrateKbps = if (averageBitrate != null) averageBitrate / 1000 else null

        val sampleRate = obj["audioSampleRate"]?.jsonPrimitive?.content?.toIntOrNull()
        val channels = obj["audioChannels"]?.jsonPrimitive?.content?.toIntOrNull() ?: 2
        val contentLength = obj["contentLength"]?.jsonPrimitive?.content?.toLongOrNull()
        val approxDurationMs = obj["approxDurationMs"]?.jsonPrimitive?.content?.toLongOrNull()

        val codec = deriveCodec(mimeType)
        val container = deriveContainer(mimeType)

        val audioFormat = AudioFormat(
            codec = codec,
            container = container,
            bitrateKbps = bitrateKbps,
            sampleRateHz = sampleRate,
            channelCount = channels,
            contentLengthBytes = contentLength,
            mimeType = mimeType
        )

        // Capture forward-compatible metadata without sensitive parameters
        val metadata = mutableMapOf<String, String>()
        metadata["itag"] = itag.toString()
        obj["audioQuality"]?.jsonPrimitive?.content?.let { metadata["audioQuality"] = it }
        obj["quality"]?.jsonPrimitive?.content?.let { metadata["quality"] = it }
        obj["projectionType"]?.jsonPrimitive?.content?.let { metadata["projectionType"] = it }

        return ParsedStreamFormat(
            itag = itag,
            directUrl = directUrl,
            cipherSignature = cipherSig,
            cipherSignatureParam = cipherParam ?: "sig",
            cipherBaseUrl = cipherBaseUrl,
            audioFormat = audioFormat,
            approxDurationMs = approxDurationMs,
            isAudioOnly = isAudioOnly,
            rawMetadata = metadata
        )
    }

    private fun deriveCodec(mimeType: String): AudioCodec {
        val lower = mimeType.lowercase()
        return when {
            lower.contains("opus") -> AudioCodec.OPUS
            lower.contains("mp4a") || lower.contains("aac") -> AudioCodec.AAC
            lower.contains("flac") -> AudioCodec.FLAC
            lower.contains("vorbis") -> AudioCodec.VORBIS
            lower.contains("mp3") -> AudioCodec.MP3
            else -> AudioCodec.UNKNOWN
        }
    }

    private fun deriveContainer(mimeType: String): AudioContainer {
        val lower = mimeType.lowercase()
        return when {
            lower.contains("audio/webm") || lower.contains("video/webm") -> AudioContainer.WEBM
            lower.contains("audio/mp4") || lower.contains("video/mp4") || lower.contains("m4a") -> AudioContainer.MP4_M4A
            lower.contains("audio/ogg") -> AudioContainer.OGG
            lower.contains("audio/mp3") -> AudioContainer.MP3
            lower.contains("matroska") -> AudioContainer.MATROSKA
            else -> AudioContainer.RAW
        }
    }
}
