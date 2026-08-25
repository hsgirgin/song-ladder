package com.songladder.android.data.spotify

import com.songladder.android.domain.model.AmbiguousPlaylistTrack
import com.songladder.android.domain.model.MusicSourceType
import com.songladder.android.domain.model.MusicTrackCandidate
import com.songladder.android.domain.model.PlaylistImportPreview
import com.songladder.android.domain.repository.PlaylistSourceClient
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume

class SpotifyPlaylistClient(
    private val getAccessToken: suspend () -> Result<String>,
    private val httpClient: OkHttpClient,
    private val apiBaseUrl: HttpUrl = "https://api.spotify.com".toHttpUrl()
) : PlaylistSourceClient {
    constructor(authManager: SpotifyAuthManager, httpClient: OkHttpClient) : this(
        getAccessToken = authManager::getValidAccessToken,
        httpClient = httpClient
    )

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun previewPlaylist(url: String): Result<PlaylistImportPreview> {
        return runCatching {
            withTimeout(20_000) {
                val playlistId = extractPlaylistId(url)
                    ?: error("Paste a link to one of your Spotify playlists.")
                val accessToken = getAccessToken()
                    .getOrElse { error("Connect your Spotify account first.") }

                val playlistTitle = fetchPlaylistTitle(playlistId, accessToken)
                val (importable, ambiguous) = fetchAllTracks(playlistId, accessToken)

                if (importable.isEmpty() && ambiguous.isEmpty()) {
                    error(
                        "Could not find any tracks in this playlist. Only playlists in your own " +
                            "Spotify account can be imported right now (Spotify Developer Mode restriction)."
                    )
                }

                PlaylistImportPreview(
                    playlistTitle = playlistTitle,
                    importableTracks = importable,
                    ambiguousTracks = ambiguous
                )
            }
        }.recoverCatching { throwable ->
            when (throwable) {
                is TimeoutCancellationException ->
                    error("Spotify preview timed out. Try again in a moment.")
                is IOException ->
                    error("Could not reach Spotify. Check your connection and try again.")
                else -> throw throwable
            }
        }
    }

    private fun extractPlaylistId(url: String): String? {
        val trimmed = url.trim()
        Regex("""spotify:playlist:([A-Za-z0-9]+)""").find(trimmed)?.let {
            return it.groupValues[1]
        }
        val parsed = trimmed.toHttpUrlOrNull() ?: return null
        if (!parsed.host.lowercase().endsWith("spotify.com")) return null
        val segments = parsed.pathSegments
        val playlistIndex = segments.indexOf("playlist")
        if (playlistIndex == -1 || playlistIndex + 1 >= segments.size) return null
        return segments[playlistIndex + 1].takeIf { it.isNotBlank() }
    }

    private fun playlistUrlBuilder(playlistId: String): HttpUrl.Builder =
        apiBaseUrl.newBuilder().addPathSegments("v1/playlists/$playlistId")

    private suspend fun fetchPlaylistTitle(playlistId: String, accessToken: String): String {
        val url = playlistUrlBuilder(playlistId)
            .addQueryParameter("fields", "name")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .build()
        val body = executeRequest(request)
        val name = json.parseToJsonElement(body).jsonObject["name"]?.jsonPrimitive?.contentOrNull
        return name?.takeIf { it.isNotBlank() } ?: "Spotify Playlist"
    }

    private suspend fun fetchAllTracks(
        playlistId: String,
        accessToken: String
    ): Pair<List<MusicTrackCandidate>, List<AmbiguousPlaylistTrack>> {
        val importable = mutableListOf<MusicTrackCandidate>()
        val ambiguous = mutableListOf<AmbiguousPlaylistTrack>()
        var offset = 0
        var page = 0

        while (page < MAX_PAGES) {
            val url = playlistUrlBuilder(playlistId).addPathSegment("tracks")
                .addQueryParameter("limit", PAGE_SIZE.toString())
                .addQueryParameter("offset", offset.toString())
                .addQueryParameter("fields", "next,items(is_local,track(id,name,album(name,images),artists(name)))")
                .build()
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .build()

            val body = executeRequest(request)
            val root = json.parseToJsonElement(body).jsonObject
            val items = root["items"]?.jsonArray ?: JsonArray(emptyList())

            items.forEach { item -> collectTrack(item.jsonObject, importable, ambiguous) }

            val hasNext = root["next"]?.jsonPrimitive?.contentOrNull != null
            page += 1
            if (!hasNext || items.isEmpty()) break
            offset += PAGE_SIZE
        }

        return importable to ambiguous
    }

    private fun collectTrack(
        item: JsonObject,
        importable: MutableList<MusicTrackCandidate>,
        ambiguous: MutableList<AmbiguousPlaylistTrack>
    ) {
        val isLocal = item["is_local"]?.jsonPrimitive?.boolean ?: false
        val track = item["track"]?.jsonObject
        if (isLocal || track == null) return

        val id = track["id"]?.jsonPrimitive?.contentOrNull
        val title = track["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val artist = track["artists"]?.jsonArray
            ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
            ?.joinToString(", ")
            .orEmpty()
        val album = track["album"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty()
        val artworkUrl = track["album"]?.jsonObject?.get("images")?.jsonArray
            ?.firstOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull

        if (id == null || title.isBlank() || artist.isBlank()) {
            ambiguous += AmbiguousPlaylistTrack(
                rawTitle = title,
                rawArtist = artist,
                reason = "Missing title or artist metadata."
            )
        } else {
            importable += MusicTrackCandidate(
                externalId = id,
                title = title,
                artist = artist,
                album = album,
                artworkUrl = artworkUrl,
                sourceType = MusicSourceType.SPOTIFY
            )
        }
    }

    private suspend fun executeRequest(request: Request): String =
        suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isCancelled) continuation.resumeWith(Result.failure(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!response.isSuccessful) {
                            if (!continuation.isCancelled) {
                                continuation.resumeWith(
                                    Result.failure(
                                        IllegalStateException(
                                            "Spotify request failed (${response.code}). Make sure you're signed in."
                                        )
                                    )
                                )
                            }
                            return
                        }
                        val body = response.body?.string().orEmpty()
                        if (!continuation.isCancelled) continuation.resume(body)
                    }
                }
            })
        }

    private companion object {
        const val PAGE_SIZE = 100
        const val MAX_PAGES = 50
    }
}
