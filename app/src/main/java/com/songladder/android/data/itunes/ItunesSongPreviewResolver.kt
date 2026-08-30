package com.songladder.android.data.itunes

import com.songladder.android.data.preview.PreviewUrlCache
import com.songladder.android.data.preview.SongPreviewMatcher
import com.songladder.android.domain.model.Song
import com.songladder.android.domain.repository.SongPreviewResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class ItunesSongPreviewResolver(
    private val httpClient: OkHttpClient,
    private val searchBaseUrl: HttpUrl = "https://itunes.apple.com/search".toHttpUrl(),
    private val lookupBaseUrl: HttpUrl = "https://itunes.apple.com/lookup".toHttpUrl()
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
            resolveByTrackId(song) ?: resolveBySearch(song)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            return null
        }

        cache.put(key, resolved)
        return resolved
    }

    private suspend fun resolveByTrackId(song: Song): String? {
        val trackId = song.externalId?.toLongOrNull() ?: return null
        val results = try {
            lookupTrack(trackId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            return null
        }
        return bestPreviewUrl(song, results)
    }

    private suspend fun resolveBySearch(song: Song): String? {
        val seenTracks = mutableSetOf<String>()
        val seenCollections = mutableSetOf<Long>()

        for (term in searchTerms(song)) {
            val termResults = searchTracks(term)
                .filter { candidate ->
                    seenTracks.add(candidate.trackId?.toString() ?: "${candidate.trackName}|${candidate.artistName}|${candidate.previewUrl}")
                }

            bestPreviewUrl(song, termResults)?.let { return it }

            val collectionIds = termResults
                .filter { candidate ->
                    candidate.collectionId != null &&
                        SongPreviewMatcher.artistMatches(song.artist, candidate.artistName.orEmpty())
                }
                .mapNotNull { it.collectionId }
                .filter { seenCollections.add(it) }
                .take(10)

            for (collectionId in collectionIds) {
                bestPreviewUrl(song, lookupCollection(collectionId))?.let { return it }
            }
        }
        return null
    }

    private suspend fun searchTracks(term: String): List<ItunesTrackResult> {
        val url = searchBaseUrl.newBuilder()
            .addQueryParameter("term", term)
            .addQueryParameter("entity", "song")
            .addQueryParameter("limit", "50")
            .build()
        return executeSearchRequest(url)
    }

    private suspend fun lookupTrack(trackId: Long): List<ItunesTrackResult> {
        val url = lookupBaseUrl.newBuilder()
            .addQueryParameter("id", trackId.toString())
            .build()
        return executeSearchRequest(url)
    }

    private suspend fun lookupCollection(collectionId: Long): List<ItunesTrackResult> {
        val url = lookupBaseUrl.newBuilder()
            .addQueryParameter("id", collectionId.toString())
            .addQueryParameter("entity", "song")
            .build()
        return executeSearchRequest(url)
    }

    private suspend fun executeSearchRequest(url: HttpUrl): List<ItunesTrackResult> {
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        val body = executeRequest(request)
        return json.decodeFromString(ItunesSearchResponse.serializer(), body).results
    }

    private fun bestPreviewUrl(song: Song, candidates: List<ItunesTrackResult>): String? =
        candidates
            .mapNotNull { candidate ->
                val previewUrl = candidate.previewUrl?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val score = SongPreviewMatcher.score(
                    requestedTitle = song.title,
                    requestedArtist = song.artist,
                    requestedAlbum = song.album,
                    candidateTitle = candidate.trackName.orEmpty(),
                    candidateArtist = candidate.artistName.orEmpty(),
                    candidateAlbum = candidate.collectionName.orEmpty()
                ) ?: return@mapNotNull null
                ScoredPreview(previewUrl, score)
            }
            .maxByOrNull { it.score }
            ?.url

    private fun searchTerms(song: Song): List<String> =
        listOf(
            "${song.title} ${song.artist}",
            listOf(song.title, song.artist, song.album).filter { it.isNotBlank() }.joinToString(" "),
            song.title
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
                                        IllegalStateException("iTunes preview lookup failed (${response.code}).")
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
