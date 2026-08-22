package com.songladder.android.data.local

import androidx.room.Database
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
        RankingSettingsEntity::class,
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
            .addMigrations(SongLadderDatabase.MIGRATION_1_2)
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
}
