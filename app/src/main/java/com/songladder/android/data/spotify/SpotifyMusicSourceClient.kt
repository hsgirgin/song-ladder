package com.songladder.android.data.spotify

import com.songladder.android.BuildConfig
import com.songladder.android.domain.model.MusicTrackCandidate
import com.songladder.android.domain.repository.MusicSourceClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

class SpotifyMusicSourceClient(
    private val httpClient: OkHttpClient
) : MusicSourceClient {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun searchTracks(query: String, authToken: String): Result<List<MusicTrackCandidate>> {
        return withContext(Dispatchers.IO) {
            runCatching {
                require(query.isNotBlank()) { "Enter a search query." }
                require(authToken.isNotBlank()) { "Add a Spotify token to search streaming tracks." }

                val url = "${BuildConfig.SPOTIFY_API_BASE_URL}search".toHttpUrl().newBuilder()
                    .addQueryParameter("type", "track")
                    .addQueryParameter("limit", "20")
                    .addQueryParameter("q", query.trim())
                    .build()

                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer ${authToken.trim()}")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        error("Spotify search failed (${response.code}). Check your token and try again.")
                    }

                    val body = response.body?.string().orEmpty()
                    val payload = json.decodeFromString<SpotifySearchResponse>(body)
                    payload.tracks.items.map { track ->
                        MusicTrackCandidate(
                            externalId = track.id,
                            title = track.name,
                            artist = track.artists.joinToString(", ") { it.name },
                            album = track.album.name,
                            artworkUrl = track.album.images.firstOrNull()?.url
                        )
                    }
                }
            }
        }
    }
}

@Serializable
private data class SpotifySearchResponse(
    val tracks: SpotifyTrackContainer
)

@Serializable
private data class SpotifyTrackContainer(
    val items: List<SpotifyTrack>
)

@Serializable
private data class SpotifyTrack(
    val id: String,
    val name: String,
    val artists: List<SpotifyArtist>,
    val album: SpotifyAlbum
)

@Serializable
private data class SpotifyArtist(
    val name: String
)

@Serializable
private data class SpotifyAlbum(
    val name: String,
    val images: List<SpotifyImage> = emptyList()
)

@Serializable
private data class SpotifyImage(
    @SerialName("url") val url: String
)
