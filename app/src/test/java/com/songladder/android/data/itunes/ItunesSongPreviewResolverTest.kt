package com.songladder.android.data.itunes

import com.songladder.android.domain.model.Song
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
            assertTrue(server.takeRequest().path.orEmpty().startsWith("/search?"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `song preview rejects a result from the wrong artist`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(searchResponse(artist = "The Corrs")))
        server.enqueue(MockResponse().setBody(searchResponse(artist = "The Corrs")))
        server.start()

        try {
            val resolver = ItunesSongPreviewResolver(
                httpClient = OkHttpClient(),
                searchBaseUrl = server.url("/search")
            )

            assertNull(resolver.resolve(song(title = "Dreams", artist = "Fleetwood Mac")))
            repeat(2) {
                assertTrue(server.takeRequest().path.orEmpty().startsWith("/search?"))
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `song preview resolves collaborative artist and parenthetical title variants`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                searchResponse(
                    title = "Ma Meilleure Ennemie (from the series Arcane League of Legends)",
                    artist = "Stromae & Pomme",
                    previewUrl = "https://audio.example/ennemie.m4a"
                )
            )
        )
        server.start()

        try {
            val resolver = ItunesSongPreviewResolver(
                httpClient = OkHttpClient(),
                searchBaseUrl = server.url("/search")
            )

            assertEquals(
                "https://audio.example/ennemie.m4a",
                resolver.resolve(song(title = "Ma Meilleure Enemies", artist = "Stromae"))
            )
            assertTrue(server.takeRequest().path.orEmpty().startsWith("/search?"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `song preview resolves a track from a related album lookup`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                searchResponse(
                    title = "Radio/Video",
                    artist = "System Of A Down",
                    album = "Mezmerize",
                    previewUrl = "https://audio.example/radio-video.m4a",
                    collectionId = 187472331
                )
            )
        )
        server.enqueue(
            MockResponse().setBody(
                searchResponse(
                    title = "Cigaro",
                    artist = "System Of A Down",
                    album = "Mezmerize",
                    previewUrl = "https://audio.example/cigaro.m4a",
                    collectionId = 187472331
                )
            )
        )
        server.start()

        try {
            val resolver = ItunesSongPreviewResolver(
                httpClient = OkHttpClient(),
                searchBaseUrl = server.url("/search"),
                lookupBaseUrl = server.url("/lookup")
            )

            assertEquals(
                "https://audio.example/cigaro.m4a",
                resolver.resolve(song(title = "Cigaro", artist = "System Of A Down"))
            )
            assertTrue(server.takeRequest().path.orEmpty().startsWith("/search?"))
            assertTrue(server.takeRequest().path.orEmpty().startsWith("/lookup?"))
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
            assertTrue(server.takeRequest().path.orEmpty().startsWith("/search?"))
            assertTrue(server.takeRequest().path.orEmpty().startsWith("/search?"))
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
    title: String = "Dreams",
    artist: String = "Fleetwood Mac",
    album: String = "Rumours",
    previewUrl: String = "https://audio.example/dreams.m4a",
    collectionId: Long = 123
) = """
    {
      "resultCount": 1,
      "results": [{
        "trackId": 123,
        "collectionId": $collectionId,
        "trackName": "$title",
        "artistName": "$artist",
        "collectionName": "$album",
        "previewUrl": "$previewUrl"
      }]
    }
""".trimIndent()
