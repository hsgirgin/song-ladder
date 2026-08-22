package com.songladder.android.data.repository

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.songladder.android.data.local.RankingSubjectEntity
import com.songladder.android.data.local.AppStatsEntity
import com.songladder.android.data.local.SongEntity
import com.songladder.android.data.local.SongLadderDatabase
import com.songladder.android.data.local.SuggestionDismissalEntity
import com.songladder.android.domain.engine.EloMatchupEngine
import com.songladder.android.domain.model.TimeSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DefaultRankingRepositoryTest {
    private lateinit var database: SongLadderDatabase

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, SongLadderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun battleAndSkipEventsReferenceStableSubjectIds() = runBlocking {
        insertSong(songId = "song-w", subjectId = "subject-w")
        insertSong(songId = "song-l", subjectId = "subject-l")
        val repository = DefaultRankingRepository(
            database = database,
            songDao = database.songDao(),
            matchupEngine = EloMatchupEngine(),
            rankingSubjectDao = database.rankingSubjectDao(),
            matchupEventDao = database.matchupEventDao(),
            suggestionDismissalDao = database.suggestionDismissalDao(),
            appStatsDao = database.appStatsDao()
        )

        repository.recordBattle("song-w", "song-l").getOrThrow()
        repository.recordSkip(listOf("song-w", "song-l")).getOrThrow()

        val events = database.matchupEventDao().getAll()
        assertEquals("subject-w", events[0].firstSubjectId)
        assertEquals("subject-l", events[0].secondSubjectId)
        assertEquals("subject-w", events[0].winnerSubjectId)
        assertEquals("subject-l", events[0].loserSubjectId)
        assertEquals("subject-w", events[1].firstSubjectId)
        assertEquals("subject-l", events[1].secondSubjectId)
        assertEquals(64.0, events[0].winnerEffectiveK ?: error("Missing winner K"), 0.000001)
        assertEquals(64.0, events[0].loserEffectiveK ?: error("Missing loser K"), 0.000001)
    }

    @Test
    fun battleDoesNotChangeLastRatedTimestamps() = runBlocking {
        insertSong(songId = "song-a", subjectId = "subject-a")
        insertSong(songId = "song-b", subjectId = "subject-b")
        val repository = repository()

        repository.recordBattle("song-a", "song-b").getOrThrow()

        assertEquals(null, database.rankingSubjectDao().get("subject-a")?.lastRatedAt)
        assertEquals(null, database.rankingSubjectDao().get("subject-b")?.lastRatedAt)
    }

    @Test
    fun scoreSaveUpdatesLastRatedTimestamp() = runBlocking {
        insertSong(songId = "song-a", subjectId = "subject-a")
        val repository = repository()

        repository.saveScore("song-a", 80).getOrThrow()

        assertEquals(100L, database.rankingSubjectDao().get("subject-a")?.lastRatedAt)
    }

    @Test
    fun scoreSaveReplaysTheEventLogAndResetsEditedResponsiveness() = runBlocking {
        insertSong(songId = "song-a", subjectId = "subject-a")
        insertSong(songId = "song-b", subjectId = "subject-b")
        val repository = repository()

        repository.saveScore("song-a", 80).getOrThrow()
        repository.saveScore("song-b", 80).getOrThrow()
        repository.recordBattle("song-a", "song-b").getOrThrow()
        val beforeEdit = database.rankingSubjectDao().get("subject-a")
            ?: error("Missing winner")

        repository.saveScore("song-a", 90).getOrThrow()
        val afterEdit = database.rankingSubjectDao().get("subject-a")
            ?: error("Missing edited winner")

        assertEquals(90, afterEdit.scoreTenths)
        assertEquals("EDITED", afterEdit.responsivenessEpoch)
        assertEquals(1, afterEdit.wins)
        assertEquals(0, afterEdit.completedMatchupsInEpoch)
        assertTrue(afterEdit.elo > beforeEdit.elo)
        assertEquals(1, database.matchupEventDao().getAll().size)

        repository.recordBattle("song-a", "song-b").getOrThrow()
        assertEquals(1, database.rankingSubjectDao().get("subject-a")?.completedMatchupsInEpoch)
    }

    @Test
    fun savingTheExistingScoreIsANoOpForRankingState() = runBlocking {
        insertSong(songId = "song-a", subjectId = "subject-a")
        insertSong(songId = "song-b", subjectId = "subject-b")
        val repository = repository()

        repository.saveScore("song-a", 80).getOrThrow()
        repository.saveScore("song-b", 80).getOrThrow()
        repository.recordBattle("song-a", "song-b").getOrThrow()
        val before = database.rankingSubjectDao().get("subject-a")
            ?: error("Missing subject before repeated save")
        val eventCountBefore = database.matchupEventDao().getAll().size

        val result = repository.saveScore("song-a", 80).getOrThrow()
        val after = database.rankingSubjectDao().get("subject-a")
            ?: error("Missing subject after repeated save")

        assertEquals(false, result.visibleOrderChanged)
        assertEquals(before.scoreTenths, after.scoreTenths)
        assertEquals(before.elo, after.elo, 0.000001)
        assertEquals(before.responsivenessEpoch, after.responsivenessEpoch)
        assertEquals(before.completedMatchupsInEpoch, after.completedMatchupsInEpoch)
        assertEquals(before.lastRatedAt, after.lastRatedAt)
        assertEquals(eventCountBefore, database.matchupEventDao().getAll().size)
    }

    @Test
    fun skipChangesSkipCountersButDoesNotChangeElo() = runBlocking {
        insertSong(songId = "song-a", subjectId = "subject-a")
        insertSong(songId = "song-b", subjectId = "subject-b")
        val repository = repository()

        val beforeA = database.rankingSubjectDao().get("subject-a")?.elo
        val beforeB = database.rankingSubjectDao().get("subject-b")?.elo
        repository.recordSkip(listOf("song-a", "song-b")).getOrThrow()

        assertEquals(beforeA, database.rankingSubjectDao().get("subject-a")?.elo)
        assertEquals(beforeB, database.rankingSubjectDao().get("subject-b")?.elo)
        assertEquals(1, database.rankingSubjectDao().get("subject-a")?.skips)
        assertEquals(1, database.rankingSubjectDao().get("subject-b")?.skips)
        assertEquals(1, database.appStatsDao().getAppStats()?.skipCount)
        assertEquals(0, database.appStatsDao().getAppStats()?.matchCount)
    }

    @Test
    fun undoRemovesOnlyTheLatestWinnerAndRebuildsDerivedState() = runBlocking {
        insertSong(songId = "song-a", subjectId = "subject-a")
        insertSong(songId = "song-b", subjectId = "subject-b")
        val repository = repository()

        repository.recordBattle("song-a", "song-b").getOrThrow()
        assertTrue(repository.undoLastWinner().getOrThrow())

        assertTrue(database.matchupEventDao().getAll().isEmpty())
        assertEquals(0, database.rankingSubjectDao().get("subject-a")?.wins)
        assertEquals(0, database.rankingSubjectDao().get("subject-b")?.losses)
        assertEquals(0, database.appStatsDao().getAppStats()?.matchCount)
        assertEquals(1200.0, database.rankingSubjectDao().get("subject-a")?.elo ?: 0.0, 0.0)
        assertTrue(!repository.undoLastWinner().getOrThrow())
    }

    @Test
    fun undoRemovesTheLatestWinnerEvenWhenASkipHappenedAfterward() = runBlocking {
        insertSong(songId = "song-a", subjectId = "subject-a")
        insertSong(songId = "song-b", subjectId = "subject-b")
        val repository = repository()

        repository.recordBattle("song-a", "song-b").getOrThrow()
        repository.recordSkip(listOf("song-a", "song-b")).getOrThrow()

        assertTrue(repository.undoLastWinner().getOrThrow())
        assertEquals(listOf("SKIP"), database.matchupEventDao().getAll().map { it.outcome })
        assertEquals(0, database.rankingSubjectDao().get("subject-a")?.wins)
        assertEquals(1, database.rankingSubjectDao().get("subject-a")?.skips)
    }

    @Test
    fun invalidMutationsLeaveEventAndCacheStateUnchanged() = runBlocking {
        insertSong(songId = "song-a", subjectId = "subject-a")
        insertSong(songId = "song-b", subjectId = "subject-b")
        database.appStatsDao().upsert(AppStatsEntity())
        val repository = repository()

        assertTrue(repository.recordBattle("song-a", "song-a").isFailure)
        assertTrue(repository.recordSkip(listOf("song-a", "missing")).isFailure)
        assertTrue(repository.saveScore("song-a", 101).isFailure)

        assertTrue(database.matchupEventDao().getAll().isEmpty())
        assertEquals(0, database.appStatsDao().getAppStats()?.matchCount)
        assertEquals(0, database.appStatsDao().getAppStats()?.skipCount)
        assertEquals(null, database.rankingSubjectDao().get("subject-a")?.scoreTenths)
    }

    @Test
    fun deletingOneSubjectHistoryRemovesMatchingEventsAndReplaysRemainingState() = runBlocking {
        insertSong(songId = "song-a", subjectId = "subject-a")
        insertSong(songId = "song-b", subjectId = "subject-b")
        insertSong(songId = "song-c", subjectId = "subject-c")
        val repository = repository()

        repository.recordBattle("song-a", "song-b").getOrThrow()
        repository.recordSkip(listOf("song-b", "song-c")).getOrThrow()

        val result = repository.deleteRankingHistory("subject-b").getOrThrow()

        assertEquals(2, result.deletedEventCount)
        assertTrue(database.matchupEventDao().getAll().isEmpty())
        assertEquals(0, database.rankingSubjectDao().get("subject-a")?.wins)
        assertEquals(0, database.rankingSubjectDao().get("subject-b")?.skips)
        assertEquals(0, database.rankingSubjectDao().get("subject-c")?.skips)
    }

    @Test
    fun deletingAllRankingHistoryClearsEventsAndEveryDerivedCache() = runBlocking {
        insertSong(songId = "song-a", subjectId = "subject-a")
        insertSong(songId = "song-b", subjectId = "subject-b")
        val repository = repository()

        repository.recordBattle("song-a", "song-b").getOrThrow()
        repository.recordSkip(listOf("song-a", "song-b")).getOrThrow()

        val result = repository.deleteAllRankingHistory().getOrThrow()

        assertEquals(2, result.deletedEventCount)
        assertTrue(database.matchupEventDao().getAll().isEmpty())
        assertEquals(0, database.rankingSubjectDao().get("subject-a")?.wins)
        assertEquals(0, database.rankingSubjectDao().get("subject-a")?.skips)
        assertEquals(0, database.rankingSubjectDao().get("subject-b")?.losses)
        assertEquals(0, database.rankingSubjectDao().get("subject-b")?.skips)
        assertEquals(0, database.appStatsDao().getAppStats()?.matchCount)
        assertEquals(0, database.appStatsDao().getAppStats()?.skipCount)
    }

    @Test
    fun deletingOneSubjectHistoryAlsoClearsItsSuggestionDismissalCheckpoint() = runBlocking {
        insertSong(songId = "song-a", subjectId = "subject-a")
        insertSong(songId = "song-b", subjectId = "subject-b")
        val repository = repository()
        database.suggestionDismissalDao().upsert(
            SuggestionDismissalEntity(subjectId = "subject-a", dismissedAtSequenceId = 5L, dismissedScoreTenths = 60)
        )

        repository.deleteRankingHistory("subject-a").getOrThrow()

        assertNull(database.suggestionDismissalDao().get("subject-a"))
    }

    @Test
    fun deletingAllRankingHistoryAlsoClearsEverySuggestionDismissalCheckpoint() = runBlocking {
        insertSong(songId = "song-a", subjectId = "subject-a")
        insertSong(songId = "song-b", subjectId = "subject-b")
        val repository = repository()
        database.suggestionDismissalDao().upsert(
            SuggestionDismissalEntity(subjectId = "subject-a", dismissedAtSequenceId = 5L, dismissedScoreTenths = 60)
        )
        database.suggestionDismissalDao().upsert(
            SuggestionDismissalEntity(subjectId = "subject-b", dismissedAtSequenceId = 5L, dismissedScoreTenths = 60)
        )

        repository.deleteAllRankingHistory().getOrThrow()

        assertTrue(database.suggestionDismissalDao().observeAll().first().isEmpty())
    }

    @Test
    fun observeSuggestionsEmitsEmptyListWithNoEvidence() = runBlocking {
        insertSong(songId = "song-a", subjectId = "subject-a")
        insertSong(songId = "song-b", subjectId = "subject-b")
        val repository = repository()

        assertTrue(repository.observeSuggestions().first().isEmpty())
    }

    @Test
    fun dismissSuggestionLaterPersistsTheDismissal() = runBlocking {
        insertSong(songId = "song-a", subjectId = "subject-a")
        val repository = repository()

        repository.dismissSuggestionLater(
            subjectId = "subject-a",
            suggestedScoreTenths = 70,
            lastEventSequenceId = 3L
        ).getOrThrow()

        val dismissal = database.suggestionDismissalDao().get("subject-a")
        assertEquals(70, dismissal?.dismissedScoreTenths)
        assertEquals(3L, dismissal?.dismissedAtSequenceId)
    }

    @Test
    fun acceptSuggestionRefreshesTheCheckpointInsteadOfClearingIt() = runBlocking {
        // Reseeding elo from the accepted score and replaying the event log can
        // land on a different implied score than the one just accepted (Elo
        // replay is path-dependent on the seed) - without a fresh checkpoint,
        // that reseed artifact alone could immediately re-flag this subject as
        // "disagreeing" with the score it was just given, with no new evidence.
        insertSong(songId = "song-a", subjectId = "subject-a")
        val repository = repository()
        database.suggestionDismissalDao().upsert(
            SuggestionDismissalEntity(subjectId = "subject-a", dismissedAtSequenceId = 1L, dismissedScoreTenths = 60)
        )

        val result = repository.acceptSuggestion("subject-a", 70).getOrThrow()

        assertEquals(70, result.scoreTenths)
        assertEquals("song-a", result.songId)
        assertEquals(70, database.rankingSubjectDao().get("subject-a")?.scoreTenths)
        val checkpoint = database.suggestionDismissalDao().get("subject-a")
        assertEquals(70, checkpoint?.dismissedScoreTenths)
        assertEquals(0L, checkpoint?.dismissedAtSequenceId)
    }

    @Test
    fun savingAScoreDirectlyAlsoWritesASuggestionCheckpoint() = runBlocking {
        insertSong(songId = "song-a", subjectId = "subject-a")
        val repository = repository()

        repository.saveScore("song-a", 80).getOrThrow()

        val checkpoint = database.suggestionDismissalDao().get("subject-a")
        assertEquals(80, checkpoint?.dismissedScoreTenths)
    }

    private fun repository(): DefaultRankingRepository = DefaultRankingRepository(
        database = database,
        songDao = database.songDao(),
        matchupEngine = EloMatchupEngine(),
        rankingSubjectDao = database.rankingSubjectDao(),
        matchupEventDao = database.matchupEventDao(),
        suggestionDismissalDao = database.suggestionDismissalDao(),
        appStatsDao = database.appStatsDao(),
        timeSource = TimeSource { 100L }
    )

    private suspend fun insertSong(songId: String, subjectId: String) {
        database.songDao().insertSongWithStats(
            song = SongEntity(
                id = songId,
                rankingSubjectId = subjectId,
                sourceType = "MANUAL",
                title = songId,
                artist = "Artist",
                createdAt = 0L
            ),
            stats = RankingSubjectEntity(id = subjectId)
        )
    }
}
