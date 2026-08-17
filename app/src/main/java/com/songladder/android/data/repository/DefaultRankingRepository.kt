package com.songladder.android.data.repository

import androidx.room.withTransaction
import com.songladder.android.data.local.AppStatsDao
import com.songladder.android.data.local.AppStatsEntity
import com.songladder.android.data.local.MatchupEventDao
import com.songladder.android.data.local.MatchupEventEntity
import com.songladder.android.data.local.RankingSubjectDao
import com.songladder.android.data.local.SongDao
import com.songladder.android.data.local.SongLadderDatabase
import com.songladder.android.data.local.toDomain
import com.songladder.android.data.local.toEntity
import com.songladder.android.domain.engine.EloMatchupEngine
import com.songladder.android.domain.model.AppStats
import com.songladder.android.domain.model.MatchupOutcome
import com.songladder.android.domain.repository.RankingRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DefaultRankingRepository(
    private val database: SongLadderDatabase,
    private val songDao: SongDao,
    private val matchupEngine: EloMatchupEngine,
    private val rankingSubjectDao: RankingSubjectDao,
    private val matchupEventDao: MatchupEventDao,
    private val appStatsDao: AppStatsDao? = null
) : RankingRepository {
    override fun observeStats(): Flow<AppStats> {
        val dao = requireNotNull(appStatsDao) { "AppStatsDao is required for observeStats." }
        return dao.observeAppStats().map { it.toDomain() }
    }

    override suspend fun recordBattle(winnerId: String, loserId: String): Result<Unit> = runCatching {
        database.withTransaction {
            require(winnerId != loserId) { "A matchup requires two distinct songs." }
            val winnerSubject = songDao.getSongWithStats(winnerId)?.stats
                ?: error("Winner subject not found.")
            val loserSubject = songDao.getSongWithStats(loserId)?.stats
                ?: error("Loser subject not found.")
            val update = matchupEngine.updateRatings(winnerSubject.toDomain(), loserSubject.toDomain())
            rankingSubjectDao.update(
                update.winner.toEntity()
            )
            matchupEventDao.insert(
                MatchupEventEntity(
                    sequenceId = matchupEventDao.nextSequenceId(),
                    occurredAt = update.ratedAt,
                    firstSubjectId = winnerSubject.id,
                    secondSubjectId = loserSubject.id,
                    outcome = MatchupOutcome.WIN.name,
                    winnerSubjectId = winnerSubject.id,
                    loserSubjectId = loserSubject.id,
                    winnerEffectiveK = update.winnerEffectiveK,
                    loserEffectiveK = update.loserEffectiveK
                )
            )
            rankingSubjectDao.update(
                update.loser.toEntity()
            )

            appStatsDao?.let { dao ->
                val current = dao.getAppStats() ?: AppStatsEntity()
                dao.upsert(current.copy(matchCount = current.matchCount + 1))
            }
        }
    }

    override suspend fun recordSkip(songIds: List<String>): Result<Unit> = runCatching {
        database.withTransaction {
            val uniqueSongIds = songIds.distinct()
            require(uniqueSongIds.size == 2) { "A skipped matchup requires two distinct songs." }
            val subjects = uniqueSongIds.map { songId ->
                songDao.getSongWithStats(songId)?.stats ?: error("Song not found.")
            }
            subjects.forEach { subject -> rankingSubjectDao.update(subject.copy(skips = subject.skips + 1)) }
            matchupEventDao.insert(
                MatchupEventEntity(
                    sequenceId = matchupEventDao.nextSequenceId(),
                    occurredAt = System.currentTimeMillis(),
                    firstSubjectId = subjects[0].id,
                    secondSubjectId = subjects[1].id,
                    outcome = MatchupOutcome.SKIP.name
                )
            )
            appStatsDao?.let { dao ->
                val current = dao.getAppStats() ?: AppStatsEntity()
                dao.upsert(current.copy(skipCount = current.skipCount + 1))
            }
        }
    }
}
