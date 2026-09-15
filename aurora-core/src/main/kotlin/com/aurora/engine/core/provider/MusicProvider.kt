package com.aurora.engine.core.provider

import com.aurora.engine.core.model.Track

interface PlaybackProvider {
    val providerId: String

    suspend fun resolvePlayback(context: ResolutionContext): ResolutionResult
}

interface CatalogProvider {
    val providerId: String

    suspend fun search(query: String, filter: String? = null, pageToken: String? = null): List<Track>

    suspend fun getTrack(trackId: String): Track?

    suspend fun getNextRadioTracks(trackId: String): List<Track>
}

interface MusicProvider : CatalogProvider, PlaybackProvider
