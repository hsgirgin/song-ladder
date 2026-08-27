package com.songladder.android.data.repository

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import java.util.UUID

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
    private val repositoryScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : AlbumRepository {

    init {
        // Discovers new (artist, album) groupings and attempts an initial match for
        // them as soon as songs carrying that grouping exist - this alone satisfies
        // "start metadata matching automatically after songs are added" with no hook
        // needed in DefaultSongRepository/DefaultImportRepository. distinctUntilChanged
        // means a song's score/rating changing (which doesn't affect its grouping)
        // never re-triggers this.
        songDao.observeSongsWithStats()
            .map(::deriveGroupings)
            .distinctUntilChanged()
            .onEach { groupings -> discoverAndMatch(groupings) }
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
        database.withTransaction { albumDao.insert(confirmed) }
        writeMissingTracks(confirmed, lookup)
    }

    override suspend fun addMissingTracks(albumId: String, providerTrackIds: List<String>): Result<Int> = runCatching {
        val album = albumDao.get(albumId) ?: error("Album not found.")
        val toAdd = albumMissingTrackDao.getForAlbum(albumId).filter { it.providerTrackId in providerTrackIds }
        if (toAdd.isEmpty()) return@runCatching 0
        database.withTransaction {
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
        }
        toAdd.size
    }

    override suspend fun refreshMetadata(albumId: String): Result<Unit> = runCatching {
        val album = albumDao.get(albumId) ?: error("Album not found.")
        val collectionId = album.providerCollectionId
        if (collectionId != null) {
            val lookup = albumMetadataProvider.lookupRelease(collectionId).getOrThrow()
            val updated = album.copy(
                providerTrackCount = lookup.trackCount ?: album.providerTrackCount,
                artworkUrl = lookup.artworkUrl ?: album.artworkUrl,
                lastMatchAttemptAt = timeSource.now(),
                lastMatchedAt = timeSource.now()
            )
            database.withTransaction { albumDao.insert(updated) }
            writeMissingTracks(updated, lookup)
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
        candidates
            .filter { (it.lastMatchAttemptAt ?: 0L) + MATCH_ATTEMPT_BACKOFF_MILLIS <= now }
            .forEach { attemptMatch(it) }
    }

    private suspend fun attemptMatch(album: AlbumEntity) {
        database.withTransaction {
            albumDao.insert(album.copy(lastMatchAttemptAt = timeSource.now()))
        }
        val ownedTitles = ownedTrackTitles(album)
        val candidates = albumMetadataProvider.searchReleases(album.artist, album.title).getOrNull()
            ?: return // provider unavailable - stays PENDING, lastMatchAttemptAt already recorded above
        val outcome = matchingEngine.classifyMatch(album.title, album.artist, ownedTitles, candidates)

        when (outcome.status) {
            AlbumMatchStatus.NO_MATCH -> database.withTransaction {
                albumDao.insert(
                    album.copy(
                        matchStatus = AlbumMatchStatus.NO_MATCH.name,
                        matchConfidence = outcome.confidence,
                        lastMatchAttemptAt = timeSource.now()
                    )
                )
            }

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
                database.withTransaction { albumDao.insert(updated) }
                if (outcome.status == AlbumMatchStatus.AUTO_MATCHED) {
                    val lookup = albumMetadataProvider.lookupRelease(candidate.collectionId).getOrNull()
                    if (lookup != null) writeMissingTracks(updated, lookup)
                }
            }

            else -> Unit
        }
    }

    private suspend fun writeMissingTracks(album: AlbumEntity, lookup: AlbumReleaseLookup) {
        val missing = matchingEngine.missingTracks(ownedTrackTitles(album), lookup)
        database.withTransaction {
            albumMissingTrackDao.clearForAlbum(album.id)
            albumMissingTrackDao.insertAll(
                missing.map { track ->
                    AlbumMissingTrackEntity(
                        albumId = album.id,
                        providerTrackId = track.trackId,
                        title = track.title,
                        trackNumber = track.trackNumber,
                        artworkUrl = track.artworkUrl
                    )
                }
            )
            if (lookup.trackCount != null && lookup.trackCount != album.providerTrackCount) {
                albumDao.insert(album.copy(providerTrackCount = lookup.trackCount))
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
        const val MATCH_ATTEMPT_BACKOFF_MILLIS = 60_000L
    }
}
