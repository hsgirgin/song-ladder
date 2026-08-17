package com.songladder.android.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SongLadderMigrationTest {
    private val databaseName = "song_ladder_migration_test.db"
    private lateinit var databasePathContext: android.content.Context

    @Before
    fun setUp() {
        databasePathContext = InstrumentationRegistry.getInstrumentation().targetContext
        databasePathContext.deleteDatabase(databaseName)
    }

    @After
    fun tearDown() {
        databasePathContext.deleteDatabase(databaseName)
    }

    @Test
    fun `migration adds responsiveness and tombstone suppression columns`() {
        val oldHelper = helper(callback = object : SupportSQLiteOpenHelper.Callback(1) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE ranking_subjects (
                        id TEXT NOT NULL PRIMARY KEY,
                        scoreTenths INTEGER,
                        elo REAL NOT NULL,
                        wins INTEGER NOT NULL,
                        losses INTEGER NOT NULL,
                        skips INTEGER NOT NULL,
                        lastRatedAt INTEGER,
                        responsivenessEpoch TEXT NOT NULL,
                        completedMatchupsInEpoch INTEGER NOT NULL,
                        sourceType TEXT NOT NULL,
                        externalId TEXT,
                        normalizedTitle TEXT NOT NULL,
                        normalizedArtist TEXT NOT NULL,
                        tombstoneDeletedAt INTEGER,
                        tombstoneSourceType TEXT,
                        tombstoneExternalId TEXT,
                        tombstoneScoreTenths INTEGER,
                        tombstoneSeedElo REAL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "INSERT INTO ranking_subjects " +
                        "(id, elo, wins, losses, skips, responsivenessEpoch, " +
                        "completedMatchupsInEpoch, sourceType, normalizedTitle, normalizedArtist) " +
                        "VALUES ('subject-1', 1200.0, 0, 0, 0, 'NEW', 0, 'MANUAL', 'title', 'artist')"
                )
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }).also { it.writableDatabase.close() }

        val migratedHelper = helper(callback = object : SupportSQLiteOpenHelper.Callback(2) {
            override fun onCreate(db: SupportSQLiteDatabase) = Unit

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                MIGRATION_1_2.migrate(db)
            }
        })

        val database = migratedHelper.writableDatabase
        database.query(
            "SELECT responsivenessEpochSequence, tombstoneSuppressedExternalId " +
                "FROM ranking_subjects WHERE id = 'subject-1'"
        ).use { cursor ->
            check(cursor.moveToFirst())
            assertEquals(0L, cursor.getLong(0))
            assertEquals(true, cursor.isNull(1))
        }
        database.close()
        oldHelper.close()
        migratedHelper.close()
    }

    private fun helper(
        callback: SupportSQLiteOpenHelper.Callback
    ): SupportSQLiteOpenHelper = FrameworkSQLiteOpenHelperFactory().create(
        SupportSQLiteOpenHelper.Configuration.builder(databasePathContext)
            .name(databaseName)
            .callback(callback)
            .build()
    )
}
