package com.songladder.android.data.itunes

import com.songladder.android.domain.model.MusicSourceType
import com.songladder.android.domain.model.MusicTrackCandidate
import com.songladder.android.domain.repository.MusicSourceClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume

class ItunesMusicSourceClient(
    private val httpClient: OkHttpClient
) : MusicSourceClient {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun searchTracks(query: String): Result<List<MusicTrackCandidate>> {
        return runCatching {
            withTimeout(15_000) {
                require(query.isNotBlank()) { "Enter a song or artist to search." }

                val url = "https://itunes.apple.com/search".toHttpUrl().newBuilder()
                    .addQueryParameter("term", query.trim())
                    .addQueryParameter("entity", "song")
                    .addQueryParameter("limit", "20")
                    .build()

                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .build()

                val responseBody = executeRequest(request)
                parseSearchResponse(responseBody)
            }
        }.recoverCatching { throwable ->
            when (throwable) {
                is kotlinx.coroutines.TimeoutCancellationException -> {
                    error("iTunes search timed out. Check your connection and try again.")
                }
                is IOException -> {
                    error("Could not reach iTunes. Check your internet connection and try again.")
                }
                else -> throw throwable
            }
        }
    }

    private suspend fun executeRequest(request: Request): String =
        suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }

            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isCancelled) return
                    continuation.resumeWith(Result.failure(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!response.isSuccessful) {
                            if (!continuation.isCancelled) {
                                continuation.resumeWith(
                                    Result.failure(
                                        IllegalStateException(
                                            "iTunes search failed (${response.code}). Try again in a moment."
                                        )
                                    )
                                )
                            }
                            return
                        }

                        val body = response.body?.string().orEmpty()
                        if (!continuation.isCancelled) {
                            continuation.resume(body)
                        }
                    }
                }
            })
        }

    internal fun parseSearchResponse(body: String): List<MusicTrackCandidate> {
        val payload = json.decodeFromString(ItunesSearchResponse.serializer(), body)
        return payload.results.mapNotNull { track ->
            val trackId = track.trackId ?: return@mapNotNull null
            val title = track.trackName?.trim().orEmpty()
            val artist = track.artistName?.trim().orEmpty()
            if (title.isBlank() || artist.isBlank()) return@mapNotNull null

            MusicTrackCandidate(
                externalId = trackId.toString(),
                title = title,
                artist = artist,
                album = track.collectionName?.trim().orEmpty(),
                artworkUrl = track.artworkUrl100?.takeIf { it.isNotBlank() },
                sourceType = MusicSourceType.ITUNES
            )
        }
    }
}

@Serializable
internal data class ItunesSearchResponse(
    @SerialName("resultCount") val resultCount: Int = 0,
    @SerialName("results") val results: List<ItunesTrackResult> = emptyList()
)

@Serializable
internal data class ItunesTrackResult(
    @SerialName("trackId") val trackId: Long? = null,
    @SerialName("trackName") val trackName: String? = null,
    @SerialName("artistName") val artistName: String? = null,
    @SerialName("collectionName") val collectionName: String? = null,
    @SerialName("artworkUrl100") val artworkUrl100: String? = null
)
