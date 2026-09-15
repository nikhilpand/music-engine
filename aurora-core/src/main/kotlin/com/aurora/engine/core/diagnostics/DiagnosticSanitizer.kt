package com.aurora.engine.core.diagnostics

import java.net.URI

object DiagnosticSanitizer {
    private val SENSITIVE_PARAM_KEYS = setOf(
        "sig",
        "signature",
        "s",
        "n",
        "token",
        "auth",
        "authorization",
        "session",
        "session_id",
        "visitor_data",
        "visitorData",
        "cookie",
        "key",
        "api_key",
        "apiKey",
        "access_token",
        "pot",
        "po_token",
        "device_id",
        "deviceId",
        "cpn"
    )

    private val SENSITIVE_HEADER_KEYS = setOf(
        "authorization",
        "cookie",
        "set-cookie",
        "x-goog-authuser",
        "x-youtube-identity-token",
        "x-origin"
    )

    fun sanitizeUrl(rawUrl: String): String {
        if (rawUrl.isBlank()) return rawUrl

        return try {
            val uri = URI(rawUrl)
            val query = uri.rawQuery ?: return rawUrl
            val sanitizedQuery = query.split("&").joinToString("&") { param ->
                val parts = param.split("=", limit = 2)
                val key = parts[0]
                val lowerKey = key.lowercase()
                if (SENSITIVE_PARAM_KEYS.contains(lowerKey)) {
                    "$key=[REDACTED]"
                } else if (parts.size == 2) {
                    "$key=${parts[1]}"
                } else {
                    key
                }
            }

            val scheme = if (uri.scheme != null) "${uri.scheme}://" else ""
            val rawAuthority = uri.rawAuthority ?: ""
            val rawPath = uri.rawPath ?: ""
            val rawFragment = if (uri.rawFragment != null) "#${uri.rawFragment}" else ""

            "$scheme$rawAuthority$rawPath?$sanitizedQuery$rawFragment"
        } catch (_: Throwable) {
            // Fallback: regex replacement for common query param patterns
            var scrubbed = rawUrl
            for (key in SENSITIVE_PARAM_KEYS) {
                val regex = Regex("([?&]$key=)([^&#]+)", RegexOption.IGNORE_CASE)
                scrubbed = scrubbed.replace(regex, "$1[REDACTED]")
            }
            scrubbed
        }
    }

    fun sanitizeHeaders(headers: Map<String, String>): Map<String, String> {
        return headers.mapValues { (key, value) ->
            if (SENSITIVE_HEADER_KEYS.contains(key.lowercase())) {
                "[REDACTED]"
            } else {
                value
            }
        }
    }

    fun sanitizeProperty(key: String, value: String): String {
        val lowerKey = key.lowercase()
        return when {
            SENSITIVE_HEADER_KEYS.contains(lowerKey) || SENSITIVE_PARAM_KEYS.contains(lowerKey) -> "[REDACTED]"
            value.startsWith("http://") || value.startsWith("https://") -> sanitizeUrl(value)
            else -> value
        }
    }

    fun sanitizeProperties(properties: Map<String, String>): Map<String, String> {
        return properties.map { (k, v) -> k to sanitizeProperty(k, v) }.toMap()
    }
}
