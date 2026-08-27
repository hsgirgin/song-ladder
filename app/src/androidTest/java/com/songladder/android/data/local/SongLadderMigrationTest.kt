package com.songladder.android.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Frozen pre-Slice-3 shape of `ranking_settings`, used by both historical database
 * snapshots below instead of the live [RankingSettingsEntity]. The live entity now
 * carries `metadataRetrievalEnabled`, which [SongLadderDatabase.MIGRATION_2_3] adds
 * via `ALTER TABLE`; reusing the live class for a "before" snapshot would create the
 * column twice and make that migration fail with a duplicate-column error.
 */
@Entity(tableName = "ranking_settings")
private data class RankingSettingsEntityV2(
    @PrimaryKey val id: Int = 0,
    val autoPlayMatchupPreviews: Boolean = true,
    val showTips: Boolean = true,
    val presentation: String = "GRID"
)

@Dao
private interface RankingSettingsDaoV2 {
    @Query("SELECT * FROM ranking_settings WHERE id = 0")
    suspend fun get(): RankingSettingsEntityV2?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(settings: RankingSettingsEntityV2)
}

/**
 * No schemas/1.json exists because exportSchema was false at version 1, so
 * MigrationTestHelper's asset-based schema lookup can't be used here. Instead,
 * this rebuilds the v1 schema from the original (unchanged) entity classes -
 * version 2 only adds a new table, it doesn't alter any v1 entity - opens a
 * real file-backed database at v1, then reopens the same file through the
 * real [SongLadderDatabase] with [SongLadderDatabase.MIGRATION_1_2] applied.
 */
@Database(
    entities = [
        SongEntity::class,
        RankingSubjectEntity::class,
        MatchupEventEntity::class,
        RankingSettingsEntityV2::class,
        AppStatsEntity::class,
        ImportBatchEntity::class
    ],
    version = 1,
    exportSchema = false
)
private abstract class SongLadderDatabaseV1 : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun rankingSubjectDao(): RankingSubjectDao
}

/**
 * Version 2 snapshot (post [SongLadderDatabase.MIGRATION_1_2], pre-albums) used to
 * verify [SongLadderDatabase.MIGRATION_2_3] in isolation, the same way
 * [SongLadderDatabaseV1] verifies [SongLadderDatabase.MIGRATION_1_2].
 */
@Database(
    entities = [
        SongEntity::class,
        RankingSubjectEntity::class,
        MatchupEventEntity::class,
        RankingSettingsEntityV2::class,
        AppStatsEntity::class,
        ImportBatchEntity::class,
        SuggestionDismissalEntity::class
    ],
    version = 2,
    exportSchema = false
)
private abstract class SongLadderDatabaseV2 : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun rankingSubjectDao(): RankingSubjectDao
    abstract fun rankingSettingsDao(): RankingSettingsDaoV2
}

@RunWith(AndroidJUnit4::class)
class SongLadderMigrationTest {
    @Test
    fun migrationFromV1PreservesExistingRowsAndAddsSuggestionDismissalsTable() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dbFile = context.getDatabasePath("migration-test.db")
        dbFile.delete()
        File(dbFile.path + "-wal").delete()
        File(dbFile.path + "-shm").delete()

        val v1 = Room.databaseBuilder(context, SongLadderDatabaseV1::class.java, dbFile.absolutePath)
            .allowMainThreadQueries()
            .build()
        v1.songDao().insertSongWithStats(
            song = SongEntity(
                id = "song-1",
                rankingSubjectId = "subject-1",
                sourceType = "MANUAL",
                title = "Nights",
                artist = "Frank Ocean",
                createdAt = 1234L
            ),
            stats = RankingSubjectEntity(id = "subject-1", scoreTenths = 80)
        )
        v1.close()

        val v2 = Room.databaseBuilder(context, SongLadderDatabase::class.java, dbFile.absolutePath)
            .addMigrations(SongLadderDatabase.MIGRATION_1_2, SongLadderDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
        try {
            val subject = v2.rankingSubjectDao().get("subject-1")
            assertEquals(80, subject?.scoreTenths)

            assertTrue(v2.suggestionDismissalDao().observeAll().first().isEmpty())
            v2.suggestionDismissalDao().upsert(
                SuggestionDismissalEntity(
                    subjectId = "subject-1",
                    dismissedAtSequenceId = 3L,
                    dismissedScoreTenths = 75
                )
            )
            assertEquals(75, v2.suggestionDismissalDao().get("subject-1")?.dismissedScoreTenths)
        } finally {
            v2.close()
            dbFile.delete()
            File(dbFile.path + "-wal").delete()
            File(dbFile.path + "-shm").delete()
        }
    }

    @Test
    fun migrationFromV2PreservesExistingRowsAndAddsAlbumTablesAndSettingsColumn() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dbFile = context.getDatabasePath("migration-v2-v3-test.db")
        dbFile.delete()
        File(dbFile.path + "-wal").delete()
        File(dbFile.path + "-shm").delete()

        val v2 = Room.databaseBuilder(context, SongLadderDatabaseV2::class.java, dbFile.absolutePath)
            .allowMainThreadQueries()
            .build()
        v2.songDao().insertSongWithStats(
            song = SongEntity(
                id = "song-1",
                rankingSubjectId = "subject-1",
                sourceType = "MANUAL",
                title = "Nights",
                artist = "Frank Ocean",
                createdAt = 1234L
            ),
            stats = RankingSubjectEntity(id = "subject-1", scoreTenths = 80)
        )
        v2.rankingSettingsDao().upsert(RankingSettingsEntityV2(showTips = false))
        v2.close()

        val v3 = Room.databaseBuilder(context, SongLadderDatabase::class.java, dbFile.absolutePath)
            .addMigrations(SongLadderDatabase.MIGRATION_1_2, SongLadderDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
        try {
            val subject = v3.rankingSubjectDao().get("subject-1")
            assertEquals(80, subject?.scoreTenths)

            val settings = v3.rankingSettingsDao().get()
            assertEquals(false, settings?.showTips)
            assertEquals(true, settings?.metadataRetrievalEnabled)

            assertTrue(v3.albumDao().getAll().isEmpty())
            val album = AlbumEntity(
                id = "frank ocean::blonde",
                title = "Blonde",
                artist = "Frank Ocean",
                normalizedTitle = "blonde",
                normalizedArtist = "frank ocean",
                createdAt = 1234L
            )
            v3.albumDao().insert(album)
            assertEquals(album, v3.albumDao().get("frank ocean::blonde"))

            val exclusion = AlbumTrackExclusionEntity(
                songId = "song-1",
                albumId = "frank ocean::blonde",
                excludedAt = 5678L
            )
            v3.albumTrackExclusionDao().insert(exclusion)
            assertEquals(exclusion, v3.albumTrackExclusionDao().get("song-1"))

            val missingTrack = AlbumMissingTrackEntity(
                albumId = "frank ocean::blonde",
                providerTrackId = "track-1",
                title = "Ivy"
            )
            v3.albumMissingTrackDao().insertAll(listOf(missingTrack))
            assertEquals(listOf(missingTrack), v3.albumMissingTrackDao().getForAlbum("frank ocean::blonde"))
        } finally {
            v3.close()
            dbFile.delete()
            File(dbFile.path + "-wal").delete()
            File(dbFile.path + "-shm").delete()
        }
    }
}
