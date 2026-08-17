package com.songladder.android.data.local

import com.songladder.android.domain.model.ExportPayload
import com.songladder.android.domain.model.MatchupOutcome
import com.songladder.android.domain.model.RankingPresentation
import com.songladder.android.domain.model.RankingSettingsExport
import com.songladder.android.domain.model.RankingSubjectExport
import com.songladder.android.domain.model.SongExport
import com.songladder.android.domain.model.TombstoneExport
import com.songladder.android.domain.engine.EloMatchupEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SongLadderJsonPorterTest {
    private val porter = SongLadderJsonPorter()

    @Test
    fun `encodes and decodes export payload`() {
        val payload = ExportPayload(
            songs = listOf(
                SongExport(
                    id = "song-1",
                    rankingSubjectId = "subject-1",
                    externalId = "source-1",
                    sourceType = "SPOTIFY",
                    title = "Nights",
                    artist = "Frank Ocean",
                    album = "Blonde",
                    artworkUrl = null,
                    createdAt = 1234L
                )
            ),
            rankingSubjects = listOf(
                RankingSubjectExport(
                    id = "subject-1",
                    scoreTenths = 87,
                    elo = 1240.5,
                    wins = 8,
                    losses = 2,
                    skips = 1,
                    lastRatedAt = 5678L,
                    tombstone = TombstoneExport(
                        sourceType = "SPOTIFY",
                        externalId = "source-1",
                        normalizedTitle = "nights",
                        normalizedArtist = "frank ocean",
                        scoreTenths = 87,
                        seedElo = 1456.0,
                        deletedAt = 9999L,
                        suppressedExternalId = "duplicate-source-1",
                        suppressedSourceType = "SPOTIFY",
                        suppressedNormalizedTitle = "nights duplicate",
                        suppressedNormalizedArtist = "frank ocean"
                    )
                )
            ),
            rankingSettings = RankingSettingsExport(
                autoPlayMatchupPreviews = false,
                showTips = false,
                presentation = RankingPresentation.LIST.name
            )
        )

        val encoded = porter.encode(payload)
        val decoded = porter.decode(encoded)

        assertTrue(encoded.contains("Nights"))
        assertEquals(payload, decoded)

        val restoredSubject = payload.toEntities().subjects.single().toDomain()
        val tombstone = restoredSubject.tombstone
        assertNotNull(tombstone)
        assertEquals(87, tombstone?.scoreTenths)
        assertEquals(9999L, tombstone?.deletedAt)
        assertEquals("duplicate-source-1", tombstone?.suppressedExternalId)
        assertEquals("SPOTIFY", tombstone?.suppressedSourceType?.name)
        assertEquals("nights duplicate", tombstone?.suppressedNormalizedTitle)
        assertEquals("frank ocean", tombstone?.suppressedNormalizedArtist)
        assertEquals("nights", restoredSubject.normalizedTitle)
        assertEquals("frank ocean", restoredSubject.normalizedArtist)
    }

    @Test
    fun `normalizes imported blank titles and artists`() {
        val payload = ExportPayload(
            songs = listOf(
                SongExport(
                    id = "song-2",
                    externalId = null,
                    sourceType = "IMPORT",
                    title = "",
                    artist = "",
                    album = "",
                    artworkUrl = null,
                    createdAt = 0L
                )
            )
        )

        val entities = payload.toEntities()

        assertEquals("Untitled track", entities.songs.single().title)
        assertEquals("Unknown artist", entities.songs.single().artist)
        assertEquals("song-2", entities.subjects.single().id)
        assertEquals(null, entities.subjects.single().scoreTenths)
    }

    @Test
    fun `recomputes imported Elo and counters from matchup events`() {
        val entities = ExportEntities(
            songs = emptyList(),
            subjects = listOf(
                RankingSubjectEntity(id = "one", scoreTenths = 80, elo = 1.0, wins = 99),
                RankingSubjectEntity(id = "two", scoreTenths = 80, elo = 2.0, losses = 99)
            ),
            events = listOf(
                MatchupEventEntity(
                    sequenceId = 1L,
                    occurredAt = 1L,
                    firstSubjectId = "one",
                    secondSubjectId = "two",
                    outcome = "WIN",
                    winnerSubjectId = "one",
                    loserSubjectId = "two",
                    winnerEffectiveK = 64.0,
                    loserEffectiveK = 40.0
                )
            ),
            settings = RankingSettingsEntity(),
            appStats = AppStatsEntity(matchCount = 99, skipCount = 99)
        )

        val recomputed = entities.recomputeDerivedState(EloMatchupEngine())
        val one = recomputed.subjects.first { it.id == "one" }
        val two = recomputed.subjects.first { it.id == "two" }

        assertEquals(1432.0, one.elo, 0.000001)
        assertEquals(1380.0, two.elo, 0.000001)
        assertEquals(1, one.wins)
        assertEquals(1, two.losses)
        assertEquals(AppStatsEntity(matchCount = 1), recomputed.appStats)
    }

    @Test
    fun `maps empty export payload to slice one defaults`() {
        val entities = ExportPayload().toEntities()

        assertEquals(emptyList<SongEntity>(), entities.songs)
        assertEquals(emptyList<RankingSubjectEntity>(), entities.subjects)
        assertEquals(emptyList<MatchupEventEntity>(), entities.events)
        assertEquals(RankingSettingsEntity(), entities.settings)
        assertEquals(AppStatsEntity(), entities.appStats)
    }

    @Test
    fun `normalizes unknown imported source types to import`() {
        val payload = ExportPayload(
            songs = listOf(
                SongExport(
                    id = "song-3",
                    externalId = null,
                    sourceType = "APPLE_MUSIC",
                    title = "Song",
                    artist = "Artist",
                    album = "",
                    artworkUrl = null,
                    createdAt = 0L
                )
            )
        )

        val entities = payload.toEntities()

        assertEquals("IMPORT", entities.songs.single().sourceType)
        assertEquals("IMPORT", entities.subjects.single().sourceType)
    }

    @Test
    fun `normalizes unknown matchup outcomes without losing the event`() {
        val payload = ExportPayload(
            matchupEvents = listOf(
                com.songladder.android.domain.model.MatchupEventExport(
                    sequenceId = 4L,
                    occurredAt = 10L,
                    firstSubjectId = "one",
                    secondSubjectId = "two",
                    outcome = "DRAW"
                )
            )
        )

        val event = payload.toEntities().events.single().toDomain()

        assertEquals(MatchupOutcome.UNKNOWN, event.outcome)
        assertEquals(4L, event.sequenceId)
    }

    @Test
    fun `round trips all export entities`() {
        val song = SongEntity(
            id = "song-1",
            rankingSubjectId = "subject-1",
            externalId = "external-1",
            sourceType = "ITUNES",
            title = "Nights",
            artist = "Frank Ocean",
            album = "Blonde",
            artworkUrl = "https://example.test/art.jpg",
            createdAt = 1234L
        )
        val subject = RankingSubjectEntity(
            id = "subject-1",
            scoreTenths = 87,
            elo = 1240.5,
            wins = 8,
            losses = 2,
            skips = 1,
            lastRatedAt = 5678L,
            responsivenessEpoch = "EDITED",
            completedMatchupsInEpoch = 3,
            sourceType = "ITUNES",
            externalId = "external-1",
            normalizedTitle = "nights",
            normalizedArtist = "frank ocean"
        )
        val tombstoneSubject = RankingSubjectEntity(
            id = "subject-2",
            sourceType = "ITUNES",
            normalizedTitle = "old nights",
            normalizedArtist = "frank ocean",
            tombstoneDeletedAt = 9999L,
            tombstoneSourceType = "ITUNES",
            tombstoneExternalId = "external-2",
            tombstoneScoreTenths = 87,
            tombstoneSeedElo = 1456.0
        )
        val event = MatchupEventEntity(
            sequenceId = 1L,
            occurredAt = 6000L,
            firstSubjectId = "subject-1",
            secondSubjectId = "subject-2",
            outcome = "WIN",
            winnerSubjectId = "subject-1",
            loserSubjectId = "subject-2",
            winnerEffectiveK = 64.0,
            loserEffectiveK = 40.0
        )
        val payload = ExportEntities(
            songs = listOf(song),
            subjects = listOf(
                subject,
                tombstoneSubject
            ),
            events = listOf(event),
            settings = RankingSettingsEntity(
                autoPlayMatchupPreviews = false,
                showTips = false,
                presentation = "LIST"
            ),
            appStats = AppStatsEntity(matchCount = 1, skipCount = 0)
        ).toPayload()

        val decoded = porter.decode(porter.encode(payload))
        decoded.validateForImport()
        val restored = decoded.toEntities()

        assertEquals(listOf(song), restored.songs)
        assertEquals(listOf(subject, tombstoneSubject), restored.subjects)
        assertEquals(listOf(event), restored.events)
        assertEquals(RankingSettingsEntity(autoPlayMatchupPreviews = false, showTips = false, presentation = "LIST"), restored.settings)
        assertEquals(AppStatsEntity(matchCount = 1), restored.appStats)
    }

    @Test
    fun `rejects invalid backup references and score values`() {
        val payload = ExportPayload(
            songs = listOf(
                SongExport(
                    id = "song-1",
                    rankingSubjectId = "missing-subject",
                    sourceType = "MANUAL",
                    title = "Nights",
                    artist = "Frank Ocean",
                    createdAt = 1234L
                )
            ),
            rankingSubjects = listOf(
                RankingSubjectExport(id = "subject-1", scoreTenths = 101)
            )
        )

        try {
            payload.validateForImport()
            fail("Expected invalid backup to be rejected")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("between 10 and 100"))
        }
    }

    @Test
    fun `rejects winner events with missing participants`() {
        val payload = ExportPayload(
            rankingSubjects = listOf(
                RankingSubjectExport(id = "one"),
                RankingSubjectExport(id = "two")
            ),
            matchupEvents = listOf(
                com.songladder.android.domain.model.MatchupEventExport(
                    sequenceId = 1L,
                    occurredAt = 10L,
                    firstSubjectId = "one",
                    secondSubjectId = "two",
                    outcome = "WIN"
                )
            )
        )

        try {
            payload.validateForImport()
            fail("Expected invalid winner event to be rejected")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("winner and loser"))
        }
    }

    @Test
    fun `rejects songs that reference missing ranking subjects`() {
        val payload = ExportPayload(
            songs = listOf(
                SongExport(
                    id = "song-1",
                    rankingSubjectId = "missing-subject",
                    sourceType = "MANUAL",
                    title = "Nights",
                    artist = "Frank Ocean",
                    createdAt = 1234L
                )
            )
        )

        try {
            payload.validateForImport()
            fail("Expected missing ranking subject to be rejected")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("missing ranking subject"))
        }
    }

    @Test
    fun `rejects active songs that share a ranking subject`() {
        val payload = ExportPayload(
            songs = listOf(
                SongExport(
                    id = "song-1",
                    rankingSubjectId = "subject-1",
                    sourceType = "MANUAL",
                    title = "Nights",
                    artist = "Frank Ocean",
                    createdAt = 1234L
                ),
                SongExport(
                    id = "song-2",
                    rankingSubjectId = "subject-1",
                    sourceType = "MANUAL",
                    title = "Pink + White",
                    artist = "Frank Ocean",
                    createdAt = 1235L
                )
            ),
            rankingSubjects = listOf(RankingSubjectExport(id = "subject-1"))
        )

        try {
            payload.validateForImport()
            fail("Expected shared ranking subject to be rejected")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("unique ranking subject"))
        }
    }

    @Test
    fun `rejects skipped events with effective K values`() {
        val payload = ExportPayload(
            rankingSubjects = listOf(
                RankingSubjectExport(id = "one"),
                RankingSubjectExport(id = "two")
            ),
            matchupEvents = listOf(
                com.songladder.android.domain.model.MatchupEventExport(
                    sequenceId = 1L,
                    occurredAt = 10L,
                    firstSubjectId = "one",
                    secondSubjectId = "two",
                    outcome = "SKIP",
                    winnerEffectiveK = 64.0
                )
            )
        )

        try {
            payload.validateForImport()
            fail("Expected invalid skip event to be rejected")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("effective K"))
        }
    }
}
