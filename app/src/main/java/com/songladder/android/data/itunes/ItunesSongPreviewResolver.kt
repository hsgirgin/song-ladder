package com.songladder.android.data.itunes

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
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

class ItunesSongPreviewResolver(
    private val httpClient: OkHttpClient,
    private val searchBaseUrl: HttpUrl = "https://itunes.apple.com/search".toHttpUrl()
) : SongPreviewResolver {
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = ConcurrentHashMap<String, CachedPreview>()

    override suspend fun resolve(song: Song): String? {
        val key = "${normalize(song.title)}|${normalize(song.artist)}"
        cache[key]?.let { return it.url }

        val resolved = try {
            val url = searchBaseUrl.newBuilder()
                .addQueryParameter("term", "${song.title} ${song.artist}")
                .addQueryParameter("entity", "song")
                .addQueryParameter("limit", "10")
                .build()
            val request = Request.Builder().url(url).header("Accept", "application/json").build()
            val body = executeRequest(request)
            json.decodeFromString(ItunesSearchResponse.serializer(), body).results
                .firstOrNull { candidate ->
                    normalize(candidate.trackName.orEmpty()) == normalize(song.title) &&
                        normalize(candidate.artistName.orEmpty()) == normalize(song.artist) &&
                        !candidate.previewUrl.isNullOrBlank()
                }
                ?.previewUrl
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            return null
        }

        cache[key] = CachedPreview(resolved)
        return resolved
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

    private fun normalize(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

    private data class CachedPreview(val url: String?)
}
