package com.aurora.engine.provider.ytmusic.parser

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class CatalogResponseParserTest {

    @Test
    @DisplayName("Parses YouTube Music search results containing tracks, artists, and thumbnails")
    fun testParseSearchTracks() {
        val searchJson = """
        {
            "contents": {
                "tabbedSearchResultsRenderer": {
                    "tabs": [
                        {
                            "tabRenderer": {
                                "content": {
                                    "sectionListRenderer": {
                                        "contents": [
                                            {
                                                "musicShelfRenderer": {
                                                    "contents": [
                                                        {
                                                            "musicResponsiveListItemRenderer": {
                                                                "flexColumns": [
                                                                    {
                                                                        "musicResponsiveListItemFlexColumnRenderer": {
                                                                            "text": {
                                                                                "runs": [
                                                                                    {
                                                                                        "text": "Starboy",
                                                                                        "navigationEndpoint": {
                                                                                            "watchEndpoint": {
                                                                                                "videoId": "d38H_rJ0Zek"
                                                                                            }
                                                                                        }
                                                                                    }
                                                                                ]
                                                                            }
                                                                        }
                                                                    },
                                                                    {
                                                                        "musicResponsiveListItemFlexColumnRenderer": {
                                                                            "text": {
                                                                                "runs": [
                                                                                    {
                                                                                        "text": "The Weeknd",
                                                                                        "navigationEndpoint": {
                                                                                            "browseEndpoint": {
                                                                                                "browseId": "UC0WP5P-ufpRfjbNrmOWwLBQ"
                                                                                            }
                                                                                        }
                                                                                    },
                                                                                    { "text": " • " },
                                                                                    {
                                                                                        "text": "Starboy (Album)",
                                                                                        "navigationEndpoint": {
                                                                                            "browseEndpoint": {
                                                                                                "browseId": "MPREb_album123"
                                                                                            }
                                                                                        }
                                                                                    },
                                                                                    { "text": " • " },
                                                                                    { "text": "3:50" }
                                                                                ]
                                                                            }
                                                                        }
                                                                    }
                                                                ],
                                                                "thumbnail": {
                                                                    "musicThumbnailRenderer": {
                                                                        "thumbnail": {
                                                                            "thumbnails": [
                                                                                {
                                                                                    "url": "https://lh3.googleusercontent.com/art123=w120-h120",
                                                                                    "width": 120,
                                                                                    "height": 120
                                                                                }
                                                                            ]
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    ]
                                                }
                                            }
                                        ]
                                    }
                                }
                            }
                        }
                    ]
                }
            }
        }
        """.trimIndent()

        val tracks = CatalogResponseParser.parseSearchTracks(searchJson)

        assertThat(tracks).hasSize(1)
        val track = tracks.first()
        assertThat(track.id).isEqualTo("d38H_rJ0Zek")
        assertThat(track.title).isEqualTo("Starboy")
        assertThat(track.artists).hasSize(1)
        assertThat(track.artists.first().name).isEqualTo("The Weeknd")
        assertThat(track.artists.first().id).isEqualTo("UC0WP5P-ufpRfjbNrmOWwLBQ")
        assertThat(track.album?.title).isEqualTo("Starboy (Album)")
        assertThat(track.durationMs).isEqualTo(230_000L) // 3 min 50 sec = 230 sec = 230,000 ms
        assertThat(track.thumbnails).hasSize(1)
        assertThat(track.thumbnails.first().url).contains("art123")
    }

    @Test
    @DisplayName("Handles malformed search response without crashing")
    fun testMalformedSearch() {
        val tracks = CatalogResponseParser.parseSearchTracks("{ invalid json }")
        assertThat(tracks).isEmpty()
    }
}
