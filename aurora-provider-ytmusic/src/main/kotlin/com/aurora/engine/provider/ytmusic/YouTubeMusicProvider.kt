package com.aurora.engine.provider.ytmusic

import com.aurora.engine.core.diagnostics.DiagnosticSanitizer
import com.aurora.engine.core.model.ErrorCategory
import com.aurora.engine.core.model.PlaybackError
import com.aurora.engine.core.model.PlaybackSource
import com.aurora.engine.core.model.Track
import com.aurora.engine.core.provider.MusicProvider
import com.aurora.engine.core.provider.ResolutionContext
import com.aurora.engine.core.provider.ResolutionResult
import com.aurora.engine.core.strategy.FailureType
import com.aurora.engine.core.strategy.StrategyRegistry
import com.aurora.engine.provider.ytmusic.parser.AlbumDetails
import com.aurora.engine.provider.ytmusic.parser.ArtistDetails
import com.aurora.engine.provider.ytmusic.parser.CatalogResponseParser
import com.aurora.engine.provider.ytmusic.parser.ParsedPlayerResponse
import com.aurora.engine.provider.ytmusic.parser.ParsedStreamFormat
import com.aurora.engine.provider.ytmusic.parser.PlayerResponseParser
import com.aurora.engine.provider.ytmusic.parser.PlaylistDetails
import com.aurora.engine.provider.ytmusic.config.toInnerTubeClientConfig
import com.aurora.engine.provider.ytmusic.recovery.PlaybackErrorType
import com.aurora.engine.provider.ytmusic.recovery.RecoveryManager
import com.aurora.engine.provider.ytmusic.resolver.MultiClientStreamResolver
import com.aurora.engine.provider.ytmusic.session.InnerTubeClientConfig
import com.aurora.engine.provider.ytmusic.session.InnerTubeException
import com.aurora.engine.provider.ytmusic.session.InnerTubeSession
import com.aurora.engine.provider.ytmusic.strategy.YouTubePlaybackStrategy
import com.aurora.engine.provider.ytmusic.token.DefaultPlaybackTokenProvider
import com.aurora.engine.provider.ytmusic.token.PlaybackTokenProvider
import com.aurora.engine.provider.ytmusic.transform.PassThroughPlayerTransformProvider
import com.aurora.engine.provider.ytmusic.transform.PlayerTransformProvider
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.security.SecureRandom

class YouTubeMusicProvider(
    val session: InnerTubeSession = InnerTubeSession(),
    val strategyRegistry: StrategyRegistry = StrategyRegistry(),
    val transformProvider: PlayerTransformProvider = PassThroughPlayerTransformProvider(),
    val tokenProvider: PlaybackTokenProvider = DefaultPlaybackTokenProvider(),
    val streamResolver: MultiClientStreamResolver? = null,
    val recoveryManager: RecoveryManager = RecoveryManager(),
    private val clock: () -> Long = { System.currentTimeMillis() }
) : MusicProvider {

    override val providerId: String = "ytmusic"

    private val secureRandom = SecureRandom()

    init {
        // Automatically register YouTube default strategies into the StrategyRegistry
        for (strategy in YouTubePlaybackStrategy.DEFAULT_STRATEGIES) {
            strategyRegistry.register(strategy)
        }
    }

    // ==========================================
    // PlaybackProvider Implementation
    // ==========================================

    override suspend fun resolvePlayback(
        context: ResolutionContext
    ): ResolutionResult {

        /*
         * Phase 5 primary path:
         *
         * MultiClientStreamResolver
         *      ↓
         * ClientLadder
         *      ↓
         * cache / client fallback / cipher / transport
         *
         * The old StrategyRegistry path remains as a compatibility fallback.
         */
        streamResolver?.let { resolver ->

            val startTime = clock()

            try {
                val result = resolver.resolve(
                    track = context.track,
                    context = context,
                    preferSabr = false
                )

                when (result) {
                    is ResolutionResult.Success -> {
                        recoveryManager.onRecoverySuccess(context.track.id)

                        return result.copy(
                            latencyMs = clock() - startTime
                        )
                    }

                    is ResolutionResult.Failure -> {
                        /*
                         * Do not immediately return.
                         * The old strategy resolver remains available as a
                         * backward-compatible fallback.
                         */
                    }
                }
            } catch (_: Throwable) {
                /*
                 * Resolver failure must not destroy the existing playback path.
                 * Fall through to StrategyRegistry.
                 */
            }
        }

        // ============================================================
        // LEGACY / COMPATIBILITY FALLBACK
        // ============================================================

        val eligibleStrategies = strategyRegistry
            .getEligibleStrategies(context)
            .filterIsInstance<YouTubePlaybackStrategy>()

        if (eligibleStrategies.isEmpty()) {
            return ResolutionResult.Failure(
                error = PlaybackError(
                    code = "NO_ELIGIBLE_STRATEGIES",
                    message = "No eligible playback strategies available for track ${context.track.id}",
                    category = ErrorCategory.PROVIDER_REJECTION,
                    isRecoverable = false
                ),
                strategyId = null,
                canFallback = false
            )
        }

        var lastError: PlaybackError? = null
        var failedStrategyId: String? = null

        for (strategy in eligibleStrategies) {

            val startTime = clock()

            try {
                val candidateResult = resolveWithStrategy(
                    track = context.track,
                    context = context,
                    strategy = strategy
                )

                when (candidateResult) {

                    is ResolutionResult.Success -> {
                        val latency = clock() - startTime

                        strategyRegistry.reportSuccess(
                            strategy.id,
                            latency
                        )

                        return candidateResult.copy(
                            latencyMs = latency
                        )
                    }

                    is ResolutionResult.Failure -> {

                        val latency = clock() - startTime

                        lastError = candidateResult.error
                        failedStrategyId = strategy.id

                        val failureType =
                            classifyFailure(candidateResult.error)

                        strategyRegistry.reportFailure(
                            strategy.id,
                            failureType,
                            latency
                        )

                        if (!candidateResult.canFallback) {
                            return candidateResult.copy(
                                latencyMs = latency
                            )
                        }
                    }
                }

            } catch (ite: InnerTubeException) {

                val latency = clock() - startTime

                lastError = ite.playbackError
                failedStrategyId = strategy.id

                strategyRegistry.reportFailure(
                    strategy.id,
                    classifyFailure(ite.playbackError),
                    latency
                )

            } catch (t: Throwable) {

                val latency = clock() - startTime

                val error = PlaybackError(
                    code = "UNEXPECTED_RESOLVER_ERROR",
                    message = t.message ?: "Unknown exception during resolution",
                    category = ErrorCategory.INVALID_RESPONSE,
                    isRecoverable = true
                )

                lastError = error
                failedStrategyId = strategy.id

                strategyRegistry.reportFailure(
                    strategy.id,
                    FailureType.INVALID_RESPONSE,
                    latency
                )
            }
        }

        return ResolutionResult.Failure(
            error = lastError ?: PlaybackError(
                code = "ALL_RESOLUTION_PATHS_EXHAUSTED",
                message = "All playback resolution paths failed for track ${context.track.id}",
                category = ErrorCategory.PROVIDER_REJECTION,
                isRecoverable = false
            ),
            strategyId = failedStrategyId,
            canFallback = false
        )
    }

    private suspend fun resolveWithStrategy(
        track: Track,
        context: ResolutionContext,
        strategy: YouTubePlaybackStrategy
    ): ResolutionResult {
        val clientConfig = strategy.clientConfig

        // 1. Explicit token verification if strategy requires token
        val requiresToken = strategy.capabilities.requiresPoToken || clientConfig.requiresPoToken
        val poToken = if (requiresToken) {
            tokenProvider.getPoToken(track.id)
        } else null

        if (requiresToken && poToken.isNullOrBlank()) {
            return ResolutionResult.Failure(
                error = PlaybackError(
                    code = "TOKEN_FAILURE",
                    message = "Strategy ${strategy.id} requires poToken but token acquisition failed",
                    category = ErrorCategory.TOKEN_FAILURE,
                    isRecoverable = true
                ),
                strategyId = strategy.id,
                canFallback = true
            )
        }

        // 2. Build /youtubei/v1/player payload
        val contextObj = session.buildContextPayload(clientConfig)
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

        // 3. Dispatch request
        val responseResult = session.postJson(
            endpoint = "/youtubei/v1/player",
            payload = requestPayload,
            clientConfig = clientConfig
        )

        val innerTubeResponse = responseResult.getOrThrow()

        if (!innerTubeResponse.isSuccess) {
            val isBot = innerTubeResponse.statusCode == 403 &&
                (innerTubeResponse.body.contains("bot", ignoreCase = true) ||
                 innerTubeResponse.body.contains("captcha", ignoreCase = true) ||
                 innerTubeResponse.body.contains("robot", ignoreCase = true))

            val errorCategory = when {
                isBot -> ErrorCategory.BOT_DETECTION
                innerTubeResponse.statusCode == 403 -> ErrorCategory.PROVIDER_REJECTION
                innerTubeResponse.statusCode == 429 -> ErrorCategory.RATE_LIMITED
                innerTubeResponse.statusCode in 500..599 -> ErrorCategory.HTTP_SERVER
                else -> ErrorCategory.HTTP_CLIENT
            }
            return ResolutionResult.Failure(
                error = PlaybackError(
                    code = "HTTP_${innerTubeResponse.statusCode}",
                    message = "Player endpoint returned HTTP error ${innerTubeResponse.statusCode}",
                    category = errorCategory,
                    isRecoverable = (innerTubeResponse.statusCode != 403),
                    httpStatusCode = innerTubeResponse.statusCode
                ),
                strategyId = strategy.id,
                canFallback = true
            )
        }

        // 4. Parse Player Response
        val parsed = PlayerResponseParser.parse(innerTubeResponse.body)

        if (!parsed.isPlayable) {
            val (category, isRecoverable, canFallback) = when {
                parsed.status.equals("LOGIN_REQUIRED", ignoreCase = true) -> {
                    Triple(ErrorCategory.AUTHENTICATION_REQUIRED, false, false)
                }
                parsed.status.equals("CONTENT_CHECK_REQUIRED", ignoreCase = true) ||
                parsed.status.equals("AGE_CHECK_REQUIRED", ignoreCase = true) -> {
                    Triple(ErrorCategory.CONTENT_RESTRICTION, false, true)
                }
                parsed.status.equals("UNPLAYABLE", ignoreCase = true) -> {
                    Triple(ErrorCategory.UNPLAYABLE, false, true)
                }
                parsed.status.contains("BOT", ignoreCase = true) ||
                parsed.status.contains("CAPTCHA", ignoreCase = true) -> {
                    Triple(ErrorCategory.BOT_DETECTION, true, true)
                }
                else -> Triple(ErrorCategory.INVALID_RESPONSE, true, true)
            }

            return ResolutionResult.Failure(
                error = PlaybackError(
                    code = "PLAYBACK_STATUS_${parsed.status}",
                    message = parsed.reason ?: "Track playability status was ${parsed.status}",
                    category = category,
                    isRecoverable = isRecoverable
                ),
                strategyId = strategy.id,
                canFallback = canFallback
            )
        }

        if (parsed.formats.isEmpty()) {
            return ResolutionResult.Failure(
                error = PlaybackError(
                    code = "NO_FORMATS_FOUND",
                    message = "Player response contained no audio formats",
                    category = ErrorCategory.INVALID_RESPONSE,
                    isRecoverable = true
                ),
                strategyId = strategy.id,
                canFallback = true
            )
        }

        // 5. Calculate Expiry
        val now = clock()
        val expiresAtMs = if (parsed.expiresInSeconds != null) {
            now + (parsed.expiresInSeconds * 1000L)
        } else {
            now + 21_600_000L // 6 hours default
        }

        // 6. Intermediate Representation: Convert to ResolvedFormatCandidates
        val candidates = mutableListOf<com.aurora.engine.provider.ytmusic.model.ResolvedFormatCandidate>()
        var requiredTransformMissing = false

        for (format in parsed.formats) {
            val candidate = resolveCandidate(format, strategy, expiresAtMs)
            if (candidate != null) {
                candidates.add(candidate)
            } else if (format.requiresCipher || (format.directUrl != null && format.directUrl.contains("n="))) {
                requiredTransformMissing = true
            }
        }

        // Add SABR candidate if supported
        if (clientConfig.supportsSabr && parsed.serverEndpoint != null && parsed.formats.isNotEmpty()) {
            val primaryAudio = parsed.formats.first().audioFormat
            candidates.add(
                com.aurora.engine.provider.ytmusic.model.ResolvedFormatCandidate(
                    formatId = "sabr-${parsed.formats.first().itag}",
                    url = null,
                    mimeType = primaryAudio.mimeType,
                    codec = primaryAudio.codec,
                    bitrateKbps = primaryAudio.bitrateKbps,
                    sampleRateHz = primaryAudio.sampleRateHz,
                    channelCount = primaryAudio.channelCount,
                    durationMs = parsed.formats.first().approxDurationMs,
                    expiresAtMs = expiresAtMs,
                    transportHints = com.aurora.engine.provider.ytmusic.model.TransportHints(
                        isProgressiveCapable = false,
                        isSabrCapable = true,
                        serverEndpoint = parsed.serverEndpoint,
                        clientContextJson = contextObj.toString(),
                        ustreamerConfig = parsed.ustreamerConfig
                    ),
                    strategyId = strategy.id,
                    audioFormat = primaryAudio
                )
            )
        }

        if (candidates.isEmpty()) {
            if (requiredTransformMissing) {
                return ResolutionResult.Failure(
                    error = PlaybackError(
                        code = "TRANSFORMATION_REQUIRED",
                        message = "Strategy ${strategy.id} requires stream transformation but active transform provider (${transformProvider.name}) is unavailable or failed",
                        category = ErrorCategory.TRANSFORMATION_REQUIRED,
                        isRecoverable = true
                    ),
                    strategyId = strategy.id,
                    canFallback = true
                )
            }

            return ResolutionResult.Failure(
                error = PlaybackError(
                    code = "NO_USABLE_FORMATS",
                    message = "No playable formats could be candidate-resolved for strategy ${strategy.id}",
                    category = ErrorCategory.INVALID_RESPONSE,
                    isRecoverable = true
                ),
                strategyId = strategy.id,
                canFallback = true
            )
        }

        // 7. Provider Candidate Ranking (separated from Transport selection)
        val rankedCandidates = rankCandidates(candidates, context)

        // 8. Convert Candidates to generic PlaybackSources
        val sources = rankedCandidates.flatMap { it.toPlaybackSources(track.id) }

        if (sources.isEmpty()) {
            return ResolutionResult.Failure(
                error = PlaybackError(
                    code = "SOURCE_CONVERSION_FAILED",
                    message = "Failed to convert format candidates to stream sources",
                    category = ErrorCategory.INVALID_RESPONSE,
                    isRecoverable = true
                ),
                strategyId = strategy.id,
                canFallback = true
            )
        }

        return ResolutionResult.Success(
            sources = sources,
            strategyId = strategy.id,
            expiresAtMs = expiresAtMs
        )
    }

    private suspend fun resolveCandidate(
        format: ParsedStreamFormat,
        strategy: YouTubePlaybackStrategy,
        expiresAtMs: Long
    ): com.aurora.engine.provider.ytmusic.model.ResolvedFormatCandidate? {
        val clientConfig = strategy.clientConfig

        // Case A: Direct URL available
        if (format.directUrl != null) {
            val url = format.directUrl
            // Check if n-param transformation required
            val needsNTransform = url.contains("n=") && clientConfig.requiresCipher
            val finalUrl = if (needsNTransform) {
                if (!transformProvider.canTransform) {
                    return null // Cannot safely use untransformed URL
                }
                try {
                    val uri = URI(url)
                    val query = uri.rawQuery.orEmpty()
                    val nMatch = Regex("[?&]n=([^&#]+)").find(query)
                    if (nMatch != null) {
                        val rawN = nMatch.groupValues[1]
                        val transformedN = transformProvider.transformN(rawN)
                        url.replace("n=$rawN", "n=$transformedN")
                    } else {
                        url
                    }
                } catch (_: Throwable) {
                    return null
                }
            } else {
                url
            }

            return com.aurora.engine.provider.ytmusic.model.ResolvedFormatCandidate(
                formatId = format.itag.toString(),
                url = finalUrl,
                mimeType = format.audioFormat.mimeType,
                codec = format.audioFormat.codec,
                bitrateKbps = format.audioFormat.bitrateKbps,
                sampleRateHz = format.audioFormat.sampleRateHz,
                channelCount = format.audioFormat.channelCount,
                durationMs = format.approxDurationMs,
                expiresAtMs = expiresAtMs,
                requiresNTransform = needsNTransform,
                transportHints = com.aurora.engine.provider.ytmusic.model.TransportHints(
                    isProgressiveCapable = true,
                    headers = mapOf("User-Agent" to clientConfig.userAgent)
                ),
                strategyId = strategy.id,
                rawMetadata = format.rawMetadata,
                audioFormat = format.audioFormat
            )
        }

        // Case B: Cipher signature present
        if (format.requiresCipher && format.cipherBaseUrl != null && format.cipherSignature != null) {
            if (!transformProvider.canTransform) {
                return null // Explicitly refuse to return fake usable candidate
            }

            return try {
                val decipheredSig = transformProvider.decipherSignature(format.cipherSignature)
                val sigParam = format.cipherSignatureParam ?: "sig"
                val separator = if (format.cipherBaseUrl.contains("?")) "&" else "?"
                var signedUrl = "${format.cipherBaseUrl}$separator$sigParam=$decipheredSig"

                val uri = URI(signedUrl)
                val query = uri.rawQuery.orEmpty()
                val nMatch = Regex("[?&]n=([^&#]+)").find(query)
                var nTransformed = false
                if (nMatch != null && strategy.clientConfig.requiresCipher) {
                    val rawN = nMatch.groupValues[1]
                    val transformedN = transformProvider.transformN(rawN)
                    signedUrl = signedUrl.replace("n=$rawN", "n=$transformedN")
                    nTransformed = true
                }

                com.aurora.engine.provider.ytmusic.model.ResolvedFormatCandidate(
                    formatId = format.itag.toString(),
                    url = signedUrl,
                    mimeType = format.audioFormat.mimeType,
                    codec = format.audioFormat.codec,
                    bitrateKbps = format.audioFormat.bitrateKbps,
                    sampleRateHz = format.audioFormat.sampleRateHz,
                    channelCount = format.audioFormat.channelCount,
                    durationMs = format.approxDurationMs,
                    expiresAtMs = expiresAtMs,
                    cipherInfo = com.aurora.engine.provider.ytmusic.model.CipherInfo(
                        encryptedSignature = format.cipherSignature,
                        signatureParam = sigParam,
                        baseUrl = format.cipherBaseUrl,
                        isDeciphered = true
                    ),
                    requiresNTransform = nTransformed,
                    transportHints = com.aurora.engine.provider.ytmusic.model.TransportHints(
                        isProgressiveCapable = true,
                        headers = mapOf("User-Agent" to clientConfig.userAgent)
                    ),
                    strategyId = strategy.id,
                    rawMetadata = format.rawMetadata,
                    audioFormat = format.audioFormat
                )
            } catch (_: Throwable) {
                null
            }
        }

        return null
    }

    private fun rankCandidates(
        candidates: List<com.aurora.engine.provider.ytmusic.model.ResolvedFormatCandidate>,
        context: ResolutionContext
    ): List<com.aurora.engine.provider.ytmusic.model.ResolvedFormatCandidate> {
        val preferredCodecs = context.preferredCodecs
        val targetQuality = context.targetQuality

        return candidates.sortedWith { a, b ->
            // 1. Codec preference (index in preferredCodecs, lower is better)
            val codecIndexA = preferredCodecs.indexOf(a.codec).let { if (it == -1) 999 else it }
            val codecIndexB = preferredCodecs.indexOf(b.codec).let { if (it == -1) 999 else it }
            if (codecIndexA != codecIndexB) {
                return@sortedWith codecIndexA.compareTo(codecIndexB)
            }

            // 2. Bitrate closeness to target quality profile
            val targetRange = targetQuality.targetBitrateRangeKbps
            val bitrateA = a.bitrateKbps ?: 128
            val bitrateB = b.bitrateKbps ?: 128

            val inRangeA = bitrateA in targetRange
            val inRangeB = bitrateB in targetRange
            if (inRangeA && !inRangeB) return@sortedWith -1
            if (!inRangeA && inRangeB) return@sortedWith 1

            // 3. Higher bitrate preferred within codec
            bitrateB.compareTo(bitrateA)
        }
    }

    private fun classifyFailure(error: PlaybackError): FailureType {
        return when (error.category) {
            ErrorCategory.AUTHENTICATION_REQUIRED -> FailureType.AUTHENTICATION_REQUIRED
            ErrorCategory.CONTENT_RESTRICTION -> FailureType.CONTENT_RESTRICTION
            ErrorCategory.UNPLAYABLE -> FailureType.UNPLAYABLE
            ErrorCategory.NETWORK, ErrorCategory.NETWORK_FAILURE -> FailureType.NETWORK_FAILURE
            ErrorCategory.TIMEOUT -> FailureType.TIMEOUT
            ErrorCategory.BOT_DETECTION, ErrorCategory.PROVIDER_BOT_DETECTION -> FailureType.BOT_DETECTION
            ErrorCategory.PROVIDER_REJECTION -> FailureType.PROVIDER_REJECTION
            ErrorCategory.RATE_LIMITED -> FailureType.RATE_LIMITED
            ErrorCategory.TRANSFORMATION_REQUIRED -> FailureType.TRANSFORMATION_REQUIRED
            ErrorCategory.TOKEN_FAILURE -> FailureType.TOKEN_FAILURE
            ErrorCategory.INVALID_RESPONSE -> FailureType.INVALID_RESPONSE
            ErrorCategory.HTTP_SERVER, ErrorCategory.HTTP_CLIENT -> {
                when (error.httpStatusCode) {
                    403 -> FailureType.PROVIDER_REJECTION
                    429 -> FailureType.RATE_LIMITED
                    else -> FailureType.INVALID_RESPONSE
                }
            }
            else -> FailureType.INVALID_RESPONSE
        }
    }

    private fun generateCpn(): String {
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"
        val sb = StringBuilder(16)
        for (i in 0 until 16) {
            sb.append(chars[secureRandom.nextInt(chars.length)])
        }
        return sb.toString()
    }

    // ==========================================
    // Playback Recovery Integration
    // ==========================================

    fun checkpointPlayback(
        mediaId: String,
        positionMs: Long,
        byteOffset: Long,
        durationMs: Long,
        currentTransport: com.aurora.engine.provider.ytmusic.transport.TransportType,
        currentClientName: String
    ) {
        recoveryManager.checkpoint(
            mediaId = mediaId,
            positionMs = positionMs,
            byteOffset = byteOffset,
            durationMs = durationMs,
            currentTransport = currentTransport,
            currentClientName = currentClientName
        )
    }

    fun handlePlaybackError(
        mediaId: String,
        errorType: PlaybackErrorType,
        currentClientName: String,
        currentTransport: com.aurora.engine.provider.ytmusic.transport.TransportType,
        availableClients: Int,
        currentClientIndex: Int
    ) = recoveryManager.onError(
        mediaId = mediaId,
        errorType = errorType,
        currentClientName = currentClientName,
        currentTransport = currentTransport,
        availableClients = availableClients,
        currentClientIndex = currentClientIndex
    )

    // ==========================================
    // CatalogProvider Implementation (Fallback-Capable)
    // ==========================================

    private suspend fun executeMetadataRequest(
        endpoint: String,
        buildPayload: (InnerTubeClientConfig) -> kotlinx.serialization.json.JsonObject
    ): com.aurora.engine.provider.ytmusic.session.InnerTubeResponse? {
        val ladder = streamResolver?.metadataLadder
        if (ladder != null) {
            val candidates = ladder.availableClients()
            for (client in candidates) {
                val config = client.toInnerTubeClientConfig()
                val payload = buildPayload(config)
                val result = session.postJson(endpoint, payload, config)
                val response = result.getOrNull()
                if (response != null && response.isSuccess) {
                    ladder.recordSuccess(client.clientName)
                    return response
                } else {
                    ladder.recordFailure(client.clientName)
                }
            }
        }

        // Default fallback to WEB_REMIX if ladder is not provided or exhausted
        val fallbackConfig = InnerTubeClientConfig.WEB_REMIX
        val payload = buildPayload(fallbackConfig)
        return session.postJson(endpoint, payload, fallbackConfig).getOrNull()?.takeIf { it.isSuccess }
    }

    override suspend fun search(query: String, filter: String?, pageToken: String?): List<Track> {
        val response = executeMetadataRequest("/youtubei/v1/search") { clientConfig ->
            val contextObj = session.buildContextPayload(clientConfig)
            buildJsonObject {
                put("context", contextObj)
                put("query", query)
                if (filter != null) {
                    put("params", filter)
                }
            }
        } ?: return emptyList()

        return CatalogResponseParser.parseSearchTracks(response.body)
    }

    override suspend fun getTrack(trackId: String): Track? {
        val radioTracks = getNextRadioTracks(trackId)
        return radioTracks.firstOrNull { it.id == trackId } ?: radioTracks.firstOrNull()
    }

    override suspend fun getNextRadioTracks(trackId: String): List<Track> {
        val response = executeMetadataRequest("/youtubei/v1/next") { clientConfig ->
            val contextObj = session.buildContextPayload(clientConfig)
            buildJsonObject {
                put("context", contextObj)
                put("videoId", trackId)
                put("isAudioOnly", true)
            }
        } ?: return emptyList()

        return CatalogResponseParser.parseWatchNextQueue(response.body)
    }

    suspend fun getArtistDetails(artistId: String): ArtistDetails? {
        val response = executeMetadataRequest("/youtubei/v1/browse") { clientConfig ->
            val contextObj = session.buildContextPayload(clientConfig)
            buildJsonObject {
                put("context", contextObj)
                put("browseId", artistId)
            }
        } ?: return null

        return CatalogResponseParser.parseBrowseArtist(response.body, artistId)
    }

    suspend fun getAlbumDetails(albumId: String): AlbumDetails? {
        val response = executeMetadataRequest("/youtubei/v1/browse") { clientConfig ->
            val contextObj = session.buildContextPayload(clientConfig)
            buildJsonObject {
                put("context", contextObj)
                put("browseId", albumId)
            }
        } ?: return null

        return CatalogResponseParser.parseBrowseAlbum(response.body, albumId)
    }

    suspend fun getPlaylistDetails(playlistId: String): PlaylistDetails? {
        val browseId = if (playlistId.startsWith("VL")) playlistId else "VL$playlistId"
        val response = executeMetadataRequest("/youtubei/v1/browse") { clientConfig ->
            val contextObj = session.buildContextPayload(clientConfig)
            buildJsonObject {
                put("context", contextObj)
                put("browseId", browseId)
            }
        } ?: return null

        return CatalogResponseParser.parseBrowsePlaylist(response.body, playlistId)
    }
}
