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
        SuggestionDismissalEntity::class,
        AlbumEntity::class,
        AlbumTrackExclusionEntity::class,
        AlbumReleaseTrackEntity::class
    ],
    version = 4,
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
    abstract fun albumDao(): AlbumDao
    abstract fun albumTrackExclusionDao(): AlbumTrackExclusionDao
    abstract fun albumReleaseTrackDao(): AlbumReleaseTrackDao

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

        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `albums` (
                        `id` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `artist` TEXT NOT NULL,
                        `artworkUrl` TEXT,
                        `normalizedTitle` TEXT NOT NULL,
                        `normalizedArtist` TEXT NOT NULL,
                        `providerSourceType` TEXT NOT NULL,
                        `providerCollectionId` TEXT,
                        `providerTrackCount` INTEGER,
                        `matchStatus` TEXT NOT NULL,
                        `matchConfidence` REAL,
                        `createdAt` INTEGER NOT NULL,
                        `lastMatchAttemptAt` INTEGER,
                        `lastMatchedAt` INTEGER,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_albums_matchStatus` ON `albums` (`matchStatus`)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `album_track_exclusions` (
                        `songId` TEXT NOT NULL,
                        `albumId` TEXT NOT NULL,
                        `excludedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`songId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_album_track_exclusions_albumId` " +
                        "ON `album_track_exclusions` (`albumId`)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `album_missing_tracks` (
                        `albumId` TEXT NOT NULL,
                        `providerTrackId` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `trackNumber` INTEGER,
                        `artworkUrl` TEXT,
                        PRIMARY KEY(`albumId`, `providerTrackId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "ALTER TABLE `ranking_settings` ADD COLUMN `metadataRetrievalEnabled` " +
                        "INTEGER NOT NULL DEFAULT 1"
                )
            }
        }

        // Same rows, same primary key, same columns - only what the table represents
        // changes (was "tracks missing from this album", now "every track on this
        // album's matched release"), so a plain rename preserves existing missing-track
        // rows as-is rather than needing a rebuild.
        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `album_missing_tracks` RENAME TO `album_release_tracks`")
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
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { instance = it }
            }
        }
    }
}
