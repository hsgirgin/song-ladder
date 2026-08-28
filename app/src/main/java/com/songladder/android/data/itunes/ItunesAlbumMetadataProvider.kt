package com.songladder.android.data.itunes

import com.songladder.android.domain.model.AlbumReleaseCandidate
import com.songladder.android.domain.model.AlbumReleaseLookup
import com.songladder.android.domain.model.AlbumReleaseTrack
import com.songladder.android.domain.model.TimeSource
import com.songladder.android.domain.repository.AlbumMetadataProvider
import com.songladder.android.domain.repository.AlbumMetadataUnavailableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * The metadata provider for Phase 3 album matching. Reuses the same iTunes Lookup
 * endpoint [ItunesSongPreviewResolver] already calls for collection previews
 * (`entity=song` on a collection id returns the collection header plus every track on
 * it in one request), the Search endpoint with `entity=album` for candidate discovery,
 * `entity=musicArtist` to resolve an artist id reliably, and Lookup with `entity=album`
 * on that id for the artist's full discography (see [searchReleases]). Kept as a
 * sibling of [ItunesMusicSourceClient]/[ItunesSongPreviewResolver] (transport in
 * `data.itunes`) with scoring logic left to
 * [com.songladder.android.domain.engine.AlbumMatchingEngine] (pure `domain.engine`),
 * matching this codebase's existing transport/domain split.
 */
class ItunesAlbumMetadataProvider(
    private val httpClient: OkHttpClient,
    private val searchBaseUrl: HttpUrl = "https://itunes.apple.com/search".toHttpUrl(),
    private val lookupBaseUrl: HttpUrl = "https://itunes.apple.com/lookup".toHttpUrl(),
    private val timeSource: TimeSource = TimeSource { System.currentTimeMillis() }
) : AlbumMetadataProvider {
    private val json = Json { ignoreUnknownKeys = true }

    // Lookups (not searches) are cached: they're re-read every time an album's detail
    // is refreshed or its missing tracks are recomputed, but must still expire and be
    // retryable rather than pinned forever the way ItunesSongPreviewResolver's preview
    // cache is - an album's real tracklist can change over time.
    private val lookupCache = ConcurrentHashMap<String, CachedLookup>()

    override suspend fun searchReleases(artist: String, album: String): Result<List<AlbumReleaseCandidate>> =
        runCatchingCancellable {
            require(album.isNotBlank()) { "Album title is required." }
            val term = listOf(artist, album).filter { it.isNotBlank() }.joinToString(" ")
            val url = searchBaseUrl.newBuilder()
                .addQueryParameter("term", term)
                .addQueryParameter("entity", "album")
                .addQueryParameter("limit", "10")
                .build()
            val termCandidates = candidatesFrom(parseSearchResults(executeRequest(url)))

            // The Search endpoint's relevance ranking routinely omits an artist's own
            // studio album from its top results entirely - e.g. searching "System of a
            // Down Toxicity" or "Tame Impala Currents" surfaces karaoke covers, tribute
            // compilations, and unrelated singles, but never the real album, even though
            // it exists in the catalog under that artist. No amount of scoring in
            // AlbumMatchingEngine can recommend a candidate that was never fetched, so
            // the artist's full discography is pulled via Lookup (which returns
            // everything credited to an artist id rather than a relevance-ranked
            // subset) and merged in as additional candidates. The artist id for that
            // lookup comes from a dedicated artist search rather than from scanning
            // termCandidates for a name match: a short or generic artist name (e.g.
            // "Muse") can be outranked in the album search entirely by decoy artists
            // whose name merely contains it (e.g. "Mindful Muse"), so termCandidates
            // may not include the right artist at all to fall back from.
            val discographyCandidates = artist.takeIf { it.isNotBlank() }
                ?.let { resolveArtistId(it) }
                ?.let { artistId ->
                    val discographyUrl = lookupBaseUrl.newBuilder()
                        .addQueryParameter("id", artistId.toString())
                        .addQueryParameter("entity", "album")
                        .addQueryParameter("limit", "200")
                        .build()
                    candidatesFrom(parseSearchResults(executeRequest(discographyUrl)))
                }.orEmpty()

            (termCandidates + discographyCandidates).distinctBy { it.collectionId }
        }.recoverProviderFailure()

    private suspend fun resolveArtistId(artist: String): Long? {
        val url = searchBaseUrl.newBuilder()
            .addQueryParameter("term", artist)
            .addQueryParameter("entity", "musicArtist")
            .addQueryParameter("limit", "1")
            .build()
        return parseSearchResults(executeRequest(url)).firstOrNull()?.artistId
    }

    override suspend fun lookupRelease(collectionId: String, forceRefresh: Boolean): Result<AlbumReleaseLookup> =
        runCatchingCancellable {
            val now = timeSource.now()
            if (!forceRefresh) {
                val cached = lookupCache[collectionId]?.takeIf { it.expiresAtMillis > now }
                if (cached != null) return@runCatchingCancellable cached.lookup
            }

            val url = lookupBaseUrl.newBuilder()
                .addQueryParameter("id", collectionId)
                .addQueryParameter("entity", "song")
                .build()
            val lookup = parseLookup(collectionId, executeRequest(url))
                ?: throw AlbumMetadataUnavailableException("Release $collectionId was not found.")
            lookupCache[collectionId] = CachedLookup(lookup, now + CACHE_TTL_MILLIS)
            lookup
        }.recoverProviderFailure()

    // Plain kotlin.runCatching also catches CancellationException, which would turn
    // structured-concurrency cancellation into an ordinary Result.failure instead of
    // letting it propagate - rethrow it before it can be mistaken for a provider
    // failure. TimeoutCancellationException is itself a CancellationException
    // subtype, so it must be caught *first* and routed to recoverProviderFailure's
    // existing timeout branch below rather than being rethrown as real cancellation -
    // this codebase already wraps sibling network calls in withTimeout
    // (ItunesMusicSourceClient, YoutubeMusicPlaylistClient), so this isn't hypothetical.
    private inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: TimeoutCancellationException) {
        Result.failure(e)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

    private fun <T> Result<T>.recoverProviderFailure(): Result<T> = recoverCatching { throwable ->
        when (throwable) {
            is AlbumMetadataUnavailableException -> throw throwable
            is TimeoutCancellationException ->
                throw AlbumMetadataUnavailableException("iTunes album lookup timed out.", throwable)
            is IOException ->
                throw AlbumMetadataUnavailableException("Could not reach iTunes for album metadata.", throwable)
            is SerializationException ->
                throw AlbumMetadataUnavailableException("iTunes returned an unexpected response.", throwable)
            is IllegalStateException ->
                throw AlbumMetadataUnavailableException(throwable.message.orEmpty(), throwable)
            else -> throw throwable
        }
    }

    private fun parseSearchResults(body: String): List<ItunesTrackResult> =
        json.decodeFromString(ItunesSearchResponse.serializer(), body).results

    private fun candidatesFrom(results: List<ItunesTrackResult>): List<AlbumReleaseCandidate> {
        return results.mapNotNull { result ->
            val collectionId = result.collectionId ?: return@mapNotNull null
            val collectionName = result.collectionName?.trim().orEmpty()
            val artistName = result.artistName?.trim().orEmpty()
            if (collectionName.isBlank() || artistName.isBlank()) return@mapNotNull null
            AlbumReleaseCandidate(
                collectionId = collectionId.toString(),
                collectionName = collectionName,
                artistName = artistName,
                artworkUrl = result.artworkUrl100?.takeIf { it.isNotBlank() }?.replace("100x100", "600x600"),
                trackCount = result.trackCount
            )
        }
    }

    // The collection header record (wrapperType "collection") has no trackId; every
    // track in the same response does. Distinguishing on trackId rather than
    // trackNumber is deliberate - trackNumber is more likely to be missing/malformed
    // on an individual track than trackId is.
    private fun parseLookup(collectionId: String, body: String): AlbumReleaseLookup? {
        val payload = json.decodeFromString(ItunesSearchResponse.serializer(), body)
        val header = payload.results.firstOrNull { it.trackId == null && it.collectionId != null }
            ?: return null
        val tracks = payload.results
            .filter { it.trackId != null }
            .mapNotNull { track ->
                val trackId = track.trackId ?: return@mapNotNull null
                val title = track.trackName?.trim().orEmpty()
                if (title.isBlank()) return@mapNotNull null
                AlbumReleaseTrack(
                    trackId = trackId.toString(),
                    title = title,
                    trackNumber = track.trackNumber,
                    artworkUrl = track.artworkUrl100?.takeIf { it.isNotBlank() }?.replace("100x100", "600x600")
                )
            }
        return AlbumReleaseLookup(
            collectionId = collectionId,
            collectionName = header.collectionName?.trim().orEmpty(),
            artistName = header.artistName?.trim().orEmpty(),
            artworkUrl = header.artworkUrl100?.takeIf { it.isNotBlank() }?.replace("100x100", "600x600"),
            trackCount = header.trackCount,
            tracks = tracks
        )
    }

    private suspend fun executeRequest(url: HttpUrl): String {
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        return suspendCancellableCoroutine { continuation ->
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
                                        AlbumMetadataUnavailableException(
                                            "iTunes album lookup failed (${response.code})."
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
    }

    private data class CachedLookup(val lookup: AlbumReleaseLookup, val expiresAtMillis: Long)

    private companion object {
        const val CACHE_TTL_MILLIS = 30 * 60 * 1000L
    }
}
