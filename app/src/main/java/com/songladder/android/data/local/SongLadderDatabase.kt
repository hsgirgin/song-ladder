package com.songladder.android.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [SongEntity::class, RankingStatsEntity::class, AppStatsEntity::class, ImportBatchEntity::class],
    version = 2,
    exportSchema = false
)
abstract class SongLadderDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
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
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE app_stats ADD COLUMN dailyGoalDate TEXT")
                database.execSQL("ALTER TABLE app_stats ADD COLUMN dailyMatchCount INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
