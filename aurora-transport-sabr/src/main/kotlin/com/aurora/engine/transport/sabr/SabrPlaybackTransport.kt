package com.aurora.engine.transport.sabr

import com.aurora.engine.core.model.PlaybackSource
import com.aurora.engine.core.transport.PlaybackSession
import com.aurora.engine.core.transport.PlaybackTransport
import com.aurora.engine.transport.sabr.config.SabrTransportConfig

/**
 * SABR [PlaybackTransport] implementation.
 *
 * Handles [PlaybackSource.Sabr] sources by creating [SabrPlaybackSession] instances.
 *
 * This is the sole public entry point for the SABR transport module.
 * Registration with [com.aurora.engine.core.transport.PlaybackTransportRegistry]
 * is done at app startup by the integration layer.
 *
 * @param config Tunable SABR configuration. Uses production defaults if omitted.
 * @param httpClient HTTP client for making SABR network requests.
 * @param listener Optional listener for session-level events.
 */
class SabrPlaybackTransport(
    private val config: SabrTransportConfig = SabrTransportConfig(),
    private val httpClient: SabrHttpClient? = null,
    private val listener: SabrSessionListener? = null
) : PlaybackTransport {

    override val transportId: String = "aurora-transport-sabr"

    /**
     * Returns true only for [PlaybackSource.Sabr] sources.
     */
    override fun canHandle(source: PlaybackSource): Boolean {
        return source is PlaybackSource.Sabr
    }

    /**
     * Create a new [SabrPlaybackSession] for the given source.
     *
     * @throws IllegalArgumentException if the source is not [PlaybackSource.Sabr].
     */
    override suspend fun createSession(source: PlaybackSource): PlaybackSession {
        require(source is PlaybackSource.Sabr) {
            "SabrPlaybackTransport can only handle PlaybackSource.Sabr, got ${source::class.simpleName}"
        }

        return SabrPlaybackSession(
            source = source,
            config = config,
            listener = listener,
            httpClient = httpClient
        )
    }
}
