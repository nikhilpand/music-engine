package com.aurora.engine.player.android.error

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import com.aurora.engine.core.model.ErrorCategory
import com.aurora.engine.core.model.PlaybackError
import com.aurora.engine.core.model.PlaybackSource
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException

object PlaybackErrorMapper {

    /**
     * Translates an ExoPlayer/Media3 PlaybackException into Aurora's pure Kotlin PlaybackError.
     * Accurately differentiates network drops, HTTP codes (403, 404, 429), decoder crashes,
     * malformed containers, and expiration.
     */
    fun map(
        playbackException: PlaybackException,
        currentSource: PlaybackSource? = null,
        nowMs: Long = System.currentTimeMillis(),
        knownErrorContext: String? = null
    ): PlaybackError {
        val cause = playbackException.cause
        val httpException = findHttpException(playbackException)

        val (category, isRecoverable, code) = when {
            // Check if URL is expired or 403 occurred on an expired source
            currentSource?.isExpired(nowMs) == true -> {
                Triple(ErrorCategory.SOURCE_EXPIRED, true, "MEDIA3_SOURCE_EXPIRED")
            }

            // Explicit HTTP 403 Forbidden classification
            httpException?.responseCode == 403 -> {
                classifyHttp403(
                    httpException = httpException,
                    currentSource = currentSource,
                    nowMs = nowMs,
                    knownErrorContext = knownErrorContext
                )
            }

            // HTTP 429 Rate Limited
            httpException?.responseCode == 429 -> {
                Triple(ErrorCategory.RATE_LIMITED, true, "HTTP_429_RATE_LIMITED")
            }

            // HTTP 404 Not Found
            httpException?.responseCode == 404 -> {
                Triple(ErrorCategory.UNPLAYABLE, false, "HTTP_404_NOT_FOUND")
            }

            // HTTP 5xx Server Error
            httpException?.responseCode != null && httpException.responseCode in 500..599 -> {
                Triple(ErrorCategory.HTTP_SERVER, true, "HTTP_${httpException.responseCode}_SERVER_ERROR")
            }

            // Network Connection Timeout
            playbackException.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                    cause is SocketTimeoutException -> {
                Triple(ErrorCategory.TIMEOUT, true, "MEDIA3_NETWORK_TIMEOUT")
            }

            // Network Connection Failed
            playbackException.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                    cause is UnknownHostException ||
                    (cause is IOException && cause.message?.contains("unexpected end of stream", ignoreCase = true) == true) -> {
                Triple(ErrorCategory.NETWORK_FAILURE, true, "MEDIA3_NETWORK_DISCONNECTED")
            }

            // Malformed Media Container / Manifest
            playbackException.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
                    playbackException.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ||
                    playbackException.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> {
                Triple(ErrorCategory.MALFORMED_MEDIA, false, "MEDIA3_PARSER_MALFORMED")
            }

            // Decoder Initialization / Decoding Failure (must NOT be treated as provider failure)
            playbackException.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                    playbackException.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED -> {
                Triple(ErrorCategory.DECODER_FAILURE, true, "MEDIA3_DECODER_FAILURE")
            }

            // Unsupported Format
            playbackException.errorCode == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED -> {
                Triple(ErrorCategory.UNSUPPORTED_FORMAT, true, "MEDIA3_FORMAT_UNSUPPORTED")
            }

            // Generic IO Error
            playbackException.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> {
                Triple(ErrorCategory.IO, true, "MEDIA3_IO_UNSPECIFIED")
            }

            else -> {
                Triple(ErrorCategory.UNKNOWN, false, "MEDIA3_PLAYBACK_UNKNOWN")
            }
        }

        return PlaybackError(
            code = code,
            message = playbackException.message ?: "Playback failed with error code: ${playbackException.errorCodeName}",
            category = category,
            isRecoverable = isRecoverable,
            httpStatusCode = httpException?.responseCode,
            timestampMs = nowMs
        )
    }

    /**
     * Differentiates HTTP 403 Forbidden into specific domain categories:
     * - SOURCE_EXPIRED (re-resolvable)
     * - PROVIDER_REJECTION (triggers strategy fallback)
     * - AUTHENTICATION_REQUIRED (terminal / auth needed)
     * - BOT_DETECTION (triggers challenge or strategy fallback)
     * - UNKNOWN_FORBIDDEN (generic 403)
     */
    fun classifyHttp403(
        httpException: HttpDataSource.InvalidResponseCodeException?,
        currentSource: PlaybackSource?,
        nowMs: Long,
        knownErrorContext: String? = null
    ): Triple<ErrorCategory, Boolean, String> {
        // 1. Check if source metadata indicates expiration
        if (currentSource != null && currentSource.isExpired(nowMs)) {
            return Triple(ErrorCategory.SOURCE_EXPIRED, true, "HTTP_403_SOURCE_EXPIRED")
        }

        // 2. Check if URL has explicit expire/expires query parameter in the past
        if (currentSource is PlaybackSource.Progressive && checkUrlExpiryParam(currentSource.url, nowMs)) {
            return Triple(ErrorCategory.SOURCE_EXPIRED, true, "HTTP_403_URL_PARAM_EXPIRED")
        }

        // Extract context and response details
        val headerFields = httpException?.headerFields ?: emptyMap()
        val hasWwwAuth = headerFields.keys.any { it.equals("WWW-Authenticate", ignoreCase = true) }
        val bodyText = httpException?.responseBody?.let {
            try { String(it, Charsets.UTF_8) } catch (_: Exception) { "" }
        } ?: ""
        val messageText = httpException?.responseMessage ?: ""
        val combinedContext = "$messageText $bodyText ${knownErrorContext ?: ""}".lowercase()

        // 3. Authentication Required
        if (hasWwwAuth ||
            combinedContext.contains("login required") ||
            combinedContext.contains("authentication required") ||
            combinedContext.contains("unauthorized") ||
            combinedContext.contains("auth token missing") ||
            combinedContext.contains("sign in")
        ) {
            return Triple(ErrorCategory.AUTHENTICATION_REQUIRED, false, "HTTP_403_AUTHENTICATION_REQUIRED")
        }

        // 4. Bot Detection
        if (combinedContext.contains("bot") ||
            combinedContext.contains("captcha") ||
            combinedContext.contains("recaptcha") ||
            combinedContext.contains("robot") ||
            combinedContext.contains("automated queries") ||
            combinedContext.contains("unusual traffic") ||
            combinedContext.contains("challenge")
        ) {
            return Triple(ErrorCategory.BOT_DETECTION, true, "HTTP_403_BOT_DETECTION")
        }

        // 5. Explicit Expiration in response text
        if (combinedContext.contains("expired") ||
            combinedContext.contains("token expired") ||
            combinedContext.contains("signature has expired") ||
            combinedContext.contains("link expired")
        ) {
            return Triple(ErrorCategory.SOURCE_EXPIRED, true, "HTTP_403_EXPIRED_MESSAGE")
        }

        // 6. Provider Rejection
        if (combinedContext.contains("access denied") ||
            combinedContext.contains("geo") ||
            combinedContext.contains("restricted") ||
            combinedContext.contains("not allowed") ||
            combinedContext.contains("ip blocked") ||
            combinedContext.contains("country") ||
            combinedContext.contains("rejected") ||
            combinedContext.contains("forbidden by provider") ||
            combinedContext.contains("client rejected")
        ) {
            return Triple(ErrorCategory.PROVIDER_REJECTION, true, "HTTP_403_PROVIDER_REJECTION")
        }

        // 7. Unknown Forbidden (generic)
        return Triple(ErrorCategory.UNKNOWN_FORBIDDEN, false, "HTTP_403_UNKNOWN_FORBIDDEN")
    }

    private fun checkUrlExpiryParam(url: String, nowMs: Long): Boolean {
        return try {
            val uri = URI(url)
            val query = uri.query ?: return false
            val params = query.split("&").associate {
                val parts = it.split("=", limit = 2)
                parts[0] to (parts.getOrNull(1) ?: "")
            }
            val expireVal = params["expire"] ?: params["expires"]
            if (expireVal != null) {
                val expireSec = expireVal.toLongOrNull()
                if (expireSec != null) {
                    val expireMs = if (expireSec < 10_000_000_000L) expireSec * 1000L else expireSec
                    nowMs >= expireMs
                } else false
            } else false
        } catch (_: Exception) {
            false
        }
    }

    private fun findHttpException(throwable: Throwable?): HttpDataSource.InvalidResponseCodeException? {
        var current = throwable
        while (current != null) {
            if (current is HttpDataSource.InvalidResponseCodeException) {
                return current
            }
            current = current.cause
        }
        return null
    }
}
