package com.songladder.android.data.local

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SongLadderPersistenceTest {
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
    fun `round trip persists songs subjects events settings and stats`() = runBlocking {
        val song = SongEntity(
            id = "song-1",
            rankingSubjectId = "subject-1",
            sourceType = "ITUNES",
            title = "Nights",
            artist = "Frank Ocean",
            createdAt = 1234L
        )
        val subject = RankingSubjectEntity(
            id = "subject-1",
            scoreTenths = 87,
            elo = 1240.5,
            wins = 8,
            losses = 2,
            skips = 1,
            responsivenessEpochSequence = 1L,
            normalizedTitle = "nights",
            normalizedArtist = "frank ocean"
        )
        val tombstone = RankingSubjectEntity(
            id = "subject-2",
            sourceType = "ITUNES",
            normalizedTitle = "Ivy",
            normalizedArtist = "Frank Ocean",
            tombstoneDeletedAt = 9999L,
            tombstoneSourceType = "ITUNES",
            tombstoneExternalId = "external-2",
            tombstoneScoreTenths = 76,
            tombstoneSeedElo = 1208.0,
            tombstoneSuppressedExternalId = "replacement-2",
            tombstoneSuppressedSourceType = "ITUNES",
            tombstoneSuppressedNormalizedTitle = "ivy",
            tombstoneSuppressedNormalizedArtist = "frank ocean"
        )
        val event = MatchupEventEntity(
            sequenceId = 1L,
            occurredAt = 5678L,
            firstSubjectId = "subject-1",
            secondSubjectId = "subject-2",
            outcome = "WIN",
            winnerSubjectId = "subject-1",
            loserSubjectId = "subject-2",
            winnerEffectiveK = 64.0,
            loserEffectiveK = 40.0
        )
        val entities = ExportEntities(
            songs = listOf(song),
            subjects = listOf(subject, tombstone),
            events = listOf(event),
            settings = RankingSettingsEntity(
                autoPlayMatchupPreviews = false,
                showTips = false,
                presentation = "LIST"
            ),
            appStats = AppStatsEntity(matchCount = 1, skipCount = 2)
        )
        val restored = SongLadderJsonPorter()
            .decode(SongLadderJsonPorter().encode(entities.toPayload()))
            .toEntities()

        database.withTransaction {
            database.rankingSubjectDao().insertAll(restored.subjects)
            restored.songs.forEach { database.songDao().insertSong(it) }
            database.matchupEventDao().insertAll(restored.events)
            database.rankingSettingsDao().upsert(restored.settings)
            database.appStatsDao().upsert(restored.appStats)
        }

        assertEquals(listOf(song), database.songDao().getSongsWithStats().map { it.song })
        assertEquals(listOf(subject, tombstone), database.rankingSubjectDao().getAll())
        assertEquals(listOf(event), database.matchupEventDao().getAll())
        assertEquals(
            RankingSettingsEntity(autoPlayMatchupPreviews = false, showTips = false, presentation = "LIST"),
            database.rankingSettingsDao().get()
        )
        assertEquals(AppStatsEntity(matchCount = 1, skipCount = 2), database.appStatsDao().getAppStats())
    }
}
