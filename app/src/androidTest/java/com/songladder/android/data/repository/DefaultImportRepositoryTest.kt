package com.songladder.android.data.repository

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.songladder.android.data.local.RankingSubjectEntity
import com.songladder.android.data.local.SongEntity
import com.songladder.android.data.local.SongLadderDatabase
import com.songladder.android.data.local.SongLadderJsonPorter
import com.songladder.android.domain.model.MusicSourceType
import com.songladder.android.domain.model.MusicTrackCandidate
import com.songladder.android.domain.model.TombstoneImportAction
import com.songladder.android.domain.model.TombstoneImportResolution
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
class DefaultImportRepositoryTest {
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
    fun matchesAndRestoresATombstoneBySourceAndExternalId() = runBlocking {
        insertTombstone("subject-1")
        val candidate = candidate()
        val repository = repository()

        val conflict = repository.findTombstoneMatches(listOf(candidate)).getOrThrow().single()
        assertEquals("subject-1", conflict.matches.single().rankingSubjectId)

        repository.importTracks(
            candidates = listOf(candidate),
            sourceLabel = "search",
            resolutions = mapOf(
                importKey(candidate) to TombstoneImportResolution(
                    action = TombstoneImportAction.RESTORE,
                    rankingSubjectId = "subject-1"
                )
            )
        ).getOrThrow()

        assertNull(database.rankingSubjectDao().get("subject-1")?.tombstoneDeletedAt)
        assertEquals("subject-1", database.songDao().getSongsWithStats().single().song.rankingSubjectId)
    }

    @Test
    fun startFreshSuppressesTheSelectedTombstoneAssociation() = runBlocking {
        insertTombstone("subject-1")
        val candidate = candidate()
        val repository = repository()

        repository.importTracks(
            listOf(candidate),
            "search",
            mapOf(importKey(candidate) to TombstoneImportResolution(TombstoneImportAction.START_FRESH))
        ).getOrThrow()

        val tombstone = database.rankingSubjectDao().get("subject-1")
        assertEquals(candidate.externalId, tombstone?.tombstoneSuppressedExternalId)
        assertTrue(repository.findTombstoneMatches(listOf(candidate)).getOrThrow().isEmpty())
        assertEquals(1, database.songDao().getSongsWithStats().size)
    }

    @Test
    fun doesNotOfferTombstoneRestorationWhenAnActiveDuplicateAlreadyExists() = runBlocking {
        insertTombstone("subject-1")
        database.songDao().insertSongWithStats(
            song = SongEntity(
                id = "active-song",
                rankingSubjectId = "active-subject",
                sourceType = "ITUNES",
                title = "Nights",
                artist = "Artist",
                createdAt = 1L
            ),
            stats = RankingSubjectEntity(
                id = "active-subject",
                sourceType = "ITUNES",
                normalizedTitle = "nights",
                normalizedArtist = "artist"
            )
        )

        val repository = repository()
        assertTrue(repository.findTombstoneMatches(listOf(candidate())).getOrThrow().isEmpty())
        assertEquals(0, repository.importTracks(listOf(candidate()), "search").getOrThrow())
        assertNotNull(database.rankingSubjectDao().get("subject-1")?.tombstoneDeletedAt)
    }

    private fun repository() = DefaultImportRepository(
        database = database,
        songDao = database.songDao(),
        rankingSubjectDao = database.rankingSubjectDao(),
        matchupEventDao = database.matchupEventDao(),
        rankingSettingsDao = database.rankingSettingsDao(),
        importBatchDao = database.importBatchDao(),
        appStatsDao = database.appStatsDao(),
        jsonPorter = SongLadderJsonPorter()
    )

    private suspend fun insertTombstone(subjectId: String) {
        database.rankingSubjectDao().insert(
            RankingSubjectEntity(
                id = subjectId,
                sourceType = "ITUNES",
                externalId = "old-external",
                normalizedTitle = "nights",
                normalizedArtist = "artist",
                tombstoneDeletedAt = 10L,
                tombstoneSourceType = "ITUNES",
                tombstoneExternalId = "external-1"
            )
        )
    }

    private fun candidate() = MusicTrackCandidate(
        externalId = "external-1",
        title = "Nights",
        artist = "Artist",
        sourceType = MusicSourceType.ITUNES
    )

    private fun importKey(candidate: MusicTrackCandidate): String =
        "${candidate.sourceType.name}:${candidate.externalId}:${candidate.title.trim().lowercase()}::${candidate.artist.trim().lowercase()}"
}
