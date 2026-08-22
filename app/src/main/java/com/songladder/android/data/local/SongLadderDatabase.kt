package com.songladder.android.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        SongEntity::class,
        RankingSubjectEntity::class,
        MatchupEventEntity::class,
        RankingSettingsEntity::class,
        AppStatsEntity::class,
        ImportBatchEntity::class,
        SuggestionDismissalEntity::class
    ],
    version = 2,
    exportSchema = true
)
abstract class SongLadderDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun rankingSubjectDao(): RankingSubjectDao
    abstract fun matchupEventDao(): MatchupEventDao
    abstract fun rankingSettingsDao(): RankingSettingsDao
    abstract fun appStatsDao(): AppStatsDao
    abstract fun importBatchDao(): ImportBatchDao
    abstract fun suggestionDismissalDao(): SuggestionDismissalDao

    companion object {
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `suggestion_dismissals` (
                        `subjectId` TEXT NOT NULL,
                        `dismissedAtSequenceId` INTEGER NOT NULL,
                        `dismissedScoreTenths` INTEGER NOT NULL,
                        PRIMARY KEY(`subjectId`)
                    )
                    """.trimIndent()
                )
            }
        }

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
    }
}
