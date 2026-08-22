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
    suspend fun insertRankingSubject(subject: RankingSubjectEntity)

    @Transaction
    suspend fun insertSongWithStats(song: SongEntity, stats: RankingSubjectEntity) {
        insertRankingSubject(stats)
        insertSong(song)
    }

    @Update
    suspend fun updateRankingSubject(subject: RankingSubjectEntity)

    @Query("DELETE FROM songs WHERE id = :songId")
    suspend fun deleteSong(songId: String)

    @Query("DELETE FROM songs")
    suspend fun clearSongs()
}

@Dao
interface RankingSubjectDao {
    @Query("SELECT * FROM ranking_subjects ORDER BY id")
    suspend fun getAll(): List<RankingSubjectEntity>

    @Query("SELECT * FROM ranking_subjects WHERE tombstoneDeletedAt IS NULL ORDER BY id")
    fun observeAll(): Flow<List<RankingSubjectEntity>>

    @Query("SELECT * FROM ranking_subjects ORDER BY id")
    fun observeAllIncludingDeleted(): Flow<List<RankingSubjectEntity>>

    @Query("SELECT * FROM ranking_subjects WHERE tombstoneDeletedAt IS NOT NULL ORDER BY tombstoneDeletedAt DESC")
    fun observeDeleted(): Flow<List<RankingSubjectEntity>>

    @Query("SELECT * FROM ranking_subjects WHERE id = :subjectId")
    suspend fun get(subjectId: String): RankingSubjectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(subject: RankingSubjectEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(subjects: List<RankingSubjectEntity>)

    @Update
    suspend fun update(subject: RankingSubjectEntity)

    @Query("DELETE FROM ranking_subjects")
    suspend fun clearAll()
}

@Dao
interface MatchupEventDao {
    @Query("SELECT COALESCE(MAX(sequenceId), 0) + 1 FROM matchup_events")
    suspend fun nextSequenceId(): Long

    @Query("SELECT * FROM matchup_events ORDER BY sequenceId")
    suspend fun getAll(): List<MatchupEventEntity>

    @Query("SELECT * FROM matchup_events ORDER BY sequenceId")
    fun observeAll(): Flow<List<MatchupEventEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: MatchupEventEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<MatchupEventEntity>)

    @Query("DELETE FROM matchup_events WHERE sequenceId = :sequenceId")
    suspend fun delete(sequenceId: Long)

    @Query("DELETE FROM matchup_events WHERE firstSubjectId = :subjectId OR secondSubjectId = :subjectId")
    suspend fun deleteForSubject(subjectId: String)

    @Query("DELETE FROM matchup_events")
    suspend fun clearAll()
}

@Dao
interface RankingSettingsDao {
    @Query("SELECT * FROM ranking_settings WHERE id = 0")
    fun observe(): Flow<RankingSettingsEntity?>

    @Query("SELECT * FROM ranking_settings WHERE id = 0")
    suspend fun get(): RankingSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(settings: RankingSettingsEntity)
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

@Dao
interface SuggestionDismissalDao {
    @Query("SELECT * FROM suggestion_dismissals")
    fun observeAll(): Flow<List<SuggestionDismissalEntity>>

    @Query("SELECT * FROM suggestion_dismissals WHERE subjectId = :subjectId")
    suspend fun get(subjectId: String): SuggestionDismissalEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(dismissal: SuggestionDismissalEntity)

    @Query("DELETE FROM suggestion_dismissals WHERE subjectId = :subjectId")
    suspend fun delete(subjectId: String)

    @Query("DELETE FROM suggestion_dismissals")
    suspend fun clearAll()
}
