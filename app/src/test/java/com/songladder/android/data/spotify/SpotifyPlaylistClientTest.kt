package com.songladder.android.data.spotify

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyPlaylistClientTest {
    private fun client(
        server: MockWebServer,
        accessToken: Result<String> = Result.success("token")
    ) = SpotifyPlaylistClient(
        getAccessToken = { accessToken },
        httpClient = OkHttpClient(),
        apiBaseUrl = server.url("/")
    )

    @Test
    fun `previews a playlist and maps tracks, skipping local and ambiguous ones`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"name":"Night Drive"}"""))
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "next": null,
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
                    },
                    {
                      "is_local": false,
                      "track": { "id": "def456", "name": "", "album": { "name": "" }, "artists": [] }
                    }
                  ]
                }
                """.trimIndent()
            )
        )
        server.start()

        try {
            val preview = client(server)
                .previewPlaylist("https://open.spotify.com/playlist/PL123")
                .getOrThrow()

            assertEquals("Night Drive", preview.playlistTitle)
            assertEquals(1, preview.importableTracks.size)
            assertEquals("Midnight City", preview.importableTracks.single().title)
            assertEquals("M83", preview.importableTracks.single().artist)
            assertEquals("https://img/1.jpg", preview.importableTracks.single().artworkUrl)
            assertEquals(1, preview.ambiguousTracks.size)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `paginates through multiple pages of tracks`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"name":"Big Playlist"}"""))
        server.enqueue(MockResponse().setBody("""{"next":"has-more","items":[${trackJson("t1")}]}"""))
        server.enqueue(MockResponse().setBody("""{"next":null,"items":[${trackJson("t2")}]}"""))
        server.start()

        try {
            val preview = client(server)
                .previewPlaylist("https://open.spotify.com/playlist/PL999")
                .getOrThrow()

            assertEquals(listOf("t1", "t2"), preview.importableTracks.map { it.externalId })
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `surfaces the developer mode restriction when no tracks are returned`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"name":"Someone Else's Playlist"}"""))
        server.enqueue(MockResponse().setBody("""{"next":null,"items":[]}"""))
        server.start()

        try {
            val result = client(server).previewPlaylist("https://open.spotify.com/playlist/PLXYZ")
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("Developer Mode"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `rejects a url that is not a spotify playlist link`() = runTest {
        val server = MockWebServer()
        server.start()

        try {
            val result = client(server).previewPlaylist("https://example.com/not-spotify")
            assertTrue(result.isFailure)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `surfaces a connect prompt when not signed in`() = runTest {
        val server = MockWebServer()
        server.start()

        try {
            val result = client(server, accessToken = Result.failure(IllegalStateException("Not connected to Spotify.")))
                .previewPlaylist("https://open.spotify.com/playlist/PL123")
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("Connect your Spotify account"))
        } finally {
            server.shutdown()
        }
    }

    private fun trackJson(id: String): String = """
        { "is_local": false, "track": { "id": "$id", "name": "Track $id", "album": { "name": "Album", "images": [] }, "artists": [{ "name": "Artist" }] } }
    """.trimIndent()
}
