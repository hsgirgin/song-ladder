package com.songladder.android.data.deezer

import com.songladder.android.domain.model.Song
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeezerSongPreviewResolverTest {
    @Test
    fun `song preview resolves a matching title and artist`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(searchResponse()))
        server.start()

        try {
            val resolver = DeezerSongPreviewResolver(
                httpClient = OkHttpClient(),
                searchBaseUrl = server.url("/search")
            )

            assertEquals(
                "https://audio.example/ratatata.mp3",
                resolver.resolve(song(title = "RATATATA", artist = "BABYMETAL"))
            )
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `song preview rejects a title match from the wrong artist`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(searchResponse(artist = "Backing Business")))
        server.enqueue(MockResponse().setBody(searchResponse(artist = "Backing Business")))
        server.start()

        try {
            val resolver = DeezerSongPreviewResolver(
                httpClient = OkHttpClient(),
                searchBaseUrl = server.url("/search")
            )

            assertNull(resolver.resolve(song(title = "RATATATA", artist = "BABYMETAL")))
            assertEquals(2, server.requestCount)
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
            val resolver = DeezerSongPreviewResolver(
                httpClient = OkHttpClient(),
                searchBaseUrl = server.url("/search")
            )
            val query = song(title = "RATATATA", artist = "BABYMETAL")

            assertNull(resolver.resolve(query))
            assertEquals(
                "https://audio.example/ratatata.mp3",
                resolver.resolve(query)
            )
            assertEquals(2, server.requestCount)
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

private fun searchResponse(
    title: String = "RATATATA",
    artist: String = "BABYMETAL",
    album: String = "TANZNEID",
    previewUrl: String = "https://audio.example/ratatata.mp3"
) = """
    {
      "data": [{
        "id": 123,
        "title": "$title",
        "preview": "$previewUrl",
        "artist": {
          "name": "$artist"
        },
        "album": {
          "title": "$album"
        }
      }]
    }
""".trimIndent()
