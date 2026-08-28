package com.songladder.android.data.repository

import android.util.Log
import androidx.room.withTransaction
import com.songladder.android.data.local.AlbumDao
import com.songladder.android.data.local.AlbumEntity
import com.songladder.android.data.local.AlbumMissingTrackDao
import com.songladder.android.data.local.AlbumMissingTrackEntity
import com.songladder.android.data.local.AlbumTrackExclusionDao
import com.songladder.android.data.local.AlbumTrackExclusionEntity
import com.songladder.android.data.local.RankingSubjectEntity
import com.songladder.android.data.local.SongDao
import com.songladder.android.data.local.SongEntity
import com.songladder.android.data.local.SongLadderDatabase
import com.songladder.android.data.local.SongWithStatsEntity
import com.songladder.android.data.local.toDomain
import com.songladder.android.domain.engine.AlbumMatchingEngine
import com.songladder.android.domain.model.AlbumDetail
import com.songladder.android.domain.model.AlbumMatchStatus
import com.songladder.android.domain.model.AlbumReleaseCandidate
import com.songladder.android.domain.model.AlbumTrackRow
import com.songladder.android.domain.model.AlbumReleaseLookup
import com.songladder.android.domain.model.MusicSourceType
import com.songladder.android.domain.model.RankedAlbum
import com.songladder.android.domain.model.TimeSource
import com.songladder.android.domain.model.computeAlbumScoreTenths
import com.songladder.android.domain.model.normalizedAlbumId
import com.songladder.android.domain.repository.AlbumMetadataProvider
import com.songladder.android.domain.repository.AlbumRepository
import com.songladder.android.domain.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class DefaultAlbumRepository(
    private val database: SongLadderDatabase,
    private val songDao: SongDao,
    private val albumDao: AlbumDao,
    private val albumTrackExclusionDao: AlbumTrackExclusionDao,
    private val albumMissingTrackDao: AlbumMissingTrackDao,
    private val albumMetadataProvider: AlbumMetadataProvider,
    private val settingsRepository: SettingsRepository,
    private val matchingEngine: AlbumMatchingEngine = AlbumMatchingEngine(),
    private val timeSource: TimeSource = TimeSource { System.currentTimeMillis() },
    private val matchAttemptSpacingMillis: Long = MATCH_ATTEMPT_SPACING_MILLIS,
    private val repositoryScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : AlbumRepository {

    // Guards against the same album ever being matched by two overlapping calls at
    // once - e.g. a flaky-wifi burst of connectivity callbacks each firing
    // retryPendingMatches() before the previous one's status write has committed,
    // which would otherwise both see the album as PENDING-and-eligible and both
    // hit the provider for it.
    private val matchesInFlight = ConcurrentHashMap.newKeySet<String>()

    init {
        // Discovers new (artist, album) groupings and attempts an initial match for
        // them as soon as songs carrying that grouping exist - this alone satisfies
        // "start metadata matching automatically after songs are added" with no hook
        // needed in DefaultSongRepository/DefaultImportRepository. distinctUntilChanged
        // means a song's score/rating changing (which doesn't affect its grouping)
        // never re-triggers this.
        //
        // A failure inside discoverAndMatch (a Room I/O error, an unexpected provider
        // exception, etc.) must never be allowed to escape this collector: launchIn
        // has no restart path, so an uncaught exception here would either silently and
        // permanently kill background album matching for the rest of the process's
        // lifetime, or crash the app outright (there's no CoroutineExceptionHandler
        // anywhere in this app). The inner try/catch keeps the collector alive across
        // a single bad pass; the outer catch is a last-resort net for a failure in the
        // flow machinery itself (map/distinctUntilChanged/the underlying Room query),
        // logged rather than left to crash the process.
        songDao.observeSongsWithStats()
            .map(::deriveGroupings)
            .distinctUntilChanged()
            .onEach { groupings ->
                try {
                    discoverAndMatch(groupings)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Album auto-discovery/match pass failed; will retry on the next library change.", e)
                }
            }
            .catch { e -> Log.e(TAG, "Album auto-discovery collector terminated unexpectedly.", e) }
            .launchIn(repositoryScope)
    }

    override fun observeAlbums(): Flow<List<RankedAlbum>> = combine(
        albumDao.observeAll(),
        songDao.observeSongsWithStats(),
        albumTrackExclusionDao.observeAll()
    ) { albums, songRows, exclusions ->
        val excludedSongIds = exclusions.map { it.songId }.toSet()
        val songsByAlbumId = songRows
            .filter { it.song.album.isNotBlank() && it.song.artist.isNotBlank() }
            .groupBy { groupingIdFor(it) }
        albums.map { album ->
            val ownedSongs = songsByAlbumId[album.id].orEmpty()
            val includedRated = ownedSongs
                .filter { it.song.id !in excludedSongIds && it.stats.scoreTenths != null }
                .map { it.stats.scoreTenths!! }
            RankedAlbum(
                rank = null,
                album = album.toDomain(),
                scoreTenths = computeAlbumScoreTenths(includedRated, album.providerTrackCount),
                includedRatedTrackCount = includedRated.size,
                totalOwnedTrackCount = ownedSongs.size
            )
        }
    }

    override fun observeAlbumDetail(albumId: String): Flow<AlbumDetail?> = combine(
        albumDao.observe(albumId),
        songDao.observeSongsWithStats(),
        albumTrackExclusionDao.observeAll(),
        albumMissingTrackDao.observeForAlbum(albumId)
    ) { album, songRows, exclusions, missingTracks ->
        if (album == null) return@combine null
        val excludedSongIds = exclusions.filter { it.albumId == albumId }.map { it.songId }.toSet()
        val tracks = songRows
            .filter { groupingIdFor(it) == albumId }
            .map { row ->
                AlbumTrackRow(
                    song = row.toDomain(),
                    excludedFromAverage = row.song.id in excludedSongIds
                )
            }
            .sortedBy { it.song.title.lowercase() }
        val includedRated = tracks
            .filter { !it.excludedFromAverage && it.song.scoreTenths != null }
            .map { it.song.scoreTenths!! }
        AlbumDetail(
            album = album.toDomain(),
            tracks = tracks,
            missingTracks = missingTracks.map { it.toDomain() }.sortedBy { it.trackNumber ?: Int.MAX_VALUE },
            scoreTenths = computeAlbumScoreTenths(includedRated, album.providerTrackCount),
            includedRatedTrackCount = includedRated.size
        )
    }

    override suspend fun setTrackExcluded(albumId: String, songId: String, excluded: Boolean): Result<Unit> =
        runCatching {
            database.withTransaction {
                if (excluded) {
                    albumTrackExclusionDao.insert(
                        AlbumTrackExclusionEntity(songId = songId, albumId = albumId, excludedAt = timeSource.now())
                    )
                } else {
                    albumTrackExclusionDao.delete(songId)
                }
            }
        }

    override suspend fun chooseRelease(albumId: String, providerCollectionId: String): Result<Unit> = runCatching {
        val album = albumDao.get(albumId) ?: error("Album not found.")
        val lookup = albumMetadataProvider.lookupRelease(providerCollectionId).getOrThrow()
        val confirmed = album.copy(
            providerCollectionId = providerCollectionId,
            providerTrackCount = lookup.trackCount,
            artworkUrl = lookup.artworkUrl ?: album.artworkUrl,
            matchStatus = AlbumMatchStatus.CONFIRMED.name,
            matchConfidence = 1.0,
            lastMatchAttemptAt = timeSource.now(),
            lastMatchedAt = timeSource.now()
        )
        persistAlbumAndMissingTracks(confirmed, lookup)
    }

    override suspend fun addMissingTracks(albumId: String, providerTrackIds: List<String>): Result<Int> = runCatching {
        database.withTransaction {
            val album = albumDao.get(albumId) ?: error("Album not found.")
            val toAdd = albumMissingTrackDao.getForAlbum(albumId).filter { it.providerTrackId in providerTrackIds }
            if (toAdd.isEmpty()) return@withTransaction 0
            toAdd.forEach { track ->
                val id = UUID.randomUUID().toString()
                songDao.insertSongWithStats(
                    song = SongEntity(
                        id = id,
                        rankingSubjectId = id,
                        externalId = track.providerTrackId,
                        sourceType = MusicSourceType.ITUNES.name,
                        title = track.title,
                        artist = album.artist,
                        album = album.title,
                        artworkUrl = track.artworkUrl ?: album.artworkUrl,
                        createdAt = timeSource.now()
                    ),
                    stats = RankingSubjectEntity(
                        id = id,
                        sourceType = MusicSourceType.ITUNES.name,
                        externalId = track.providerTrackId,
                        normalizedTitle = track.title.trim().lowercase(),
                        normalizedArtist = album.artist.trim().lowercase()
                    )
                )
            }
            albumMissingTrackDao.delete(albumId, toAdd.map { it.providerTrackId })
            toAdd.size
        }
    }

    override suspend fun refreshMetadata(albumId: String): Result<Unit> = runCatching {
        val album = albumDao.get(albumId) ?: error("Album not found.")
        val collectionId = album.providerCollectionId
        if (collectionId != null) {
            // forceRefresh = true: this is the one action that must actually bypass
            // the provider's TTL cache - every other caller is fine reusing a recent
            // lookup, but "Refresh metadata" that silently replays a cached snapshot
            // isn't a refresh at all.
            val lookup = albumMetadataProvider.lookupRelease(collectionId, forceRefresh = true).getOrThrow()
            val updated = album.copy(
                providerTrackCount = lookup.trackCount ?: album.providerTrackCount,
                artworkUrl = lookup.artworkUrl ?: album.artworkUrl,
                lastMatchAttemptAt = timeSource.now(),
                lastMatchedAt = timeSource.now()
            )
            persistAlbumAndMissingTracks(updated, lookup)
        } else {
            // No confirmed/matched release yet - an explicit refresh bypasses the
            // normal PENDING backoff rather than making the user wait for it.
            attemptMatch(album)
        }
    }

    override suspend fun retryPendingMatches(): Result<Unit> = runCatching {
        if (!settingsRepository.observeSettings().first().metadataRetrievalEnabled) return@runCatching
        matchPendingAlbums(albumDao.getByMatchStatus(AlbumMatchStatus.PENDING.name))
    }

    override suspend fun searchReleaseCandidates(albumId: String): Result<List<AlbumReleaseCandidate>> = runCatching {
        val album = albumDao.get(albumId) ?: error("Album not found.")
        val candidates = albumMetadataProvider.searchReleases(album.artist, album.title).getOrThrow()
        matchingEngine.rankCandidates(album.title, album.artist, ownedTrackTitles(album), candidates)
    }

    private suspend fun discoverAndMatch(groupings: List<DiscoveredGrouping>) {
        val existingIds = albumDao.getAll().map { it.id }.toSet()
        val newAlbums = groupings
            .filter { it.id !in existingIds }
            .map { grouping ->
                AlbumEntity(
                    id = grouping.id,
                    title = grouping.title,
                    artist = grouping.artist,
                    normalizedTitle = grouping.normalizedTitle,
                    normalizedArtist = grouping.normalizedArtist,
                    createdAt = timeSource.now()
                )
            }
        if (newAlbums.isNotEmpty()) {
            database.withTransaction { albumDao.insertAll(newAlbums) }
        }
        if (!settingsRepository.observeSettings().first().metadataRetrievalEnabled) return
        matchPendingAlbums(albumDao.getByMatchStatus(AlbumMatchStatus.PENDING.name))
    }

    private suspend fun matchPendingAlbums(candidates: List<AlbumEntity>) {
        val now = timeSource.now()
        val eligible = candidates.filter { (it.lastMatchAttemptAt ?: 0L) + MATCH_ATTEMPT_BACKOFF_MILLIS <= now }
        // Spaced out rather than fired back-to-back: iTunes' informal per-IP rate
        // limit (~20 req/min) can't absorb a burst of one-or-two requests per album
        // for an entire library at once - e.g. on first sync of a real collection.
        eligible.forEachIndexed { index, album ->
            if (index > 0) delay(matchAttemptSpacingMillis)
            attemptMatch(album)
        }
    }

    private suspend fun attemptMatch(album: AlbumEntity) {
        if (!matchesInFlight.add(album.id)) return // already being matched by another in-flight call
        try {
            database.withTransaction {
                albumDao.insert(album.copy(lastMatchAttemptAt = timeSource.now()))
            }
            val ownedTitles = ownedTrackTitles(album)
            val candidates = albumMetadataProvider.searchReleases(album.artist, album.title).getOrNull()
                ?: return // provider unavailable - stays PENDING, lastMatchAttemptAt already recorded above
            val outcome = matchingEngine.classifyMatch(album.title, album.artist, ownedTitles, candidates)

            when (outcome.status) {
                AlbumMatchStatus.NO_MATCH -> persistAlbumAndMissingTracks(
                    album.copy(
                        matchStatus = AlbumMatchStatus.NO_MATCH.name,
                        matchConfidence = outcome.confidence,
                        lastMatchAttemptAt = timeSource.now()
                    ),
                    lookup = null
                )

                AlbumMatchStatus.AUTO_MATCHED, AlbumMatchStatus.NEEDS_REVIEW -> {
                    val candidate = outcome.bestCandidate ?: return
                    val updated = album.copy(
                        providerCollectionId = candidate.collectionId,
                        providerTrackCount = candidate.trackCount,
                        artworkUrl = candidate.artworkUrl ?: album.artworkUrl,
                        matchStatus = outcome.status.name,
                        matchConfidence = outcome.confidence,
                        lastMatchAttemptAt = timeSource.now(),
                        lastMatchedAt = timeSource.now()
                    )
                    val lookup = if (outcome.status == AlbumMatchStatus.AUTO_MATCHED) {
                        albumMetadataProvider.lookupRelease(candidate.collectionId).getOrNull()
                    } else {
                        null
                    }
                    persistAlbumAndMissingTracks(updated, lookup)
                }

                else -> Unit
            }
        } finally {
            matchesInFlight.remove(album.id)
        }
    }

    /**
     * Writes the album row and (when [lookup] is known) its missing-track diff in a
     * single transaction. Both used to be two separate transactions with a real gap
     * between them - a process death in that window left an AUTO_MATCHED/CONFIRMED
     * album with stale or absent missing-track rows and nothing to self-heal it,
     * since only PENDING albums are ever auto-(re)matched. [lookup] is always
     * resolved (a suspending network call) before this is called, so the transaction
     * itself never spans a network round trip.
     */
    private suspend fun persistAlbumAndMissingTracks(album: AlbumEntity, lookup: AlbumReleaseLookup?) {
        val finalAlbum = if (lookup?.trackCount != null) album.copy(providerTrackCount = lookup.trackCount) else album
        val missing = lookup?.let { matchingEngine.missingTracks(ownedTrackTitles(finalAlbum), it) }
        database.withTransaction {
            albumDao.insert(finalAlbum)
            if (lookup != null) {
                albumMissingTrackDao.clearForAlbum(finalAlbum.id)
                albumMissingTrackDao.insertAll(
                    missing.orEmpty().map { track ->
                        AlbumMissingTrackEntity(
                            albumId = finalAlbum.id,
                            providerTrackId = track.trackId,
                            title = track.title,
                            trackNumber = track.trackNumber,
                            artworkUrl = track.artworkUrl
                        )
                    }
                )
            }
        }
    }

    private suspend fun ownedTrackTitles(album: AlbumEntity): List<String> =
        songDao.getSongsWithStats()
            .filter {
                it.song.artist.trim().lowercase() == album.normalizedArtist &&
                    it.song.album.trim().lowercase() == album.normalizedTitle
            }
            .map { it.song.title }

    private fun groupingIdFor(row: SongWithStatsEntity): String =
        normalizedAlbumId(row.song.artist.trim().lowercase(), row.song.album.trim().lowercase())

    private fun deriveGroupings(rows: List<SongWithStatsEntity>): List<DiscoveredGrouping> =
        rows
            .filter { it.song.album.isNotBlank() && it.song.artist.isNotBlank() }
            .groupBy { groupingIdFor(it) }
            .map { (id, groupRows) ->
                val first = groupRows.first()
                DiscoveredGrouping(
                    id = id,
                    title = first.song.album.trim(),
                    artist = first.song.artist.trim(),
                    normalizedTitle = first.song.album.trim().lowercase(),
                    normalizedArtist = first.song.artist.trim().lowercase()
                )
            }

    private data class DiscoveredGrouping(
        val id: String,
        val title: String,
        val artist: String,
        val normalizedTitle: String,
        val normalizedArtist: String
    )

    private companion object {
        const val TAG = "DefaultAlbumRepository"
        const val MATCH_ATTEMPT_BACKOFF_MILLIS = 60_000L

        // Worst case an eligible album costs 4 requests: the term search, the
        // dedicated artist-id search and the artist-discography lookup
        // ItunesAlbumMetadataProvider always does alongside it (Search's relevance
        // ranking can omit an artist's own album from its results entirely, or even
        // the artist itself for a short/generic name), and the release lookup on an
        // auto-match. Spacing attempts this far apart caps that at 5 albums/min,
        // safely under iTunes' informal ~20 req/min/IP limit even if every album in
        // the batch auto-matches.
        const val MATCH_ATTEMPT_SPACING_MILLIS = 12_000L
    }
}
