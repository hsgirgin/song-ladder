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
import com.songladder.android.domain.model.seedEloForScore
import com.songladder.android.domain.repository.SongRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DefaultSongRepository(
    private val database: SongLadderDatabase,
    private val songDao: SongDao,
    private val rankingSubjectDao: RankingSubjectDao,
    private val matchupEventDao: MatchupEventDao? = null,
    private val appStatsDao: AppStatsDao? = null
) : SongRepository {
    override fun observeSongs(): Flow<List<Song>> {
        return songDao.observeSongsWithStats().map { rows ->
            rows.map { it.toDomain() }.sortedWith(compareByDescending<Song> { it.rating }.thenBy { it.title })
        }
    }

    override suspend fun addSong(input: SongInput): Result<Unit> = runCatching {
        require(input.title.isNotBlank()) { "Song title is required." }
        require(input.artist.isNotBlank()) { "Artist is required." }
        val (songEntity, subjectEntity) = input.toSongAndRankingSubjectEntities()
        songDao.insertSongWithStats(
            song = songEntity,
            stats = subjectEntity
        )
    }

    override suspend fun removeSong(songId: String): Result<Unit> = runCatching {
        database.withTransaction {
            val song = songDao.getSongWithStats(songId) ?: error("Song not found.")
            val subject = song.stats
            rankingSubjectDao.update(
                subject.copy(
                    tombstoneDeletedAt = System.currentTimeMillis(),
                    tombstoneSourceType = subject.sourceType,
                    tombstoneExternalId = subject.externalId,
                    tombstoneScoreTenths = subject.scoreTenths,
                    tombstoneSeedElo = seedEloForScore(subject.scoreTenths)
                )
            )
            songDao.deleteSong(songId)
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
