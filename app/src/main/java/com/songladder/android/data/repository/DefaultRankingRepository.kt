package com.songladder.android.data.repository

import androidx.room.withTransaction
import com.songladder.android.data.local.AppStatsDao
import com.songladder.android.data.local.AppStatsEntity
import com.songladder.android.data.local.RankingStatsEntity
import com.songladder.android.data.local.SongDao
import com.songladder.android.data.local.SongLadderDatabase
import com.songladder.android.data.local.toDomain
import com.songladder.android.domain.engine.EloMatchupEngine
import com.songladder.android.domain.model.AppStats
import com.songladder.android.domain.repository.RankingRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DefaultRankingRepository(
    private val database: SongLadderDatabase,
    private val songDao: SongDao,
    private val matchupEngine: EloMatchupEngine,
    private val appStatsDao: AppStatsDao? = null
) : RankingRepository {
    override fun observeStats(): Flow<AppStats> {
        val dao = requireNotNull(appStatsDao) { "AppStatsDao is required for observeStats." }
        return dao.observeAppStats().map { it.toDomain() }
    }

    override suspend fun recordBattle(winnerId: String, loserId: String): Result<Unit> = runCatching {
        database.withTransaction {
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
    }

    override suspend fun recordSkip(songIds: List<String>): Result<Unit> = runCatching {
        database.withTransaction {
            val uniqueSongIds = songIds.distinct()
            require(uniqueSongIds.isNotEmpty()) { "No songs to skip." }
            uniqueSongIds.forEach { songId ->
                val song = songDao.getSongWithStats(songId) ?: error("Song not found.")
                songDao.updateRankingStats(song.stats.copy(skips = song.stats.skips + 1))
            }
            appStatsDao?.let { dao ->
                val current = dao.getAppStats() ?: AppStatsEntity()
                dao.upsert(current.copy(skipCount = current.skipCount + 1))
            }
        }
    }
}
