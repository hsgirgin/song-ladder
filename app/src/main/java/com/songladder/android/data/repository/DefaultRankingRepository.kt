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
import com.songladder.android.domain.model.MatchupEvent
import com.songladder.android.domain.model.RankingHistoryDeletionResult
import com.songladder.android.domain.model.RankingSubject
import com.songladder.android.domain.model.ResponsivenessEpoch
import com.songladder.android.domain.model.ScoreSaveResult
import com.songladder.android.domain.model.TimeSource
import com.songladder.android.domain.model.scoreFirstComparator
import com.songladder.android.domain.model.seedEloForScore
import com.songladder.android.domain.model.validateScoreTenths
import com.songladder.android.domain.repository.RankingRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DefaultRankingRepository(
    private val database: SongLadderDatabase,
    private val songDao: SongDao,
    private val matchupEngine: EloMatchupEngine,
    private val rankingSubjectDao: RankingSubjectDao,
    private val matchupEventDao: MatchupEventDao,
    private val appStatsDao: AppStatsDao? = null,
    private val timeSource: TimeSource = TimeSource { System.currentTimeMillis() }
) : RankingRepository {
    override fun observeStats(): Flow<AppStats> {
        val dao = requireNotNull(appStatsDao) { "AppStatsDao is required for observeStats." }
        return dao.observeAppStats().map { it.toDomain() }
    }

    override fun observeMatchupEvents(): Flow<List<MatchupEvent>> =
        matchupEventDao.observeAll().map { events -> events.map { it.toDomain() } }

    override suspend fun recordBattle(winnerId: String, loserId: String): Result<Unit> = runCatching {
        database.withTransaction {
            require(winnerId != loserId) { "A matchup requires two distinct songs." }
            val winnerSubject = songDao.getSongWithStats(winnerId)?.stats
                ?: error("Winner subject not found.")
            val loserSubject = songDao.getSongWithStats(loserId)?.stats
                ?: error("Loser subject not found.")
            val update = matchupEngine.updateRatings(
                winner = winnerSubject.toDomain(),
                loser = loserSubject.toDomain(),
                ratedAt = nextEventTimestamp()
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
            rebuildCaches()
        }
    }

    override suspend fun recordSkip(songIds: List<String>): Result<Unit> = runCatching {
        database.withTransaction {
            val uniqueSongIds = songIds.distinct()
            require(uniqueSongIds.size == 2) { "A skipped matchup requires two distinct songs." }
            val subjects = uniqueSongIds.map { songId ->
                songDao.getSongWithStats(songId)?.stats ?: error("Song not found.")
            }
            matchupEventDao.insert(
                MatchupEventEntity(
                    sequenceId = matchupEventDao.nextSequenceId(),
                    occurredAt = nextEventTimestamp(),
                    firstSubjectId = subjects[0].id,
                    secondSubjectId = subjects[1].id,
                    outcome = MatchupOutcome.SKIP.name
                )
            )
            rebuildCaches()
        }
    }

    override suspend fun saveScore(songId: String, scoreTenths: Int): Result<ScoreSaveResult> = runCatching {
        validateScoreTenths(scoreTenths)
        database.withTransaction {
            val beforeOrder = currentSongOrder()
            val row = songDao.getSongWithStats(songId) ?: error("Song not found.")
            require(row.stats.tombstoneDeletedAt == null) { "Cannot score a deleted song." }
            val current = row.stats.toDomain()
            if (current.scoreTenths == scoreTenths) {
                return@withTransaction ScoreSaveResult(
                    songId = songId,
                    scoreTenths = scoreTenths,
                    visibleOrderChanged = false
                )
            }
            val epochSequence = matchupEventDao.getAll().maxOfOrNull { it.sequenceId } ?: 0L
            rankingSubjectDao.update(
                current.copy(
                    scoreTenths = scoreTenths,
                    elo = seedEloForScore(scoreTenths),
                    responsivenessEpoch = if (current.scoreTenths == null) {
                        ResponsivenessEpoch.NEW
                    } else {
                        ResponsivenessEpoch.EDITED
                    },
                    completedMatchupsInEpoch = 0,
                    responsivenessEpochSequence = epochSequence,
                    lastRatedAt = nextEventTimestamp()
                ).toEntity()
            )
            rebuildCaches()
            val afterOrder = currentSongOrder()
            ScoreSaveResult(
                songId = songId,
                scoreTenths = scoreTenths,
                visibleOrderChanged = beforeOrder != afterOrder
            )
        }
    }

    override suspend fun undoLastWinner(): Result<Boolean> = runCatching {
        database.withTransaction {
            val latestWinner = matchupEventDao.getAll()
                .lastOrNull { it.outcome == MatchupOutcome.WIN.name }
                ?: return@withTransaction false
            matchupEventDao.delete(latestWinner.sequenceId)
            rebuildCaches()
            true
        }
    }

    override suspend fun deleteRankingHistory(rankingSubjectId: String): Result<RankingHistoryDeletionResult> = runCatching {
        database.withTransaction {
            val events = matchupEventDao.getAll()
            val deletedEventCount = events.count {
                it.firstSubjectId == rankingSubjectId || it.secondSubjectId == rankingSubjectId
            }
            matchupEventDao.deleteForSubject(rankingSubjectId)
            rebuildCaches()
            RankingHistoryDeletionResult(
                rankingSubjectId = rankingSubjectId,
                deletedEventCount = deletedEventCount
            )
        }
    }

    override suspend fun deleteAllRankingHistory(): Result<RankingHistoryDeletionResult> = runCatching {
        database.withTransaction {
            val deletedEventCount = matchupEventDao.getAll().size
            matchupEventDao.clearAll()
            rebuildCaches()
            RankingHistoryDeletionResult(
                rankingSubjectId = null,
                deletedEventCount = deletedEventCount
            )
        }
    }

    private suspend fun rebuildCaches(): List<RankingSubject> {
        val subjects = rankingSubjectDao.getAll().map { it.toDomain() }
        val events = matchupEventDao.getAll().map { it.toDomain() }
        val replayed = matchupEngine.replay(subjects, events)
        replayed.forEach { rankingSubjectDao.update(it.toEntity()) }
        appStatsDao?.upsert(
            AppStatsEntity(
                matchCount = events.count { it.outcome == MatchupOutcome.WIN },
                skipCount = events.count { it.outcome == MatchupOutcome.SKIP }
            )
        )
        return replayed
    }

    private suspend fun currentSongOrder(): List<String> = songDao.getSongsWithStats()
        .map { it.toDomain() }
        .sortedWith(scoreFirstComparator())
        .map { it.id }

    private suspend fun nextEventTimestamp(): Long {
        val latest = matchupEventDao.getAll().maxOfOrNull { it.occurredAt } ?: Long.MIN_VALUE
        return maxOf(timeSource.now(), latest + 1L)
    }
}
