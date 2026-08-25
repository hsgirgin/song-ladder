package com.songladder.android.data.spotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyPlaylistJsonImporterTest {
    private val importer = SpotifyPlaylistJsonImporter()

    @Test
    fun `parses a raw Spotify API items dump`() {
        val preview = importer.parse(
            """
            {
              "items": [
                {
                  "is_local": false,
                  "track": {
                    "id": "abc123",
                    "name": "Midnight City",
                    "album": { "name": "Hurry Up, We're Dreaming", "images": [{ "url": "https://img/1.jpg" }] },
                    "artists": [{ "name": "M83" }]
                  }
                },
                {
                  "is_local": true,
                  "track": { "id": "local1", "name": "Local Track", "album": { "name": "" }, "artists": [] }
                }
              ]
            }
            """.trimIndent()
        ).getOrThrow()

        assertEquals(1, preview.importableTracks.size)
        assertEquals("Midnight City", preview.importableTracks.single().title)
        assertEquals("M83", preview.importableTracks.single().artist)
        assertEquals("https://img/1.jpg", preview.importableTracks.single().artworkUrl)
    }

    @Test
    fun `parses a simple flat array of track objects`() {
        val preview = importer.parse(
            """
            [
              { "title": "Dreams", "artist": "Fleetwood Mac", "album": "Rumours" },
              { "title": "Genesis", "artist": "" }
            ]
            """.trimIndent()
        ).getOrThrow()

        assertEquals(1, preview.importableTracks.size)
        assertEquals("Dreams", preview.importableTracks.single().title)
        assertEquals("Rumours", preview.importableTracks.single().album)
        assertEquals(1, preview.ambiguousTracks.size)
    }

    @Test
    fun `parses a spotify track uri when no id field is present`() {
        val preview = importer.parse(
            """[{ "title": "Genesis", "artist": "Grimes", "uri": "spotify:track:xyz789" }]"""
        ).getOrThrow()

        assertEquals("xyz789", preview.importableTracks.single().externalId)
    }

    @Test
    fun `fails gracefully on invalid json`() {
        val result = importer.parse("not json")
        assertTrue(result.isFailure)
    }

    @Test
    fun `fails when no tracks are found`() {
        val result = importer.parse("""{"items":[]}""")
        assertTrue(result.isFailure)
    }
}
