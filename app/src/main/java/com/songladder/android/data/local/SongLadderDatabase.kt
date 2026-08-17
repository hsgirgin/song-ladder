package com.songladder.android.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.migration.Migration
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
        ImportBatchEntity::class
    ],
    version = 2,
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
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
        }
    }
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE ranking_subjects ADD COLUMN responsivenessEpochSequence INTEGER NOT NULL DEFAULT 0"
        )
        db.execSQL(
            "ALTER TABLE ranking_subjects ADD COLUMN tombstoneSuppressedExternalId TEXT"
        )
        db.execSQL(
            "ALTER TABLE ranking_subjects ADD COLUMN tombstoneSuppressedSourceType TEXT"
        )
        db.execSQL(
            "ALTER TABLE ranking_subjects ADD COLUMN tombstoneSuppressedNormalizedTitle TEXT"
        )
        db.execSQL(
            "ALTER TABLE ranking_subjects ADD COLUMN tombstoneSuppressedNormalizedArtist TEXT"
        )
    }
}
