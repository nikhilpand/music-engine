package com.aurora.engine.provider.ytmusic.parser

import com.aurora.engine.core.model.AlbumRef
import com.aurora.engine.core.model.ArtistRef
import com.aurora.engine.core.model.Thumbnail
import com.aurora.engine.core.model.Track
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class ArtistDetails(
    val id: String,
    val name: String,
    val description: String? = null,
    val thumbnails: List<Thumbnail> = emptyList(),
    val topTracks: List<Track> = emptyList()
)

data class AlbumDetails(
    val id: String,
    val title: String,
    val artists: List<ArtistRef>,
    val year: String? = null,
    val thumbnails: List<Thumbnail> = emptyList(),
    val tracks: List<Track> = emptyList()
)

data class PlaylistDetails(
    val id: String,
    val title: String,
    val author: String? = null,
    val trackCount: Int = 0,
    val thumbnails: List<Thumbnail> = emptyList(),
    val tracks: List<Track> = emptyList()
)

object CatalogResponseParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parseSearchTracks(rawJson: String): List<Track> {
        val root = try {
            json.parseToJsonElement(rawJson).jsonObject
        } catch (_: Throwable) {
            return emptyList()
        }

        val tracks = mutableListOf<Track>()

        // Search polymorphic traversal: look for musicResponsiveListItemRenderer across shelves
        findRenderers(root, "musicResponsiveListItemRenderer") { renderer ->
            parseTrackFromResponsiveItem(renderer)?.let { tracks.add(it) }
        }

        return tracks
    }

    fun parseWatchNextQueue(rawJson: String): List<Track> {
        val root = try {
            json.parseToJsonElement(rawJson).jsonObject
        } catch (_: Throwable) {
            return emptyList()
        }

        val tracks = mutableListOf<Track>()

        findRenderers(root, "playlistPanelVideoRenderer") { renderer ->
            parseTrackFromPlaylistPanelVideo(renderer)?.let { tracks.add(it) }
        }

        return tracks
    }

    fun parseBrowseArtist(rawJson: String, artistId: String): ArtistDetails? {
        val root = try {
            json.parseToJsonElement(rawJson).jsonObject
        } catch (_: Throwable) {
            return null
        }

        val header = root["header"]?.jsonObject
        val name = header?.get("musicImmersiveHeaderRenderer")?.jsonObject
            ?.get("title")?.jsonObject?.get("runs")?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("text")?.jsonPrimitive?.content ?: "Unknown Artist"

        val tracks = mutableListOf<Track>()
        findRenderers(root, "musicResponsiveListItemRenderer") { renderer ->
            parseTrackFromResponsiveItem(renderer)?.let { tracks.add(it) }
        }

        return ArtistDetails(
            id = artistId,
            name = name,
            topTracks = tracks
        )
    }

    fun parseBrowseAlbum(rawJson: String, albumId: String): AlbumDetails? {
        val root = try {
            json.parseToJsonElement(rawJson).jsonObject
        } catch (_: Throwable) {
            return null
        }

        val header = root["header"]?.jsonObject?.get("musicDetailHeaderRenderer")?.jsonObject
        val title = header?.get("title")?.jsonObject?.get("runs")?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("text")?.jsonPrimitive?.content ?: "Unknown Album"

        val tracks = mutableListOf<Track>()
        findRenderers(root, "musicResponsiveListItemRenderer") { renderer ->
            parseTrackFromResponsiveItem(renderer)?.let { tracks.add(it) }
        }

        return AlbumDetails(
            id = albumId,
            title = title,
            artists = emptyList(),
            tracks = tracks
        )
    }

    fun parseBrowsePlaylist(rawJson: String, playlistId: String): PlaylistDetails? {
        val root = try {
            json.parseToJsonElement(rawJson).jsonObject
        } catch (_: Throwable) {
            return null
        }

        val header = root["header"]?.jsonObject?.get("musicDetailHeaderRenderer")?.jsonObject
        val title = header?.get("title")?.jsonObject?.get("runs")?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("text")?.jsonPrimitive?.content ?: "Playlist"

        val tracks = mutableListOf<Track>()
        findRenderers(root, "musicResponsiveListItemRenderer") { renderer ->
            parseTrackFromResponsiveItem(renderer)?.let { tracks.add(it) }
        }

        return PlaylistDetails(
            id = playlistId,
            title = title,
            trackCount = tracks.size,
            tracks = tracks
        )
    }

    fun parseTrackFromResponsiveItem(item: JsonObject): Track? {
        val flexColumns = item["flexColumns"]?.jsonArray ?: return null
        if (flexColumns.isEmpty()) return null

        // Column 0: Title & VideoId
        val col0Runs = flexColumns.getOrNull(0)?.jsonObject
            ?.get("musicResponsiveListItemFlexColumnRenderer")?.jsonObject
            ?.get("text")?.jsonObject?.get("runs")?.jsonArray.orEmpty()

        val titleRun = col0Runs.firstOrNull()?.jsonObject ?: return null
        val title = titleRun["text"]?.jsonPrimitive?.content ?: return null

        val navEndpoint = titleRun["navigationEndpoint"]?.jsonObject
            ?: item["navigationEndpoint"]?.jsonObject
            ?: item["playlistItemData"]?.jsonObject

        val videoId = navEndpoint?.get("watchEndpoint")?.jsonObject?.get("videoId")?.jsonPrimitive?.content
            ?: navEndpoint?.get("videoId")?.jsonPrimitive?.content
            ?: return null

        // Column 1: Artist, Album, Duration runs
        val col1Runs = flexColumns.getOrNull(1)?.jsonObject
            ?.get("musicResponsiveListItemFlexColumnRenderer")?.jsonObject
            ?.get("text")?.jsonObject?.get("runs")?.jsonArray.orEmpty()

        val artists = mutableListOf<ArtistRef>()
        var albumRef: AlbumRef? = null
        var durationMs = 0L

        for (runElem in col1Runs) {
            if (runElem !is JsonObject) continue
            val text = runElem["text"]?.jsonPrimitive?.content.orEmpty()
            val browseEndpoint = runElem["navigationEndpoint"]?.jsonObject?.get("browseEndpoint")?.jsonObject
            val browseId = browseEndpoint?.get("browseId")?.jsonPrimitive?.content.orEmpty()

            if (browseId.startsWith("UC") || browseId.contains("artist")) {
                artists.add(ArtistRef(id = browseId, name = text))
            } else if (browseId.startsWith("MPREb_") || browseId.contains("release_detail")) {
                albumRef = AlbumRef(id = browseId, title = text)
            } else if (text.matches(Regex("\\d+:\\d+(:\\d+)?"))) {
                durationMs = parseDurationMs(text)
            }
        }

        if (artists.isEmpty()) {
            val fallbackArtist = col1Runs.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content ?: "Unknown"
            artists.add(ArtistRef(id = "artist_unknown", name = fallbackArtist))
        }

        // Thumbnails
        val thumbnails = parseThumbnails(item["thumbnail"]?.jsonObject?.get("musicThumbnailRenderer")?.jsonObject)

        return Track(
            id = videoId,
            providerId = "ytmusic",
            title = title,
            artists = artists,
            album = albumRef,
            durationMs = durationMs,
            thumbnails = thumbnails
        )
    }

    private fun parseTrackFromPlaylistPanelVideo(item: JsonObject): Track? {
        val videoId = item["videoId"]?.jsonPrimitive?.content ?: return null
        val title = item["title"]?.jsonObject?.get("runs")?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("text")?.jsonPrimitive?.content ?: "Unknown"

        val bylineRuns = item["longBylineText"]?.jsonObject?.get("runs")?.jsonArray
            ?: item["shortBylineText"]?.jsonObject?.get("runs")?.jsonArray
            ?: JsonArray(emptyList())

        val artists = mutableListOf<ArtistRef>()
        for (run in bylineRuns) {
            val text = run.jsonObject["text"]?.jsonPrimitive?.content.orEmpty()
            if (text != " • " && text.isNotBlank()) {
                val browseId = run.jsonObject["navigationEndpoint"]?.jsonObject
                    ?.get("browseEndpoint")?.jsonObject?.get("browseId")?.jsonPrimitive?.content ?: "artist_unknown"
                artists.add(ArtistRef(id = browseId, name = text))
            }
        }

        if (artists.isEmpty()) {
            artists.add(ArtistRef(id = "artist_unknown", name = "Unknown Artist"))
        }

        val lengthText = item["lengthText"]?.jsonObject?.get("runs")?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("text")?.jsonPrimitive?.content.orEmpty()
        val durationMs = parseDurationMs(lengthText)

        val thumbnails = parseThumbnails(item["thumbnail"]?.jsonObject)

        return Track(
            id = videoId,
            providerId = "ytmusic",
            title = title,
            artists = artists,
            durationMs = durationMs,
            thumbnails = thumbnails
        )
    }

    private fun parseThumbnails(thumbnailObj: JsonObject?): List<Thumbnail> {
        val thumbnailsArray = thumbnailObj?.get("thumbnail")?.jsonObject?.get("thumbnails")?.jsonArray
            ?: thumbnailObj?.get("thumbnails")?.jsonArray
            ?: return emptyList()

        return thumbnailsArray.mapNotNull { elem ->
            if (elem !is JsonObject) return@mapNotNull null
            val url = elem["url"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val width = elem["width"]?.jsonPrimitive?.content?.toIntOrNull()
            val height = elem["height"]?.jsonPrimitive?.content?.toIntOrNull()
            Thumbnail(url = url, width = width, height = height)
        }
    }

    private fun parseDurationMs(durationStr: String): Long {
        if (durationStr.isBlank()) return 0L
        val parts = durationStr.split(":")
        return try {
            when (parts.size) {
                2 -> {
                    val m = parts[0].toLong()
                    val s = parts[1].toLong()
                    (m * 60 + s) * 1000L
                }
                3 -> {
                    val h = parts[0].toLong()
                    val m = parts[1].toLong()
                    val s = parts[2].toLong()
                    (h * 3600 + m * 60 + s) * 1000L
                }
                else -> 0L
            }
        } catch (_: Throwable) {
            0L
        }
    }

    private fun findRenderers(element: JsonElement, rendererKey: String, onFound: (JsonObject) -> Unit) {
        when (element) {
            is JsonObject -> {
                for ((key, value) in element) {
                    if (key == rendererKey && value is JsonObject) {
                        onFound(value)
                    } else {
                        findRenderers(value, rendererKey, onFound)
                    }
                }
            }
            is JsonArray -> {
                for (item in element) {
                    findRenderers(item, rendererKey, onFound)
                }
            }
            else -> {}
        }
    }
}
