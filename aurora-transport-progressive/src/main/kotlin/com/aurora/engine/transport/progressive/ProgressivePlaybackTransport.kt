package com.aurora.engine.transport.progressive

import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.aurora.engine.core.model.PlaybackSource
import com.aurora.engine.core.transport.PlaybackSession
import com.aurora.engine.core.transport.PlaybackTransport
import com.aurora.engine.transport.progressive.datasource.AuroraHttpDataSourceFactory
import com.aurora.engine.transport.progressive.session.ProgressivePlaybackSession
import com.aurora.engine.transport.progressive.validation.UrlExpirationValidator
import okhttp3.OkHttpClient
import java.util.UUID

class ProgressivePlaybackTransport(
    private val okHttpClient: OkHttpClient = AuroraHttpDataSourceFactory.defaultOkHttpClient(),
    private val expirationValidator: UrlExpirationValidator = UrlExpirationValidator(),
    private val userAgent: String = "AuroraMusicEngine/1.2 (Linux; Android 14)",
    private val customDataSourceFactoryProvider: ((PlaybackSource.Progressive) -> DataSource.Factory)? = null
) : PlaybackTransport {

    override val transportId: String = "transport-progressive"

    override fun canHandle(source: PlaybackSource): Boolean {
        return source is PlaybackSource.Progressive
    }

    override suspend fun createSession(source: PlaybackSource): PlaybackSession {
        require(canHandle(source)) {
            "ProgressivePlaybackTransport cannot handle source of type ${source::class.simpleName}"
        }
        val progressiveSource = source as PlaybackSource.Progressive

        // Pre-flight check: treat expiresAtMs as metadata, reject if already expired
        val validationResult = expirationValidator.validate(progressiveSource)
        if (validationResult.isFailure) {
            throw validationResult.exceptionOrNull()
                ?: IllegalStateException("Playback source for track ${progressiveSource.trackId} has expired")
        }

        val effectiveUserAgent = progressiveSource.headers["User-Agent"]
            ?: progressiveSource.headers["user-agent"]
            ?: userAgent

        // Construct DataSource.Factory
        val dataSourceFactory = customDataSourceFactoryProvider?.invoke(progressiveSource)
            ?: AuroraHttpDataSourceFactory(
                baseClient = okHttpClient,
                userAgent = effectiveUserAgent,
                additionalHeaders = progressiveSource.headers
            )

        // Build MediaItem with custom metadata
        val mediaItem = MediaItem.Builder()
            .setUri(progressiveSource.url)
            .setMediaId(progressiveSource.trackId)
            .setCustomCacheKey(progressiveSource.customCacheKey)
            .build()

        // Build Media3 ProgressiveMediaSource
        val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(mediaItem)

        val sessionId = "progressive-${progressiveSource.trackId}-${UUID.randomUUID().toString().take(8)}"

        return ProgressivePlaybackSession(
            sessionId = sessionId,
            source = progressiveSource,
            mediaSource = mediaSource,
            dataSourceFactory = dataSourceFactory
        )
    }
}
