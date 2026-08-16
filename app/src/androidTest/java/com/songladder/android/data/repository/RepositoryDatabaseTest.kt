package com.songladder.android.data.repository

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.songladder.android.data.local.AppStatsEntity
import com.songladder.android.data.local.RankingStatsEntity
import com.songladder.android.data.local.SongEntity
import com.songladder.android.data.local.SongLadderDatabase
import com.songladder.android.data.local.SongLadderJsonPorter
import com.songladder.android.domain.engine.EloMatchupEngine
import com.songladder.android.domain.model.ExportPayload
import com.songladder.android.domain.model.MusicSourceType
import com.songladder.android.domain.model.MusicTrackCandidate
import com.songladder.android.domain.model.SongExport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RepositoryDatabaseTest {
    private lateinit var context: Context
    private lateinit var database: SongLadderDatabase
    private lateinit var songRepository: DefaultSongRepository
    private lateinit var rankingRepository: DefaultRankingRepository
    private lateinit var importRepository: DefaultImportRepository
    private val backupFiles = mutableListOf<File>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, SongLadderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        songRepository = DefaultSongRepository(
            database = database,
            songDao = database.songDao(),
            appStatsDao = database.appStatsDao()
        )
        rankingRepository = DefaultRankingRepository(
            database = database,
            songDao = database.songDao(),
            matchupEngine = EloMatchupEngine(),
            appStatsDao = database.appStatsDao()
        )
        importRepository = DefaultImportRepository(
            database = database,
            songDao = database.songDao(),
            importBatchDao = database.importBatchDao(),
            appStatsDao = database.appStatsDao(),
            jsonPorter = SongLadderJsonPorter()
        )
    }

    @After
    fun tearDown() {
        database.close()
        backupFiles.forEach { it.delete() }
    }

    @Test
    fun battle_updates_both_song_stats_and_app_stats_atomically() = runBlocking {
        insertSong("winner", "Winner")
        insertSong("loser", "Loser")

        assertTrue(rankingRepository.recordBattle("winner", "loser").isSuccess)

        val winner = database.songDao().getSongWithStats("winner")!!.stats
        val loser = database.songDao().getSongWithStats("loser")!!.stats
        assertEquals(1, winner.wins)
        assertEquals(1, loser.losses)
        assertEquals(1, database.appStatsDao().getAppStats()!!.matchCount)
    }

    @Test
    fun battle_increments_daily_count_and_restarts_it_on_a_new_day() = runBlocking {
        insertSong("winner", "Winner")
        insertSong("loser", "Loser")
        database.appStatsDao().upsert(
            AppStatsEntity(
                matchCount = 4,
                dailyGoalDate = "2025-01-02",
                dailyMatchCount = 3
            )
        )
        val todayRepository = DefaultRankingRepository(
            database = database,
            songDao = database.songDao(),
            matchupEngine = EloMatchupEngine(),
            appStatsDao = database.appStatsDao(),
            today = { LocalDate.of(2025, 1, 2) }
        )
        val tomorrowRepository = DefaultRankingRepository(
            database = database,
            songDao = database.songDao(),
            matchupEngine = EloMatchupEngine(),
            appStatsDao = database.appStatsDao(),
            today = { LocalDate.of(2025, 1, 3) }
        )

        assertTrue(todayRepository.recordBattle("winner", "loser").isSuccess)
        assertEquals(
            AppStatsEntity(matchCount = 5, dailyGoalDate = "2025-01-02", dailyMatchCount = 4),
            database.appStatsDao().getAppStats()
        )

        assertTrue(tomorrowRepository.recordBattle("winner", "loser").isSuccess)
        assertEquals(
            AppStatsEntity(matchCount = 6, dailyGoalDate = "2025-01-03", dailyMatchCount = 1),
            database.appStatsDao().getAppStats()
        )
    }

    @Test
    fun failed_battle_rolls_back_updates_when_loser_is_missing() = runBlocking {
        insertSong("winner", "Winner")
        database.appStatsDao().upsert(AppStatsEntity(matchCount = 4))

        val result = rankingRepository.recordBattle("winner", "missing")

        assertTrue(result.isFailure)
        assertEquals(RankingStatsEntity(songId = "winner"), database.songDao().getSongWithStats("winner")!!.stats)
        assertEquals(4, database.appStatsDao().getAppStats()!!.matchCount)
    }

    @Test
    fun battle_rejects_same_song_without_changing_stats() = runBlocking {
        insertSong("one", "One")
        database.appStatsDao().upsert(AppStatsEntity(matchCount = 4))

        val result = rankingRepository.recordBattle("one", "one")

        assertTrue(result.isFailure)
        assertEquals(RankingStatsEntity(songId = "one"), database.songDao().getSongWithStats("one")!!.stats)
        assertEquals(4, database.appStatsDao().getAppStats()!!.matchCount)
    }

    @Test
    fun observing_stats_persists_daily_rollover() = runBlocking {
        database.appStatsDao().upsert(
            AppStatsEntity(
                matchCount = 4,
                dailyGoalDate = "2025-01-01",
                dailyMatchCount = 3
            )
        )
        val rolloverRepository = DefaultRankingRepository(
            database = database,
            songDao = database.songDao(),
            matchupEngine = EloMatchupEngine(),
            appStatsDao = database.appStatsDao(),
            today = { LocalDate.of(2025, 1, 2) }
        )

        assertEquals(0, rolloverRepository.observeStats().first().dailyMatchCount)
        assertEquals("2025-01-02", database.appStatsDao().getAppStats()!!.dailyGoalDate)
    }

    @Test
    fun active_stats_observer_normalizes_a_previous_day_json_import() = runBlocking {
        val rolloverRepository = DefaultRankingRepository(
            database = database,
            songDao = database.songDao(),
            matchupEngine = EloMatchupEngine(),
            appStatsDao = database.appStatsDao(),
            today = { LocalDate.of(2025, 1, 2) }
        )
        val observerStarted = CompletableDeferred<Unit>()
        val importedStats = CompletableDeferred<AppStatsEntity>()
        val observer = launch {
            rolloverRepository.observeStats().collect { stats ->
                if (stats.dailyGoalDate == "2025-01-02" && stats.matchCount == 0) {
                    observerStarted.complete(Unit)
                }
                if (stats.matchCount == 7 && !importedStats.isCompleted) {
                    importedStats.complete(
                        AppStatsEntity(
                            matchCount = stats.matchCount,
                            skipCount = stats.skipCount,
                            dailyGoalDate = stats.dailyGoalDate,
                            dailyMatchCount = stats.dailyMatchCount
                        )
                    )
                }
            }
        }
        val payload = ExportPayload(
            songs = emptyList(),
            matchCount = 7,
            skipCount = 6,
            dailyGoalDate = "2025-01-01",
            dailyMatchCount = 3
        )

        try {
            observerStarted.await()
            assertEquals(
                0,
                importRepository.importFromJson(
                    context.contentResolver,
                    createBackupUri(SongLadderJsonPorter().encode(payload))
                ).getOrThrow()
            )

            assertEquals(
                AppStatsEntity(
                    matchCount = 7,
                    skipCount = 6,
                    dailyGoalDate = "2025-01-02",
                    dailyMatchCount = 0
                ),
                importedStats.await()
            )
        } finally {
            observer.cancel()
        }
    }

    @Test
    fun migration_1_to_2_preserves_existing_app_stats() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "migration-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)

        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(databaseName), null).use { legacyDatabase ->
            legacyDatabase.execSQL("""
                CREATE TABLE songs (
                    id TEXT NOT NULL PRIMARY KEY,
                    externalId TEXT,
                    sourceType TEXT NOT NULL,
                    title TEXT NOT NULL,
                    artist TEXT NOT NULL,
                    album TEXT NOT NULL,
                    artworkUrl TEXT,
                    createdAt INTEGER NOT NULL
                )
            """.trimIndent())
            legacyDatabase.execSQL("""
                CREATE TABLE ranking_stats (
                    songId TEXT NOT NULL PRIMARY KEY,
                    rating INTEGER NOT NULL,
                    wins INTEGER NOT NULL,
                    losses INTEGER NOT NULL,
                    skips INTEGER NOT NULL,
                    lastRankedAt INTEGER,
                    FOREIGN KEY(songId) REFERENCES songs(id) ON DELETE CASCADE
                )
            """.trimIndent())
            legacyDatabase.execSQL("""
                CREATE TABLE app_stats (
                    id INTEGER NOT NULL PRIMARY KEY,
                    matchCount INTEGER NOT NULL,
                    skipCount INTEGER NOT NULL
                )
            """.trimIndent())
            legacyDatabase.execSQL("CREATE INDEX index_ranking_stats_songId ON ranking_stats(songId)")
            legacyDatabase.execSQL("""
                CREATE TABLE import_batches (
                    id TEXT NOT NULL PRIMARY KEY,
                    sourceLabel TEXT NOT NULL,
                    importedAt INTEGER NOT NULL,
                    itemCount INTEGER NOT NULL
                )
            """.trimIndent())
            legacyDatabase.execSQL("INSERT INTO app_stats (id, matchCount, skipCount) VALUES (0, 12, 4)")
            legacyDatabase.execSQL("PRAGMA user_version = 1")
        }

        val migrated = Room.databaseBuilder(context, SongLadderDatabase::class.java, databaseName)
            .addMigrations(SongLadderDatabase.MIGRATION_1_2)
            .build()
        try {
            assertEquals(AppStatsEntity(matchCount = 12, skipCount = 4), migrated.appStatsDao().getAppStats())
        } finally {
            migrated.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun skip_counts_unique_songs_and_one_skip_operation() = runBlocking {
        insertSong("one", "One")
        insertSong("two", "Two")

        assertTrue(rankingRepository.recordSkip(listOf("one", "one", "two")).isSuccess)

        assertEquals(1, database.songDao().getSongWithStats("one")!!.stats.skips)
        assertEquals(1, database.songDao().getSongWithStats("two")!!.stats.skips)
        assertEquals(1, database.appStatsDao().getAppStats()!!.skipCount)
    }

    @Test
    fun failed_skip_rolls_back_prior_song_updates() = runBlocking {
        insertSong("one", "One")
        insertSong("two", "Two")
        database.appStatsDao().upsert(AppStatsEntity(skipCount = 3))

        val result = rankingRepository.recordSkip(listOf("one", "missing", "two"))

        assertTrue(result.isFailure)
        assertEquals(0, database.songDao().getSongWithStats("one")!!.stats.skips)
        assertEquals(0, database.songDao().getSongWithStats("two")!!.stats.skips)
        assertEquals(3, database.appStatsDao().getAppStats()!!.skipCount)
    }

    @Test
    fun reset_clears_songs_and_app_stats() = runBlocking {
        insertSong("one", "One")
        database.appStatsDao().upsert(AppStatsEntity(matchCount = 2, skipCount = 1))

        assertTrue(songRepository.resetLibrary().isSuccess)

        assertTrue(database.songDao().getSongsWithStats().isEmpty())
        assertEquals(AppStatsEntity(), database.appStatsDao().getAppStats())
    }

    @Test
    fun importing_tracks_deduplicates_against_input_and_existing_songs() = runBlocking {
        insertSong("existing", "Existing")
        val candidates = listOf(
            candidate("first", "Artist"),
            candidate(" FIRST ", "artist"),
            candidate("Existing", "EXISTING"),
            candidate("second", "Artist")
        )

        val result = importRepository.importTracks(candidates, sourceLabel = "Test import")

        assertEquals(2, result.getOrThrow())
        assertEquals(3, database.songDao().getSongsWithStats().size)
        assertEquals(1200, database.songDao().getSongWithStats("existing")!!.stats.rating)
    }

    @Test
    fun json_import_replaces_library_and_preserves_imported_stats() = runBlocking {
        insertSong("old", "Old")
        database.appStatsDao().upsert(AppStatsEntity(matchCount = 9, skipCount = 8))
        val payload = ExportPayload(
            songs = listOf(
                SongExport(
                    id = "new",
                    sourceType = "MANUAL",
                    title = "New",
                    artist = "Artist",
                    createdAt = 42L,
                    rating = 1250,
                    wins = 3,
                    losses = 1,
                    skips = 2,
                    lastRankedAt = 41L
                )
            ),
            matchCount = 7,
            skipCount = 6
        )

        val result = importRepository.importFromJson(
            context.contentResolver,
            createBackupUri(SongLadderJsonPorter().encode(payload))
        )

        assertEquals(1, result.getOrThrow())
        assertFalse(database.songDao().getSongWithStats("old") != null)
        assertEquals(1250, database.songDao().getSongWithStats("new")!!.stats.rating)
        assertEquals(AppStatsEntity(matchCount = 7, skipCount = 6), database.appStatsDao().getAppStats())
    }

    @Test
    fun json_export_and_import_round_trip_daily_goal_stats() = runBlocking {
        insertSong("song", "Song")
        val expectedStats = AppStatsEntity(
            matchCount = 7,
            skipCount = 6,
            dailyGoalDate = "2025-01-02",
            dailyMatchCount = 4
        )
        database.appStatsDao().upsert(expectedStats)
        val uri = createBackupUri()

        assertTrue(importRepository.exportToJson(context.contentResolver, uri).isSuccess)
        val exported = SongLadderJsonPorter().decode(readBackup(uri))
        assertEquals(expectedStats.dailyGoalDate, exported.dailyGoalDate)
        assertEquals(expectedStats.dailyMatchCount, exported.dailyMatchCount)

        database.appStatsDao().upsert(AppStatsEntity())
        assertEquals(1, importRepository.importFromJson(context.contentResolver, uri).getOrThrow())
        assertEquals(expectedStats, database.appStatsDao().getAppStats())
    }

    @Test
    fun malformed_json_does_not_replace_existing_library_or_stats() = runBlocking {
        insertSong("old", "Old")
        database.appStatsDao().upsert(AppStatsEntity(matchCount = 9, skipCount = 8))

        val result = importRepository.importFromJson(
            context.contentResolver,
            createBackupUri("not-json")
        )

        assertTrue(result.isFailure)
        assertTrue(database.songDao().getSongWithStats("old") != null)
        assertEquals(AppStatsEntity(matchCount = 9, skipCount = 8), database.appStatsDao().getAppStats())
    }

    @Test
    fun removing_song_cascades_its_ranking_stats() = runBlocking {
        insertSong("one", "One")

        assertTrue(songRepository.removeSong("one").isSuccess)

        assertTrue(database.songDao().getSongWithStats("one") == null)
        assertTrue(database.songDao().getRankingStats("one") == null)
    }

    private suspend fun insertSong(id: String, title: String) {
        database.songDao().insertSongWithStats(
            song = SongEntity(
                id = id,
                sourceType = MusicSourceType.MANUAL.name,
                title = title,
                artist = "Artist",
                createdAt = id.hashCode().toLong()
            ),
            stats = RankingStatsEntity(songId = id)
        )
    }

    private fun candidate(title: String, artist: String): MusicTrackCandidate =
        MusicTrackCandidate(
            externalId = "$title-$artist",
            title = title,
            artist = artist,
            sourceType = MusicSourceType.ITUNES
        )

    private fun createBackupUri(content: String = ""): Uri {
        val file = File.createTempFile("song-ladder-", ".json", context.cacheDir)
        file.writeText(content)
        backupFiles += file
        return Uri.fromFile(file)
    }

    private fun readBackup(uri: Uri): String = File(requireNotNull(uri.path)).readText()
}
