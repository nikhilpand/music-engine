package com.aurora.engine.provider.ytmusic.parser

import com.aurora.engine.core.model.AudioCodec
import com.aurora.engine.core.model.AudioContainer
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class PlayerResponseParserTest {

    @Test
    @DisplayName("Parses standard player response with direct audio-only adaptiveFormats")
    fun testParseDirectAdaptiveFormats() {
        val jsonPayload = """
        {
            "playabilityStatus": {
                "status": "OK"
            },
            "streamingData": {
                "expiresInSeconds": "21600",
                "adaptiveFormats": [
                    {
                        "itag": 251,
                        "url": "https://rr1.googlevideo.com/videoplayback?itag=251",
                        "mimeType": "audio/webm; codecs=\"opus\"",
                        "bitrate": 160000,
                        "averageBitrate": 154000,
                        "audioSampleRate": "48000",
                        "audioChannels": 2,
                        "contentLength": "4567890",
                        "approxDurationMs": "210000",
                        "audioQuality": "AUDIO_QUALITY_MEDIUM"
                    },
                    {
                        "itag": 140,
                        "url": "https://rr1.googlevideo.com/videoplayback?itag=140",
                        "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"",
                        "bitrate": 128000,
                        "averageBitrate": 127000,
                        "audioSampleRate": "44100",
                        "audioChannels": 2,
                        "contentLength": "3890123",
                        "approxDurationMs": "210000",
                        "audioQuality": "AUDIO_QUALITY_MEDIUM"
                    }
                ]
            }
        }
        """.trimIndent()

        val parsed = PlayerResponseParser.parse(jsonPayload)

        assertThat(parsed.isPlayable).isTrue()
        assertThat(parsed.status).isEqualTo("OK")
        assertThat(parsed.expiresInSeconds).isEqualTo(21600L)
        assertThat(parsed.formats).hasSize(2)

        val opusFormat = parsed.formats[0]
        assertThat(opusFormat.itag).isEqualTo(251)
        assertThat(opusFormat.directUrl).isEqualTo("https://rr1.googlevideo.com/videoplayback?itag=251")
        assertThat(opusFormat.audioFormat.codec).isEqualTo(AudioCodec.OPUS)
        assertThat(opusFormat.audioFormat.container).isEqualTo(AudioContainer.WEBM)
        assertThat(opusFormat.audioFormat.bitrateKbps).isEqualTo(154)
        assertThat(opusFormat.audioFormat.sampleRateHz).isEqualTo(48000)
        assertThat(opusFormat.requiresCipher).isFalse()

        val aacFormat = parsed.formats[1]
        assertThat(aacFormat.itag).isEqualTo(140)
        assertThat(aacFormat.audioFormat.codec).isEqualTo(AudioCodec.AAC)
        assertThat(aacFormat.audioFormat.container).isEqualTo(AudioContainer.MP4_M4A)
        assertThat(aacFormat.audioFormat.bitrateKbps).isEqualTo(127)
    }

    @Test
    @DisplayName("Parses web response containing signatureCipher")
    fun testParseSignatureCipher() {
        val jsonPayload = """
        {
            "playabilityStatus": {
                "status": "OK"
            },
            "streamingData": {
                "expiresInSeconds": "18000",
                "adaptiveFormats": [
                    {
                        "itag": 251,
                        "signatureCipher": "s=encrypted_signature_payload&sp=sig&url=https%3A%2F%2Frr2.googlevideo.com%2Fvideoplayback%3Fitag%3D251",
                        "mimeType": "audio/webm; codecs=\"opus\"",
                        "bitrate": 160000
                    }
                ]
            }
        }
        """.trimIndent()

        val parsed = PlayerResponseParser.parse(jsonPayload)

        assertThat(parsed.isPlayable).isTrue()
        val format = parsed.formats.first()
        assertThat(format.directUrl).isNull()
        assertThat(format.requiresCipher).isTrue()
        assertThat(format.cipherSignature).isEqualTo("encrypted_signature_payload")
        assertThat(format.cipherSignatureParam).isEqualTo("sig")
        assertThat(format.cipherBaseUrl).isEqualTo("https://rr2.googlevideo.com/videoplayback?itag=251")
    }

    @Test
    @DisplayName("Handles unplayable responses like LOGIN_REQUIRED gracefully")
    fun testUnplayableResponse() {
        val jsonPayload = """
        {
            "playabilityStatus": {
                "status": "LOGIN_REQUIRED",
                "reason": "Sign in to confirm your age"
            }
        }
        """.trimIndent()

        val parsed = PlayerResponseParser.parse(jsonPayload)

        assertThat(parsed.isPlayable).isFalse()
        assertThat(parsed.status).isEqualTo("LOGIN_REQUIRED")
        assertThat(parsed.reason).isEqualTo("Sign in to confirm your age")
        assertThat(parsed.formats).isEmpty()
    }

    @Test
    @DisplayName("Handles malformed or missing JSON without throwing exception")
    fun testMalformedJson() {
        val parsed = PlayerResponseParser.parse("<!DOCTYPE html><html><body>Error 404</body></html>")

        assertThat(parsed.isPlayable).isFalse()
        assertThat(parsed.status).isEqualTo("PARSE_ERROR")
        assertThat(parsed.formats).isEmpty()
    }

    @Test
    @DisplayName("Handles response where status is OK but streamingData is missing")
    fun testMissingStreamingData() {
        val parsed = PlayerResponseParser.parse("""{"playabilityStatus": {"status": "OK"}}""")

        assertThat(parsed.isPlayable).isFalse()
        assertThat(parsed.status).isEqualTo("MISSING_STREAMING_DATA")
    }
}
