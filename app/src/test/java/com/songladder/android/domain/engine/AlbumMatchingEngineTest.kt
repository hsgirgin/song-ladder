package com.songladder.android.domain.engine

import com.songladder.android.domain.model.AlbumMatchStatus
import com.songladder.android.domain.model.AlbumReleaseCandidate
import com.songladder.android.domain.model.AlbumReleaseLookup
import com.songladder.android.domain.model.AlbumReleaseTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumMatchingEngineTest {
    private val engine = AlbumMatchingEngine()

    @Test
    fun `an exact title, artist, and track count match auto-matches`() {
        val outcome = engine.classifyMatch(
            localTitle = "Blonde",
            localArtist = "Frank Ocean",
            ownedTrackTitles = List(17) { "Track ${it + 1}" },
            candidates = listOf(
                AlbumReleaseCandidate(
                    collectionId = "collection-1",
                    collectionName = "Blonde",
                    artistName = "Frank Ocean",
                    trackCount = 17
                )
            )
        )

        assertEquals(AlbumMatchStatus.AUTO_MATCHED, outcome.status)
        assertEquals("collection-1", outcome.bestCandidate?.collectionId)
    }

    @Test
    fun `an unrelated artist and title never matches`() {
        val outcome = engine.classifyMatch(
            localTitle = "Blonde",
            localArtist = "Frank Ocean",
            ownedTrackTitles = listOf("Nights"),
            candidates = listOf(
                AlbumReleaseCandidate(
                    collectionId = "collection-2",
                    collectionName = "Random Access Memories",
                    artistName = "Daft Punk",
                    trackCount = 13
                )
            )
        )

        assertEquals(AlbumMatchStatus.NO_MATCH, outcome.status)
        assertNull(outcome.bestCandidate)
    }

    @Test
    fun `no candidates at all is a no-match`() {
        val outcome = engine.classifyMatch(
            localTitle = "Blonde",
            localArtist = "Frank Ocean",
            ownedTrackTitles = emptyList(),
            candidates = emptyList()
        )

        assertEquals(AlbumMatchStatus.NO_MATCH, outcome.status)
    }

    @Test
    fun `two duplicate-looking listings need review rather than an automatic pick`() {
        // Same title, artist, and track count under two different collection ids - e.g.
        // a duplicate iTunes listing. Nothing distinguishes them, so this must not
        // silently auto-pick one.
        val outcome = engine.classifyMatch(
            localTitle = "Blonde",
            localArtist = "Frank Ocean",
            ownedTrackTitles = List(17) { "Track ${it + 1}" },
            candidates = listOf(
                AlbumReleaseCandidate(
                    collectionId = "collection-a",
                    collectionName = "Blonde",
                    artistName = "Frank Ocean",
                    trackCount = 17
                ),
                AlbumReleaseCandidate(
                    collectionId = "collection-b",
                    collectionName = "Blonde",
                    artistName = "Frank Ocean",
                    trackCount = 17
                )
            )
        )

        assertEquals(AlbumMatchStatus.NEEDS_REVIEW, outcome.status)
    }

    @Test
    fun `a plausible but not confident match needs review instead of auto-matching`() {
        val outcome = engine.classifyMatch(
            localTitle = "Blonde",
            localArtist = "Frank Ocean",
            ownedTrackTitles = emptyList(),
            candidates = listOf(
                AlbumReleaseCandidate(
                    collectionId = "collection-3",
                    collectionName = "Blonde Extended Edition",
                    artistName = "Frank Ocean",
                    trackCount = null
                )
            )
        )

        assertEquals(AlbumMatchStatus.NEEDS_REVIEW, outcome.status)
    }

    @Test
    fun `missing tracks excludes titles the user already owns`() {
        val lookup = AlbumReleaseLookup(
            collectionId = "collection-1",
            collectionName = "Blonde",
            artistName = "Frank Ocean",
            trackCount = 3,
            tracks = listOf(
                AlbumReleaseTrack(trackId = "1", title = "Nikes", trackNumber = 1),
                AlbumReleaseTrack(trackId = "2", title = "Ivy", trackNumber = 2),
                AlbumReleaseTrack(trackId = "3", title = "Pink + White", trackNumber = 3)
            )
        )

        val missing = engine.missingTracks(ownedTrackTitles = listOf("Nikes", "ivy"), release = lookup)

        assertEquals(listOf("Pink + White"), missing.map { it.title })
    }

    @Test
    fun `missing tracks is empty when every track is owned`() {
        val lookup = AlbumReleaseLookup(
            collectionId = "collection-1",
            collectionName = "Blonde",
            artistName = "Frank Ocean",
            trackCount = 1,
            tracks = listOf(AlbumReleaseTrack(trackId = "1", title = "Nikes", trackNumber = 1))
        )

        assertTrue(engine.missingTracks(listOf("Nikes"), lookup).isEmpty())
    }
}
