package com.aurora.engine.core.model

import kotlinx.serialization.Serializable

@Serializable
data class Track(
    val id: String,
    val providerId: String,
    val title: String,
    val artists: List<ArtistRef>,
    val album: AlbumRef? = null,
    val durationMs: Long,
    val thumbnails: List<Thumbnail> = emptyList(),
    val isLiveStream: Boolean = false,
    val isExplicit: Boolean = false,
    val extraMetadata: Map<String, String> = emptyMap()
) {
    init {
        require(id.isNotBlank()) { "Track id cannot be blank" }
        require(providerId.isNotBlank()) { "Provider id cannot be blank" }
        require(title.isNotBlank()) { "Track title cannot be blank" }
        require(durationMs >= 0) { "Track durationMs must be non-negative: $durationMs" }
    }

    val primaryArtist: ArtistRef?
        get() = artists.firstOrNull()
}

@Serializable
data class ArtistRef(
    val id: String,
    val name: String
) {
    init {
        require(id.isNotBlank()) { "Artist id cannot be blank" }
        require(name.isNotBlank()) { "Artist name cannot be blank" }
    }
}

@Serializable
data class AlbumRef(
    val id: String,
    val title: String
) {
    init {
        require(id.isNotBlank()) { "Album id cannot be blank" }
        require(title.isNotBlank()) { "Album title cannot be blank" }
    }
}

@Serializable
data class Thumbnail(
    val url: String,
    val width: Int? = null,
    val height: Int? = null
) {
    init {
        require(url.isNotBlank()) { "Thumbnail url cannot be blank" }
    }
}
