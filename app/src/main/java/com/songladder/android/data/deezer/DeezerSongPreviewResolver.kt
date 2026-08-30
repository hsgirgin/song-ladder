package com.songladder.android.data.deezer

import com.songladder.android.data.preview.PreviewUrlCache
import com.songladder.android.data.preview.SongPreviewMatcher
import com.songladder.android.domain.model.Song
import com.songladder.android.domain.repository.SongPreviewResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class DeezerSongPreviewResolver(
    private val httpClient: OkHttpClient,
    private val searchBaseUrl: HttpUrl = "https://api.deezer.com/search".toHttpUrl()
) : SongPreviewResolver {
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = PreviewUrlCache(
        positiveTtlMillis = TimeUnit.HOURS.toMillis(24),
        negativeTtlMillis = TimeUnit.MINUTES.toMillis(5)
    )

    override suspend fun resolve(song: Song): String? {
        val key = SongPreviewMatcher.cacheKey(song.title, song.artist, song.album, song.externalId.orEmpty())
        cache.getIfFresh(key)?.let { return it.url }

        val resolved = try {
            resolveBySearch(song)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            return null
        }

        cache.put(key, resolved)
        return resolved
    }

    private suspend fun resolveBySearch(song: Song): String? {
        val seenTracks = mutableSetOf<String>()
        for (term in searchTerms(song)) {
            val termResults = searchTracks(term)
                .filter { candidate ->
                    seenTracks.add(candidate.id?.toString() ?: "${candidate.title}|${candidate.artist.name}|${candidate.preview}")
                }

            bestPreviewUrl(song, termResults)?.let { return it }
        }
        return null
    }

    private fun bestPreviewUrl(song: Song, candidates: List<DeezerTrackResult>): String? =
        candidates
            .mapNotNull { candidate ->
                val previewUrl = candidate.preview?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val score = SongPreviewMatcher.score(
                    requestedTitle = song.title,
                    requestedArtist = song.artist,
                    requestedAlbum = song.album,
                    candidateTitle = candidate.title.orEmpty(),
                    candidateArtist = candidate.artist.name.orEmpty(),
                    candidateAlbum = candidate.album.title.orEmpty()
                ) ?: return@mapNotNull null
                ScoredPreview(previewUrl, score)
            }
            .maxByOrNull { it.score }
            ?.url

    private suspend fun searchTracks(term: String): List<DeezerTrackResult> {
        val url = searchBaseUrl.newBuilder()
            .addQueryParameter("q", term)
            .addQueryParameter("limit", "25")
            .build()
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        val body = executeRequest(request)
        return json.decodeFromString(DeezerSearchResponse.serializer(), body).data
    }

    private fun searchTerms(song: Song): List<String> =
        listOf(
            """track:"${song.title}" artist:"${song.artist}"""",
            "${song.title} ${song.artist}",
            listOf(song.title, song.artist, song.album).filter { it.isNotBlank() }.joinToString(" ")
        )
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

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
                                        IllegalStateException("Deezer preview lookup failed (${response.code}).")
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

    private data class ScoredPreview(val url: String, val score: Int)
}

@Serializable
private data class DeezerSearchResponse(
    @SerialName("data") val data: List<DeezerTrackResult> = emptyList()
)

@Serializable
private data class DeezerTrackResult(
    @SerialName("id") val id: Long? = null,
    @SerialName("title") val title: String? = null,
    @SerialName("preview") val preview: String? = null,
    @SerialName("artist") val artist: DeezerArtist = DeezerArtist(),
    @SerialName("album") val album: DeezerAlbum = DeezerAlbum()
)

@Serializable
private data class DeezerArtist(
    @SerialName("name") val name: String? = null
)

@Serializable
private data class DeezerAlbum(
    @SerialName("title") val title: String? = null
)
