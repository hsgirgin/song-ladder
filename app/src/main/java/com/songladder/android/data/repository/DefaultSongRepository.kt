package com.songladder.android.data.repository

import androidx.room.withTransaction
import com.songladder.android.data.local.AppStatsDao
import com.songladder.android.data.local.AppStatsEntity
import com.songladder.android.data.local.MatchupEventDao
import com.songladder.android.data.local.RankingSubjectDao
import com.songladder.android.data.local.SongDao
import com.songladder.android.data.local.SongLadderDatabase
import com.songladder.android.data.local.toDomain
import com.songladder.android.data.local.toSongAndRankingSubjectEntities
import com.songladder.android.domain.model.Song
import com.songladder.android.domain.model.SongInput
import com.songladder.android.domain.model.TimeSource
import com.songladder.android.domain.model.scoreFirstComparator
import com.songladder.android.domain.model.seedEloForScore
import com.songladder.android.domain.repository.SongRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DefaultSongRepository(
    private val database: SongLadderDatabase,
    private val songDao: SongDao,
    private val rankingSubjectDao: RankingSubjectDao,
    private val matchupEventDao: MatchupEventDao? = null,
    private val appStatsDao: AppStatsDao? = null,
    private val timeSource: TimeSource = TimeSource { System.currentTimeMillis() }
) : SongRepository {
    override fun observeSongs(): Flow<List<Song>> {
        return songDao.observeSongsWithStats().map { rows ->
            rows.map { it.toDomain() }.sortedWith(scoreFirstComparator())
        }
    }

    override suspend fun addSong(input: SongInput): Result<Unit> = runCatching {
        require(input.title.isNotBlank()) { "Song title is required." }
        require(input.artist.isNotBlank()) { "Artist is required." }
        database.withTransaction {
            val normalizedTitle = input.title.trim()
            val normalizedArtist = input.artist.trim()
            require(
                songDao.getSongsWithStats().none {
                    it.song.title.trim().equals(normalizedTitle, ignoreCase = true) &&
                        it.song.artist.trim().equals(normalizedArtist, ignoreCase = true)
                }
            ) { "A song with this title and artist is already in the library." }
            val (songEntity, subjectEntity) = input.toSongAndRankingSubjectEntities()
            songDao.insertSongWithStats(
                song = songEntity,
                stats = subjectEntity
            )
        }
    }

    override suspend fun removeSong(songId: String): Result<Unit> = runCatching {
        database.withTransaction {
            val song = songDao.getSongWithStats(songId) ?: error("Song not found.")
            val subject = song.stats
            rankingSubjectDao.update(
                subject.copy(
                    tombstoneDeletedAt = timeSource.now(),
                    tombstoneSourceType = subject.sourceType,
                    tombstoneExternalId = subject.externalId,
                    tombstoneScoreTenths = subject.scoreTenths,
                    tombstoneSeedElo = seedEloForScore(subject.scoreTenths),
                    tombstoneSuppressedExternalId = null,
                    tombstoneSuppressedSourceType = null,
                    tombstoneSuppressedNormalizedTitle = null,
                    tombstoneSuppressedNormalizedArtist = null
                )
            )
            songDao.deleteSong(songId)
        }
    }

    override suspend fun restoreSong(input: SongInput, rankingSubjectId: String): Result<Unit> = runCatching {
        require(input.title.isNotBlank()) { "Song title is required." }
        require(input.artist.isNotBlank()) { "Artist is required." }
        database.withTransaction {
            val subject = rankingSubjectDao.get(rankingSubjectId)
                ?: error("Ranking history not found.")
            require(subject.tombstoneDeletedAt != null) { "Ranking history is not deleted." }
            require(
                songDao.getSongsWithStats().none {
                    it.song.title.trim().equals(input.title.trim(), ignoreCase = true) &&
                        it.song.artist.trim().equals(input.artist.trim(), ignoreCase = true)
                }
            ) { "A song with this title and artist is already in the library." }
            val (newSong, _) = input.toSongAndRankingSubjectEntities()
            rankingSubjectDao.update(
                subject.copy(
                    sourceType = input.sourceType.name,
                    externalId = input.externalId,
                    normalizedTitle = input.title.trim().lowercase(),
                    normalizedArtist = input.artist.trim().lowercase(),
                    tombstoneDeletedAt = null,
                    tombstoneSourceType = null,
                    tombstoneExternalId = null,
                    tombstoneScoreTenths = null,
                    tombstoneSeedElo = null,
                    tombstoneSuppressedExternalId = null,
                    tombstoneSuppressedSourceType = null,
                    tombstoneSuppressedNormalizedTitle = null,
                    tombstoneSuppressedNormalizedArtist = null
                )
            )
            songDao.insertSong(newSong.copy(rankingSubjectId = subject.id))
        }
    }

    override suspend fun resetLibrary(): Result<Unit> = runCatching {
        database.withTransaction {
            songDao.clearSongs()
            rankingSubjectDao.clearAll()
            matchupEventDao?.clearAll()
            appStatsDao?.upsert(AppStatsEntity())
        }
    }
}
