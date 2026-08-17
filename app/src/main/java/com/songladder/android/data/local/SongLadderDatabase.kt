package com.songladder.android.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        SongEntity::class,
        RankingSubjectEntity::class,
        MatchupEventEntity::class,
        RankingSettingsEntity::class,
        AppStatsEntity::class,
        ImportBatchEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class SongLadderDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun rankingSubjectDao(): RankingSubjectDao
    abstract fun matchupEventDao(): MatchupEventDao
    abstract fun rankingSettingsDao(): RankingSettingsDao
    abstract fun appStatsDao(): AppStatsDao
    abstract fun importBatchDao(): ImportBatchDao

    companion object {
        @Volatile
        private var instance: SongLadderDatabase? = null

        fun getDatabase(context: Context): SongLadderDatabase {
            return instance ?: synchronized(this) {
                Room.databaseBuilder(
                    context,
                    SongLadderDatabase::class.java,
                    "song_ladder.db"
                ).build().also { instance = it }
            }
        }
    }
}
