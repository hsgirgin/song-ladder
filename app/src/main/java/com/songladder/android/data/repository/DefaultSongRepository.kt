package com.songladder.android.data.repository

import com.songladder.android.data.local.AppStatsDao
import com.songladder.android.data.local.AppStatsEntity
import com.songladder.android.data.local.RankingStatsEntity
import com.songladder.android.data.local.SongDao
import com.songladder.android.data.local.toDomain
import com.songladder.android.data.local.toSongEntity
import com.songladder.android.domain.model.Song
import com.songladder.android.domain.model.SongInput
import com.songladder.android.domain.repository.SongRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DefaultSongRepository(
    private val songDao: SongDao,
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
        val songEntity = input.toSongEntity()
        songDao.insertSong(songEntity)
        songDao.insertRankingStats(RankingStatsEntity(songId = songEntity.id))
    }

    override suspend fun removeSong(songId: String) {
        songDao.deleteSong(songId)
    }

    override suspend fun resetLibrary() {
        songDao.clearSongs()
        appStatsDao?.upsert(AppStatsEntity())
    }
}
