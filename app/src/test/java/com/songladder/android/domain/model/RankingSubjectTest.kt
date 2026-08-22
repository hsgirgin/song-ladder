package com.songladder.android.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class RankingSubjectTest {
    @Test
    fun `score seed uses explicit score and defaults unrated songs to base Elo`() {
        assertEquals(840.0, seedEloForScore(10), 0.0)
        assertEquals(1200.0, seedEloForScore(null), 0.0)
        assertEquals(1560.0, seedEloForScore(100), 0.0)
    }

    @Test
    fun `score for elo is the inverse of seed elo for score, clamped to the valid range`() {
        assertEquals(MIN_SCORE_TENTHS, scoreTenthsForElo(840.0))
        assertEquals(55, scoreTenthsForElo(BASE_ELO))
        assertEquals(MAX_SCORE_TENTHS, scoreTenthsForElo(1560.0))
    }

    @Test
    fun `score for elo clamps extreme elo values to the valid score range`() {
        assertEquals(MIN_SCORE_TENTHS, scoreTenthsForElo(BASE_ELO - 1000.0))
        assertEquals(MAX_SCORE_TENTHS, scoreTenthsForElo(BASE_ELO + 1000.0))
    }

    @Test
    fun `score first ordering uses score then Elo then recent rating and stable identity`() {
        val songs = listOf(
            song(id = "tie-low", scoreTenths = 80, elo = 1200.0, lastRatedAt = 2L),
            song(id = "higher-score", scoreTenths = 90, elo = 1000.0),
            song(id = "tie-high", scoreTenths = 80, elo = 1300.0),
            song(id = "unrated", scoreTenths = null, elo = 1600.0)
        )

        assertEquals(
            listOf("higher-score", "tie-high", "tie-low", "unrated"),
            songs.sortedWith(scoreFirstComparator()).map { it.id }
        )
    }

    private fun song(
        id: String,
        scoreTenths: Int?,
        elo: Double,
        lastRatedAt: Long? = null
    ) = Song(
        id = id,
        title = id,
        artist = "Artist",
        createdAt = 0L,
        scoreTenths = scoreTenths,
        elo = elo,
        lastRankedAt = lastRatedAt
    )
}
