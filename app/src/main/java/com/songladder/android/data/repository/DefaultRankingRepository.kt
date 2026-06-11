package com.songladder.android.data.repository

import com.songladder.android.data.local.AppStatsDao
import com.songladder.android.data.local.AppStatsEntity
import com.songladder.android.data.local.RankingStatsEntity
import com.songladder.android.data.local.SongDao
import com.songladder.android.data.local.toDomain
import com.songladder.android.domain.engine.EloMatchupEngine
import com.songladder.android.domain.model.AppStats
import com.songladder.android.domain.repository.RankingRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DefaultRankingRepository(
    private val songDao: SongDao,
    private val matchupEngine: EloMatchupEngine,
    private val appStatsDao: AppStatsDao? = null
) : RankingRepository {
    override fun observeStats(): Flow<AppStats> {
        val dao = requireNotNull(appStatsDao) { "AppStatsDao is required for observeStats." }
        return dao.observeAppStats().map { it.toDomain() }
    }

    override suspend fun recordBattle(winnerId: String, loserId: String): Result<Unit> = runCatching {
        val winner = songDao.getSongWithStats(winnerId)?.toDomain() ?: error("Winner not found.")
        val loser = songDao.getSongWithStats(loserId)?.toDomain() ?: error("Loser not found.")
        val (winnerUpdate, loserUpdate) = matchupEngine.updateRatings(winner, loser)

        songDao.updateRankingStats(
            RankingStatsEntity(
                songId = winnerUpdate.id,
                rating = winnerUpdate.rating,
                wins = winnerUpdate.wins,
                losses = winnerUpdate.losses,
                skips = winnerUpdate.skips,
                lastRankedAt = winnerUpdate.lastRankedAt
            )
        )
        songDao.updateRankingStats(
            RankingStatsEntity(
                songId = loserUpdate.id,
                rating = loserUpdate.rating,
                wins = loserUpdate.wins,
                losses = loserUpdate.losses,
                skips = loserUpdate.skips,
                lastRankedAt = loserUpdate.lastRankedAt
            )
        )

        appStatsDao?.let { dao ->
            val current = dao.getAppStats() ?: AppStatsEntity()
            dao.upsert(current.copy(matchCount = current.matchCount + 1))
        }
    }

    override suspend fun recordSkip(songId: String) {
        val song = songDao.getSongWithStats(songId) ?: return
        songDao.updateRankingStats(song.stats.copy(skips = song.stats.skips + 1))
        appStatsDao?.let { dao ->
            val current = dao.getAppStats() ?: AppStatsEntity()
            dao.upsert(current.copy(skipCount = current.skipCount + 1))
        }
    }
}
