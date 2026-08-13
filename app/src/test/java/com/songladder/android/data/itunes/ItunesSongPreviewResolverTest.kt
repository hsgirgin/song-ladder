package com.songladder.android.data.itunes

import com.songladder.android.domain.model.Song
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ItunesSongPreviewResolverTest {
    @Test
    fun `song preview resolves a matching title and artist`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(searchResponse()))
        server.start()

        try {
            val resolver = ItunesSongPreviewResolver(
                httpClient = OkHttpClient(),
                searchBaseUrl = server.url("/search")
            )

            assertEquals(
                "https://audio.example/dreams.m4a",
                resolver.resolve(song(title = "Dreams", artist = "Fleetwood Mac"))
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `song preview rejects a result from the wrong artist`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(searchResponse(artist = "The Corrs")))
        server.start()

        try {
            val resolver = ItunesSongPreviewResolver(
                httpClient = OkHttpClient(),
                searchBaseUrl = server.url("/search")
            )

            assertNull(resolver.resolve(song(title = "Dreams", artist = "Fleetwood Mac")))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `transient lookup failures are not cached as unavailable`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setBody(searchResponse()))
        server.start()

        try {
            val resolver = ItunesSongPreviewResolver(
                httpClient = OkHttpClient(),
                searchBaseUrl = server.url("/search")
            )
            val song = song(title = "Dreams", artist = "Fleetwood Mac")

            assertNull(resolver.resolve(song))
            assertEquals("https://audio.example/dreams.m4a", resolver.resolve(song))
        } finally {
            server.shutdown()
        }
    }
}

private fun song(title: String, artist: String) = Song(
    id = "song-1",
    title = title,
    artist = artist,
    createdAt = 1L
)

private fun searchResponse(artist: String = "Fleetwood Mac") = """
    {
      "resultCount": 1,
      "results": [{
        "trackId": 123,
        "trackName": "Dreams",
        "artistName": "$artist",
        "previewUrl": "https://audio.example/dreams.m4a"
      }]
    }
""".trimIndent()
