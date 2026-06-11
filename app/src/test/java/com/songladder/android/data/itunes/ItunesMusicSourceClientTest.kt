package com.songladder.android.data.itunes

import com.songladder.android.domain.model.MusicSourceType
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ItunesMusicSourceClientTest {
    private val client = ItunesMusicSourceClient(OkHttpClient())

    @Test
    fun `maps iTunes payload into track candidates`() {
        val results = client.parseSearchResponse(
            """
            {
              "resultCount": 1,
              "results": [
                {
                  "trackId": 12345,
                  "trackName": "Dreams",
                  "artistName": "Fleetwood Mac",
                  "collectionName": "Rumours",
                  "artworkUrl100": "https://is1-ssl.mzstatic.com/image/thumb/Music123/v4/example/100x100bb.jpg"
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals(1, results.size)
        assertEquals("12345", results.single().externalId)
        assertEquals("Dreams", results.single().title)
        assertEquals("Fleetwood Mac", results.single().artist)
        assertEquals("Rumours", results.single().album)
        assertEquals(MusicSourceType.ITUNES, results.single().sourceType)
    }

    @Test
    fun `returns empty results when payload contains no songs`() {
        val results = client.parseSearchResponse("""{"resultCount":0,"results":[]}""")
        assertTrue(results.isEmpty())
    }

    @Test
    fun `drops malformed track rows`() {
        val results = client.parseSearchResponse(
            """
            {
              "resultCount": 2,
              "results": [
                { "trackId": 999, "trackName": "", "artistName": "Artist" },
                { "trackId": 111, "trackName": "Song", "artistName": "Singer", "artworkUrl100": null }
              ]
            }
            """.trimIndent()
        )

        assertEquals(1, results.size)
        assertEquals("Song", results.single().title)
        assertNull(results.single().artworkUrl)
    }
}
