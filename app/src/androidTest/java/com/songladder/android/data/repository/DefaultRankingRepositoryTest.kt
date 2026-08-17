package com.songladder.android.data.repository

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.songladder.android.data.local.RankingSubjectEntity
import com.songladder.android.data.local.SongEntity
import com.songladder.android.data.local.SongLadderDatabase
import com.songladder.android.domain.engine.EloMatchupEngine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
    fun `battle and skip events reference stable subject ids`() = runBlocking {
        insertSong(songId = "song-w", subjectId = "subject-w")
        insertSong(songId = "song-l", subjectId = "subject-l")
        val repository = DefaultRankingRepository(
            database = database,
            songDao = database.songDao(),
            matchupEngine = EloMatchupEngine(),
            rankingSubjectDao = database.rankingSubjectDao(),
            matchupEventDao = database.matchupEventDao(),
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
