package com.songladder.android.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SongDao {
    @Transaction
    @Query("SELECT * FROM songs ORDER BY createdAt DESC")
    fun observeSongsWithStats(): Flow<List<SongWithStatsEntity>>

    @Transaction
    @Query("SELECT * FROM songs WHERE id = :songId")
    suspend fun getSongWithStats(songId: String): SongWithStatsEntity?

    @Transaction
    @Query("SELECT * FROM songs ORDER BY createdAt DESC")
    suspend fun getSongsWithStats(): List<SongWithStatsEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSong(song: SongEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRankingStats(stats: RankingStatsEntity)

    @Update
    suspend fun updateRankingStats(stats: RankingStatsEntity)

    @Query("DELETE FROM songs WHERE id = :songId")
    suspend fun deleteSong(songId: String)

    @Query("DELETE FROM songs")
    suspend fun clearSongs()
}

@Dao
interface AppStatsDao {
    @Query("SELECT * FROM app_stats WHERE id = 0")
    fun observeAppStats(): Flow<AppStatsEntity?>

    @Query("SELECT * FROM app_stats WHERE id = 0")
    suspend fun getAppStats(): AppStatsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(appStats: AppStatsEntity)
}

@Dao
interface ImportBatchDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(batch: ImportBatchEntity)
}
