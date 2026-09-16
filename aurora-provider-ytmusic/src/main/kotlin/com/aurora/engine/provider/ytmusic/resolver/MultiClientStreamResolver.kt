package com.aurora.engine.provider.ytmusic.resolver

import com.aurora.engine.core.model.AudioCodec
import com.aurora.engine.core.model.AudioContainer
import com.aurora.engine.core.model.AudioFormat
import com.aurora.engine.core.model.ErrorCategory
import com.aurora.engine.core.model.PlaybackError
import com.aurora.engine.core.model.PlaybackSource
import com.aurora.engine.core.model.Track
import com.aurora.engine.core.provider.ResolutionContext
import com.aurora.engine.core.provider.ResolutionResult
import com.aurora.engine.provider.ytmusic.cipher.CipherService
import com.aurora.engine.provider.ytmusic.config.ClientConfigEntry
import com.aurora.engine.provider.ytmusic.config.ClientLadder
import com.aurora.engine.provider.ytmusic.config.toInnerTubeClientConfig
import com.aurora.engine.provider.ytmusic.parser.CatalogResponseParser
import com.aurora.engine.provider.ytmusic.parser.PlayerResponseParser
import com.aurora.engine.provider.ytmusic.session.InnerTubeSession
import com.aurora.engine.provider.ytmusic.token.PlaybackTokenProvider
import com.aurora.engine.provider.ytmusic.transport.TransportDecision
import com.aurora.engine.provider.ytmusic.transport.TransportSelector
import com.aurora.engine.provider.ytmusic.transport.TransportType
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.security.SecureRandom
import kotlin.math.abs

/**
 * Multi-client, capability-filtered stream resolver.
 *
 * Implements:
 * 1. Cache-first lookup using [StreamUrlCache] with monotonic generation tokens.
 * 2. Client fallback ladder using [ClientLadder] with quarantine on failures.
 * 3. Duration cross-validation (|approxDurationMs - expectedDurationMs| <= 5000ms).
 * 4. Two-phase cipher deobfuscation (signature deciphering + n-param transform) via [CipherService].
 * 5. Capability and health-aware transport selection via [TransportSelector].
 * 6. Metadata fallback chain using [metadataLadder].
 */
class MultiClientStreamResolver(
    private val session: InnerTubeSession,
    val clientLadder: ClientLadder,
    val urlCache: StreamUrlCache,
    val cipherService: CipherService,
    val transportSelector: TransportSelector = TransportSelector(),
    val tokenProvider: PlaybackTokenProvider? = null,
    val metadataLadder: ClientLadder? = null,
    private val playerScriptUrlProvider: (suspend () -> String?)? = null,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val secureRandom = SecureRandom()
    private var cachedPlayerScriptUrl: String? = null

    suspend fun resolve(
        track: Track,
        context: ResolutionContext,
        preferSabr: Boolean = false
    ): ResolutionResult {
        // 1. Cache check (cache-first, generation-tracked)
        val cached = urlCache.get(track.id)
        if (cached != null && !cached.isExpired()) {
            val audioFormat = AudioFormat(
                codec = AudioCodec.OPUS,
                container = AudioContainer.WEBM,
                bitrateKbps = 160,
                mimeType = "audio/webm"
            )
            val source = when (cached.transportType) {
                StreamUrlCache.TransportType.PROGRESSIVE -> PlaybackSource.Progressive(
                    trackId = track.id,
                    url = cached.url,
                    audioFormat = audioFormat,
                    expiresAtMs = cached.expiresAtMs,
                    customCacheKey = "aurora:track:${track.id}"
                )
                StreamUrlCache.TransportType.SABR -> PlaybackSource.Sabr(
                    trackId = track.id,
                    serverEndpoint = cached.url,
                    clientContextJson = "{}",
                    audioFormat = audioFormat,
                    expiresAtMs = cached.expiresAtMs,
                    customCacheKey = "aurora:track:${track.id}"
                )
            }
            return ResolutionResult.Success(
                sources = listOf(source),
                strategyId = cached.clientName,
                expiresAtMs = cached.expiresAtMs,
                latencyMs = 0
            )
        }

        // 2. Start resolution with monotonic generation tracking
        val generation = urlCache.startResolution(track.id)
        val startTime = clock()

        // 3. Obtain expected duration for cross-validation
        var expectedDurationMs: Long? = track.durationMs?.takeIf { it > 0 }
        if (expectedDurationMs == null && metadataLadder != null) {
            expectedDurationMs = fetchDurationFromMetadata(track.id)
        }

        // 4. Client ladder traversal
        val candidates = clientLadder.availableClients()
        if (candidates.isEmpty()) {
            return ResolutionResult.Failure(
                error = PlaybackError(
                    code = "ALL_CLIENTS_QUARANTINED",
                    message = "All clients in ladder are quarantined or unavailable for track ${track.id}",
                    category = ErrorCategory.PROVIDER_REJECTION,
                    isRecoverable = false
                ),
                strategyId = null,
                canFallback = true
            )
        }

        var lastError: PlaybackError? = null
        var lastFailedClient: String? = null

        for (client in candidates) {
            // Check PoToken capability
            val requiresToken = client.requiresPoToken
            val poToken = if (requiresToken) {
                tokenProvider?.getPoToken(track.id)
            } else null

            if (requiresToken && poToken.isNullOrBlank()) {
                clientLadder.recordFailure(client.clientName)
                lastError = PlaybackError(
                    code = "TOKEN_FAILURE",
                    message = "Client ${client.clientName} requires poToken but token unavailable",
                    category = ErrorCategory.TOKEN_FAILURE,
                    isRecoverable = true
                )
                lastFailedClient = client.clientName
                continue
            }

            // Build request
            val innerTubeConfig = client.toInnerTubeClientConfig()
            val contextObj = session.buildContextPayload(innerTubeConfig)
            val cpn = generateCpn()

            val requestPayload = buildJsonObject {
                put("context", contextObj)
                put("videoId", track.id)
                put("cpn", cpn)
                put("contentCheckOk", true)
                put("racyCheckOk", true)
                if (!poToken.isNullOrBlank()) {
                    put("serviceIntegrityDimensions", buildJsonObject {
                        put("poToken", poToken)
                    })
                }
            }

            val postResult = try {
                session.postJson("/youtubei/v1/player", requestPayload, innerTubeConfig)
            } catch (t: Throwable) {
                clientLadder.recordFailure(client.clientName)
                lastError = PlaybackError(
                    code = "NETWORK_ERROR",
                    message = t.message ?: "Failed to reach player endpoint",
                    category = ErrorCategory.NETWORK,
                    isRecoverable = true
                )
                lastFailedClient = client.clientName
                continue
            }

            val response = postResult.getOrNull()
            if (response == null || !response.isSuccess) {
                val statusCode = response?.statusCode ?: 0
                val is403 = statusCode == 403
                val errorCategory = when {
                    is403 -> ErrorCategory.PROVIDER_REJECTION
                    statusCode == 429 -> ErrorCategory.RATE_LIMITED
                    statusCode in 500..599 -> ErrorCategory.HTTP_SERVER
                    else -> ErrorCategory.HTTP_CLIENT
                }
                clientLadder.recordFailure(client.clientName)
                lastError = PlaybackError(
                    code = "HTTP_$statusCode",
                    message = "Player endpoint returned HTTP error $statusCode",
                    category = errorCategory,
                    isRecoverable = !is403,
                    httpStatusCode = statusCode
                )
                lastFailedClient = client.clientName
                continue
            }

            val parsed = PlayerResponseParser.parse(response.body)
            if (!parsed.isPlayable || parsed.formats.isEmpty()) {
                clientLadder.recordFailure(client.clientName)
                lastError = PlaybackError(
                    code = "PLAYBACK_STATUS_${parsed.status}",
                    message = parsed.reason ?: "Unplayable status: ${parsed.status}",
                    category = ErrorCategory.UNPLAYABLE,
                    isRecoverable = false
                )
                lastFailedClient = client.clientName
                continue
            }

            // Duration cross-validation: |expectedDurationMs - approxDurationMs| <= 5000ms
            val validExpectedDuration = expectedDurationMs ?: parsed.videoDurationMs
            val durationValidFormats = if (validExpectedDuration != null && validExpectedDuration > 0) {
                parsed.formats.filter { fmt ->
                    fmt.approxDurationMs == null || abs(fmt.approxDurationMs - validExpectedDuration) <= 5000L
                }
            } else {
                parsed.formats
            }

            if (durationValidFormats.isEmpty()) {
                clientLadder.recordFailure(client.clientName)
                lastError = PlaybackError(
                    code = "DURATION_MISMATCH",
                    message = "Client ${client.clientName} returned formats failing duration validation",
                    category = ErrorCategory.INVALID_RESPONSE,
                    isRecoverable = true
                )
                lastFailedClient = client.clientName
                continue
            }

            // Transport selection based on health & capabilities
            val transportDecision = transportSelector.select(client, preferSabr)
            val expiresAtMs = clock() + ((parsed.expiresInSeconds ?: 21600L) * 1000L)

            // Select best format
            val bestFormat = durationValidFormats.maxByOrNull { it.audioFormat.bitrateKbps ?: 0 }
                ?: durationValidFormats.first()

            var resolvedUrl: String? = null
            var cipherFailed = false

            // Cipher deciphering & n-parameter transformation
            if (bestFormat.requiresCipher) {
                if (!cipherService.isOperational) {
                    cipherFailed = true
                } else {
                    try {
                        val playerScriptUrl = resolvePlayerScriptUrl()
                        val decipheredSig = cipherService.decipherSignature(bestFormat.cipherSignature!!, playerScriptUrl)
                        val sigParam = bestFormat.cipherSignatureParam ?: "sig"
                        val sep = if (bestFormat.cipherBaseUrl!!.contains("?")) "&" else "?"
                        var signed = "${bestFormat.cipherBaseUrl}$sep$sigParam=$decipheredSig"

                        // n-param transform
                        val nMatch = Regex("(?:^|[?&])n=([^&#]+)").find(signed)
                        if (nMatch != null) {
                            val rawN = nMatch.groupValues[1]
                            val transformedN = cipherService.transformN(rawN, playerScriptUrl)
                            signed = signed.replace("n=$rawN", "n=$transformedN")
                        }
                        resolvedUrl = signed
                    } catch (_: Throwable) {
                        cipherFailed = true
                    }
                }
            } else if (bestFormat.directUrl != null) {
                var url = bestFormat.directUrl
                val needsNTransform = url.contains("n=") && client.requiresCipher
                if (needsNTransform) {
                    if (!cipherService.isOperational) {
                        cipherFailed = true
                    } else {
                        try {
                            val playerScriptUrl = resolvePlayerScriptUrl()
                            val nMatch = Regex("(?:^|[?&])n=([^&#]+)").find(url)
                            if (nMatch != null) {
                                val rawN = nMatch.groupValues[1]
                                val transformedN = cipherService.transformN(rawN, playerScriptUrl)
                                url = url.replace("n=$rawN", "n=$transformedN")
                            }
                            resolvedUrl = url
                        } catch (_: Throwable) {
                            cipherFailed = true
                        }
                    }
                } else {
                    resolvedUrl = url
                }
            }

            if (cipherFailed) {
                clientLadder.recordFailure(client.clientName)
                lastError = PlaybackError(
                    code = "CIPHER_FAILURE",
                    message = "Cipher operation failed for client ${client.clientName}",
                    category = ErrorCategory.TRANSFORMATION_REQUIRED,
                    isRecoverable = true
                )
                lastFailedClient = client.clientName
                continue
            }

            // Build sources
            val sources = mutableListOf<PlaybackSource>()

            val progressiveSource = if (!resolvedUrl.isNullOrBlank()) {
                PlaybackSource.Progressive(
                    trackId = track.id,
                    url = resolvedUrl,
                    audioFormat = bestFormat.audioFormat,
                    headers = mapOf("User-Agent" to client.userAgent),
                    expiresAtMs = expiresAtMs,
                    customCacheKey = "aurora:track:${track.id}"
                )
            } else null

            val sabrSource = if (client.supportsSabr && !parsed.serverEndpoint.isNullOrBlank()) {
                PlaybackSource.Sabr(
                    trackId = track.id,
                    serverEndpoint = parsed.serverEndpoint,
                    clientContextJson = contextObj.toString(),
                    ustreamerConfig = parsed.ustreamerConfig,
                    audioFormat = bestFormat.audioFormat,
                    expiresAtMs = expiresAtMs,
                    customCacheKey = "aurora:track:${track.id}"
                )
            } else null

            // Order sources according to transport decision
            if (transportDecision.type == TransportType.SABR && sabrSource != null) {
                sources.add(sabrSource)
                if (progressiveSource != null) sources.add(progressiveSource)
            } else {
                if (progressiveSource != null) sources.add(progressiveSource)
                if (sabrSource != null) sources.add(sabrSource)
            }

            if (sources.isEmpty()) {
                clientLadder.recordFailure(client.clientName)
                lastError = PlaybackError(
                    code = "NO_USABLE_SOURCES",
                    message = "No usable playback sources constructed for client ${client.clientName}",
                    category = ErrorCategory.INVALID_RESPONSE,
                    isRecoverable = true
                )
                lastFailedClient = client.clientName
                continue
            }

            // Promotion on success
            clientLadder.recordSuccess(client.clientName)

            // Cache primary URL
            val primaryTransport = if (sources.first() is PlaybackSource.Sabr) {
                StreamUrlCache.TransportType.SABR
            } else {
                StreamUrlCache.TransportType.PROGRESSIVE
            }
            val cacheUrl = if (primaryTransport == StreamUrlCache.TransportType.SABR) {
                parsed.serverEndpoint ?: resolvedUrl ?: ""
            } else {
                resolvedUrl ?: parsed.serverEndpoint ?: ""
            }

            urlCache.completeResolution(
                mediaId = track.id,
                generation = generation,
                url = cacheUrl,
                expiresAtMs = expiresAtMs,
                clientName = client.clientName,
                transportType = primaryTransport,
                formatId = bestFormat.itag.toString()
            )

            return ResolutionResult.Success(
                sources = sources,
                strategyId = client.clientName,
                latencyMs = clock() - startTime,
                expiresAtMs = expiresAtMs
            )
        }

        return ResolutionResult.Failure(
            error = lastError ?: PlaybackError(
                code = "ALL_CLIENTS_EXHAUSTED",
                message = "All clients in ladder failed to resolve track ${track.id}",
                category = ErrorCategory.PROVIDER_REJECTION,
                isRecoverable = false
            ),
            strategyId = lastFailedClient,
            canFallback = true
        )
    }

    suspend fun fetchDurationFromMetadata(trackId: String): Long? {
        val ladder = metadataLadder ?: return null
        for (client in ladder.availableClients()) {
            try {
                val config = client.toInnerTubeClientConfig()
                val contextObj = session.buildContextPayload(config)
                val payload = buildJsonObject {
                    put("context", contextObj)
                    put("videoId", trackId)
                    put("isAudioOnly", true)
                }
                val res = session.postJson("/youtubei/v1/next", payload, config).getOrNull()
                if (res != null && res.isSuccess) {
                    ladder.recordSuccess(client.clientName)
                    val tracks = CatalogResponseParser.parseWatchNextQueue(res.body)
                    val matching = tracks.firstOrNull { it.id == trackId }
                    if (matching?.durationMs != null && matching.durationMs > 0) {
                        return matching.durationMs
                    }
                } else {
                    ladder.recordFailure(client.clientName)
                }
            } catch (_: Exception) {
                ladder.recordFailure(client.clientName)
            }
        }
        return null
    }

    private suspend fun resolvePlayerScriptUrl(): String {
        val provided = playerScriptUrlProvider?.invoke()
        if (!provided.isNullOrBlank()) return provided
        cachedPlayerScriptUrl?.let { return it }
        val defaultUrl = "https://www.youtube.com/s/player/current/player_ias.vflset/en_US/base.js"
        cachedPlayerScriptUrl = defaultUrl
        return defaultUrl
    }

    private fun generateCpn(): String {
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"
        val sb = StringBuilder(16)
        for (i in 0 until 16) {
            sb.append(chars[secureRandom.nextInt(chars.length)])
        }
        return sb.toString()
    }
}
