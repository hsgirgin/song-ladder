package com.songladder.android.data.itunes

import com.songladder.android.domain.model.TimeSource
import com.songladder.android.domain.repository.AlbumMetadataUnavailableException
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ItunesAlbumMetadataProviderTest {
    @Test
    fun `search releases maps a collection result into a candidate`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(albumSearchResponse()))
        // The artist-id search that follows the term search finds no match here -
        // no discography lookup should be attempted off the back of it.
        server.enqueue(MockResponse().setBody("""{"resultCount":0,"results":[]}"""))
        server.start()

        try {
            val provider = ItunesAlbumMetadataProvider(
                httpClient = OkHttpClient(),
                searchBaseUrl = server.url("/search")
            )

            val candidates = provider.searchReleases("Frank Ocean", "Blonde").getOrThrow()

            assertEquals(1, candidates.size)
            val candidate = candidates.single()
            assertEquals("123456", candidate.collectionId)
            assertEquals("Blonde", candidate.collectionName)
            assertEquals("Frank Ocean", candidate.artistName)
            assertEquals(17, candidate.trackCount)
            assertTrue(candidate.artworkUrl.orEmpty().contains("600x600"))
            assertTrue(server.takeRequest().path.orEmpty().startsWith("/search?"))
            assertEquals(2, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `search releases merges in the artist's discography when search omits the real album`() = runTest {
        val server = MockWebServer()
        // The term search only returns a decoy - a karaoke cover by a different
        // artist, and a genuine System Of A Down single - never the "Toxicity"
        // album itself, reproducing what iTunes' Search endpoint actually returns
        // for this query.
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "resultCount": 2,
                  "results": [
                    {"collectionId": 1, "collectionName": "Toxicity (Instrumental) - Single", "artistName": "Karaoke Band"},
                    {"collectionId": 2, "collectionName": "Protect The Land - Single", "artistName": "System Of A Down", "trackCount": 1}
                  ]
                }
                """.trimIndent()
            )
        )
        // The dedicated artist-id search resolves the artist correctly.
        server.enqueue(
            MockResponse().setBody(
                """{"resultCount": 1, "results": [{"wrapperType": "artist", "artistId": 462715, "artistName": "System Of A Down"}]}"""
            )
        )
        // Its discography lookup includes the real album.
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "resultCount": 1,
                  "results": [
                    {"collectionId": 3, "collectionName": "Toxicity", "artistName": "System Of A Down", "trackCount": 15}
                  ]
                }
                """.trimIndent()
            )
        )
        server.start()

        try {
            val provider = ItunesAlbumMetadataProvider(
                httpClient = OkHttpClient(),
                searchBaseUrl = server.url("/search"),
                lookupBaseUrl = server.url("/lookup")
            )

            val candidates = provider.searchReleases("System Of A Down", "Toxicity").getOrThrow()

            assertEquals(setOf("1", "2", "3"), candidates.map { it.collectionId }.toSet())
            val realAlbum = candidates.single { it.collectionId == "3" }
            assertEquals("Toxicity", realAlbum.collectionName)
            assertEquals(15, realAlbum.trackCount)
            assertEquals(3, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `search releases resolves the artist even when search's own results never surface it`() = runTest {
        val server = MockWebServer()
        // Reproduces "Muse Drones": every term-search result is a decoy artist whose
        // name merely contains "Muse", so none of them is a name match to fall back
        // from - the real band never appears in these results at all.
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "resultCount": 2,
                  "results": [
                    {"collectionId": 1, "collectionName": "Rain and Drones for Meditation", "artistName": "Mindful Muse", "trackCount": 14},
                    {"collectionId": 2, "collectionName": "I See Drones At Night - Single", "artistName": "Moon Muse", "trackCount": 1}
                  ]
                }
                """.trimIndent()
            )
        )
        server.enqueue(
            MockResponse().setBody(
                """{"resultCount": 1, "results": [{"wrapperType": "artist", "artistId": 1093360, "artistName": "Muse"}]}"""
            )
        )
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "resultCount": 1,
                  "results": [
                    {"collectionId": 3, "collectionName": "Drones", "artistName": "Muse", "trackCount": 13}
                  ]
                }
                """.trimIndent()
            )
        )
        server.start()

        try {
            val provider = ItunesAlbumMetadataProvider(
                httpClient = OkHttpClient(),
                searchBaseUrl = server.url("/search"),
                lookupBaseUrl = server.url("/lookup")
            )

            val candidates = provider.searchReleases("Muse", "Drones").getOrThrow()

            val realAlbum = candidates.singleOrNull { it.collectionId == "3" }
            assertEquals("Drones", realAlbum?.collectionName)
            assertEquals("Muse", realAlbum?.artistName)
            assertEquals(13, realAlbum?.trackCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `search releases drops results missing a title or artist`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "resultCount": 2,
                  "results": [
                    {"collectionId": 1, "collectionName": "", "artistName": "Frank Ocean"},
                    {"collectionId": 2, "collectionName": "Blonde", "artistName": "Frank Ocean"}
                  ]
                }
                """.trimIndent()
            )
        )
        server.enqueue(MockResponse().setBody("""{"resultCount":0,"results":[]}"""))
        server.start()

        try {
            val provider = ItunesAlbumMetadataProvider(
                httpClient = OkHttpClient(),
                searchBaseUrl = server.url("/search")
            )

            val candidates = provider.searchReleases("Frank Ocean", "Blonde").getOrThrow()

            assertEquals(1, candidates.size)
            assertEquals("2", candidates.single().collectionId)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `lookup release splits the collection header from its tracks`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(collectionLookupResponse()))
        server.start()

        try {
            val provider = ItunesAlbumMetadataProvider(
                httpClient = OkHttpClient(),
                lookupBaseUrl = server.url("/lookup")
            )

            val lookup = provider.lookupRelease("123456").getOrThrow()

            assertEquals("Blonde", lookup.collectionName)
            assertEquals("Frank Ocean", lookup.artistName)
            assertEquals(2, lookup.trackCount)
            assertEquals(2, lookup.tracks.size)
            assertEquals("Nikes", lookup.tracks[0].title)
            assertEquals(1, lookup.tracks[0].trackNumber)
            assertEquals("Ivy", lookup.tracks[1].title)
            assertEquals(2, lookup.tracks[1].trackNumber)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `lookup release caches within the ttl and does not re-request`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(collectionLookupResponse()))
        server.start()

        try {
            val provider = ItunesAlbumMetadataProvider(
                httpClient = OkHttpClient(),
                lookupBaseUrl = server.url("/lookup")
            )

            val first = provider.lookupRelease("123456").getOrThrow()
            val second = provider.lookupRelease("123456").getOrThrow()

            assertEquals(first, second)
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `lookup release ignores the cache and re-requests when forceRefresh is true`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(collectionLookupResponse()))
        server.enqueue(MockResponse().setBody(collectionLookupResponse()))
        server.start()

        try {
            val provider = ItunesAlbumMetadataProvider(
                httpClient = OkHttpClient(),
                lookupBaseUrl = server.url("/lookup")
            )

            provider.lookupRelease("123456").getOrThrow()
            provider.lookupRelease("123456", forceRefresh = true).getOrThrow()

            assertEquals(2, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `lookup release re-requests once the cache ttl has expired`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(collectionLookupResponse()))
        server.enqueue(MockResponse().setBody(collectionLookupResponse()))
        server.start()

        try {
            var clockMillis = 0L
            val provider = ItunesAlbumMetadataProvider(
                httpClient = OkHttpClient(),
                lookupBaseUrl = server.url("/lookup"),
                timeSource = TimeSource { clockMillis }
            )

            provider.lookupRelease("123456").getOrThrow()
            assertEquals(1, server.requestCount)

            val cacheTtlMillis = 30 * 60 * 1000L // must match ItunesAlbumMetadataProvider's private CACHE_TTL_MILLIS
            clockMillis += cacheTtlMillis - 1
            provider.lookupRelease("123456").getOrThrow()
            assertEquals(1, server.requestCount) // still within the ttl

            clockMillis += 2 // now past the ttl
            provider.lookupRelease("123456").getOrThrow()
            assertEquals(2, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `lookup releases groups an interleaved batch response by collection id`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(batchCollectionLookupResponse()))
        server.start()

        try {
            val provider = ItunesAlbumMetadataProvider(
                httpClient = OkHttpClient(),
                lookupBaseUrl = server.url("/lookup")
            )

            val lookups = provider.lookupReleases(listOf("123456", "789")).getOrThrow()

            assertEquals(1, server.requestCount)
            assertEquals(2, lookups.size)
            val blonde = lookups.getValue("123456")
            assertEquals("Blonde", blonde.collectionName)
            assertEquals(2, blonde.tracks.size)
            assertEquals("Nikes", blonde.tracks[0].title)
            val currents = lookups.getValue("789")
            assertEquals("Currents", currents.collectionName)
            assertEquals(1, currents.tracks.size)
            assertEquals("Let It Happen", currents.tracks[0].title)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `lookup releases serves cached ids and only fetches the rest`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(collectionLookupResponse()))
        server.enqueue(MockResponse().setBody(singleCollectionLookupResponse(789, "Currents", "Tame Impala")))
        server.start()

        try {
            val provider = ItunesAlbumMetadataProvider(
                httpClient = OkHttpClient(),
                lookupBaseUrl = server.url("/lookup")
            )

            provider.lookupRelease("123456").getOrThrow()
            val lookups = provider.lookupReleases(listOf("123456", "789")).getOrThrow()

            assertEquals(2, server.requestCount)
            server.takeRequest() // the initial lookupRelease("123456") request
            val batchRequest = server.takeRequest()
            assertTrue(batchRequest.path.orEmpty().contains("id=789"))
            assertEquals("Blonde", lookups.getValue("123456").collectionName)
            assertEquals("Currents", lookups.getValue("789").collectionName)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `a non-2xx response surfaces as unavailable rather than no-match`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(403))
        server.start()

        try {
            val provider = ItunesAlbumMetadataProvider(
                httpClient = OkHttpClient(),
                searchBaseUrl = server.url("/search")
            )

            val result = provider.searchReleases("Frank Ocean", "Blonde")

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is AlbumMetadataUnavailableException)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `a lookup that returns no collection header surfaces as unavailable`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"resultCount":0,"results":[]}"""))
        server.start()

        try {
            val provider = ItunesAlbumMetadataProvider(
                httpClient = OkHttpClient(),
                lookupBaseUrl = server.url("/lookup")
            )

            val result = provider.lookupRelease("123456")

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is AlbumMetadataUnavailableException)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `a blank album title is rejected without hitting the network`() = runTest {
        val provider = ItunesAlbumMetadataProvider(httpClient = OkHttpClient())

        val result = provider.searchReleases("Frank Ocean", "  ")

        assertTrue(result.isFailure)
        assertNull(result.exceptionOrNull() as? AlbumMetadataUnavailableException)
    }
}

private fun albumSearchResponse() = """
    {
      "resultCount": 1,
      "results": [{
        "collectionId": 123456,
        "collectionName": "Blonde",
        "artistName": "Frank Ocean",
        "artworkUrl100": "https://is1-ssl.mzstatic.com/image/thumb/Music/v4/example/100x100bb.jpg",
        "trackCount": 17
      }]
    }
""".trimIndent()

private fun collectionLookupResponse() = """
    {
      "resultCount": 3,
      "results": [
        {
          "collectionId": 123456,
          "collectionName": "Blonde",
          "artistName": "Frank Ocean",
          "trackCount": 2
        },
        {
          "trackId": 1,
          "collectionId": 123456,
          "trackName": "Nikes",
          "artistName": "Frank Ocean",
          "collectionName": "Blonde",
          "trackNumber": 1
        },
        {
          "trackId": 2,
          "collectionId": 123456,
          "trackName": "Ivy",
          "artistName": "Frank Ocean",
          "collectionName": "Blonde",
          "trackNumber": 2
        }
      ]
    }
""".trimIndent()

// Reproduces the shape of a real batched `id=1,2` lookup response: every
// collection's header and tracks interleaved in one flat results list rather than
// grouped, to exercise parseBatchLookup's own regrouping.
private fun batchCollectionLookupResponse() = """
    {
      "resultCount": 5,
      "results": [
        {
          "collectionId": 123456,
          "collectionName": "Blonde",
          "artistName": "Frank Ocean",
          "trackCount": 2
        },
        {
          "collectionId": 789,
          "collectionName": "Currents",
          "artistName": "Tame Impala",
          "trackCount": 1
        },
        {
          "trackId": 1,
          "collectionId": 123456,
          "trackName": "Nikes",
          "artistName": "Frank Ocean",
          "collectionName": "Blonde",
          "trackNumber": 1
        },
        {
          "trackId": 2,
          "collectionId": 123456,
          "trackName": "Ivy",
          "artistName": "Frank Ocean",
          "collectionName": "Blonde",
          "trackNumber": 2
        },
        {
          "trackId": 3,
          "collectionId": 789,
          "trackName": "Let It Happen",
          "artistName": "Tame Impala",
          "collectionName": "Currents",
          "trackNumber": 1
        }
      ]
    }
""".trimIndent()

private fun singleCollectionLookupResponse(collectionId: Long, collectionName: String, artistName: String) = """
    {
      "resultCount": 2,
      "results": [
        {
          "collectionId": $collectionId,
          "collectionName": "$collectionName",
          "artistName": "$artistName",
          "trackCount": 1
        },
        {
          "trackId": 3,
          "collectionId": $collectionId,
          "trackName": "Let It Happen",
          "artistName": "$artistName",
          "collectionName": "$collectionName",
          "trackNumber": 1
        }
      ]
    }
""".trimIndent()
