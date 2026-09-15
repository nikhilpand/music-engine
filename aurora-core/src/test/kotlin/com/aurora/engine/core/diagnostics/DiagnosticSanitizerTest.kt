package com.aurora.engine.core.diagnostics

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class DiagnosticSanitizerTest {

    @Test
    @DisplayName("Redacts sensitive query parameters like sig, token, s, n, and key from URLs")
    fun testSanitizeUrlParameters() {
        val rawUrl = "https://rr1---sn-4g5edn6s.googlevideo.com/videoplayback?expire=1710000000&ei=xyz&ip=1.2.3.4&id=o-ABC&itag=251&source=youtube&requiressl=yes&sig=MEQCICx123abc456==&s=encrypted_sig_val&n=transform_token&pot=PoTokenValue123&ratebypass=yes&mime=audio%2Fwebm"

        val sanitized = DiagnosticSanitizer.sanitizeUrl(rawUrl)

        // Sensitive parameters must be redacted
        assertThat(sanitized).contains("sig=[REDACTED]")
        assertThat(sanitized).contains("s=[REDACTED]")
        assertThat(sanitized).contains("n=[REDACTED]")
        assertThat(sanitized).contains("pot=[REDACTED]")

        // Original secret values must NOT appear in output
        assertThat(sanitized).doesNotContain("MEQCICx123abc456==")
        assertThat(sanitized).doesNotContain("encrypted_sig_val")
        assertThat(sanitized).doesNotContain("transform_token")
        assertThat(sanitized).doesNotContain("PoTokenValue123")

        // Non-sensitive diagnostic parameters must be preserved
        assertThat(sanitized).contains("itag=251")
        assertThat(sanitized).contains("source=youtube")
        assertThat(sanitized).contains("ratebypass=yes")
        assertThat(sanitized).contains("mime=audio%2Fwebm")
    }

    @Test
    @DisplayName("Redacts sensitive headers such as Authorization and Cookie")
    fun testSanitizeHeaders() {
        val headers = mapOf(
            "Authorization" to "Bearer ya29.secret_token_value_here",
            "Cookie" to "VISITOR_INFO1_LIVE=xyz; SAPISID=abc123",
            "User-Agent" to "AuroraMusicEngine/1.2 (Linux; Android 14)",
            "Accept-Encoding" to "gzip, deflate, br"
        )

        val sanitized = DiagnosticSanitizer.sanitizeHeaders(headers)

        assertThat(sanitized["Authorization"]).isEqualTo("[REDACTED]")
        assertThat(sanitized["Cookie"]).isEqualTo("[REDACTED]")
        assertThat(sanitized["User-Agent"]).isEqualTo("AuroraMusicEngine/1.2 (Linux; Android 14)")
        assertThat(sanitized["Accept-Encoding"]).isEqualTo("gzip, deflate, br")
    }

    @Test
    @DisplayName("Sanitizes sensitive property values")
    fun testSanitizeProperty() {
        val tokenVal = DiagnosticSanitizer.sanitizeProperty("access_token", "secret123")
        assertThat(tokenVal).isEqualTo("[REDACTED]")

        val normalVal = DiagnosticSanitizer.sanitizeProperty("bitrate", "160kbps")
        assertThat(normalVal).isEqualTo("160kbps")

        val urlVal = DiagnosticSanitizer.sanitizeProperty("stream_url", "https://cdn.example.com/audio?key=supersecret&format=opus")
        assertThat(urlVal).contains("key=[REDACTED]")
        assertThat(urlVal).contains("format=opus")
    }
}
