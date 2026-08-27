package com.songladder.android.data.itunes

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
