package com.songladder.android.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.songladder.android.data.local.AppStatsEntity
import com.songladder.android.data.local.MatchupEventEntity
import com.songladder.android.data.local.RankingSubjectEntity
import com.songladder.android.data.local.SongEntity
import com.songladder.android.data.local.SongLadderDatabase
import com.songladder.android.domain.model.SongInput
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DefaultSongRepositoryTest {
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
    fun removingASongLeavesItsTombstoneAndMatchupHistoryIntact() = runBlocking {
        insertSong(songId = "song-1", subjectId = "subject-1", title = "Nights")
        insertSong(songId = "song-2", subjectId = "subject-2", title = "Ivy")
        val event = MatchupEventEntity(
            sequenceId = 1L,
            occurredAt = 100L,
            firstSubjectId = "subject-1",
            secondSubjectId = "subject-2",
            outcome = "WIN",
            winnerSubjectId = "subject-1",
            loserSubjectId = "subject-2",
            winnerEffectiveK = 64.0,
            loserEffectiveK = 64.0
        )
        database.matchupEventDao().insert(event)
        val repository = repository()

        assertTrue(repository.removeSong("song-1").isSuccess)

        assertNull(database.songDao().getSongWithStats("song-1"))
        val tombstone = database.rankingSubjectDao().get("subject-1")
        assertNotNull(tombstone)
        assertTrue((tombstone?.tombstoneDeletedAt ?: 0L) > 0L)
        assertEquals("MANUAL", tombstone?.tombstoneSourceType)
        assertEquals("nights", tombstone?.normalizedTitle)
        assertEquals("artist", tombstone?.normalizedArtist)
        assertEquals(listOf(event), database.matchupEventDao().getAll())
        assertNotNull(database.songDao().getSongWithStats("song-2"))
    }

    @Test
    fun resetClearsActiveSongsSubjectsEventsAndCachedStats() = runBlocking {
        insertSong(songId = "song-1", subjectId = "subject-1", title = "Nights")
        database.matchupEventDao().insert(
            MatchupEventEntity(
                sequenceId = 1L,
                occurredAt = 100L,
                firstSubjectId = "subject-1",
                secondSubjectId = "subject-2",
                outcome = "SKIP"
            )
        )
        database.appStatsDao().upsert(AppStatsEntity(matchCount = 3, skipCount = 2))
        val repository = repository()

        assertTrue(repository.resetLibrary().isSuccess)

        assertEquals(emptyList<SongEntity>(), database.songDao().getSongsWithStats().map { it.song })
        assertEquals(emptyList<RankingSubjectEntity>(), database.rankingSubjectDao().getAll())
        assertEquals(emptyList<MatchupEventEntity>(), database.matchupEventDao().getAll())
        assertEquals(AppStatsEntity(), database.appStatsDao().getAppStats())
    }

    @Test
    fun restoringASongReusesItsTombstoneSubjectAndPreservesHistory() = runBlocking {
        insertSong(songId = "song-1", subjectId = "subject-1", title = "Nights")
        database.matchupEventDao().insert(
            MatchupEventEntity(
                sequenceId = 1L,
                occurredAt = 100L,
                firstSubjectId = "subject-1",
                secondSubjectId = "subject-2",
                outcome = "SKIP"
            )
        )
        val repository = repository()
        repository.removeSong("song-1").getOrThrow()

        repository.restoreSong(
            SongInput(title = "Nights", artist = "Artist", sourceType = com.songladder.android.domain.model.MusicSourceType.MANUAL),
            rankingSubjectId = "subject-1"
        ).getOrThrow()

        val restored = database.songDao().getSongsWithStats().single()
        assertEquals("subject-1", restored.song.rankingSubjectId)
        assertNull(restored.stats.tombstoneDeletedAt)
        assertEquals(listOf(1L), database.matchupEventDao().getAll().map { it.sequenceId })
    }

    private fun repository(): DefaultSongRepository = DefaultSongRepository(
        database = database,
        songDao = database.songDao(),
        rankingSubjectDao = database.rankingSubjectDao(),
        matchupEventDao = database.matchupEventDao(),
        appStatsDao = database.appStatsDao()
    )

    private suspend fun insertSong(songId: String, subjectId: String, title: String) {
        database.songDao().insertSongWithStats(
            song = SongEntity(
                id = songId,
                rankingSubjectId = subjectId,
                sourceType = "MANUAL",
                title = title,
                artist = "Artist",
                createdAt = 0L
            ),
            stats = RankingSubjectEntity(
                id = subjectId,
                normalizedTitle = title.lowercase(),
                normalizedArtist = "artist"
            )
        )
    }
}
