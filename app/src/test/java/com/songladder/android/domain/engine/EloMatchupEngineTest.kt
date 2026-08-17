package com.songladder.android.domain.engine

import com.songladder.android.domain.model.BASE_RATING
import com.songladder.android.domain.model.RankingSubject
import com.songladder.android.domain.model.ResponsivenessEpoch
import com.songladder.android.domain.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class EloMatchupEngineTest {
    private val engine = EloMatchupEngine()

    @Test
    fun `returns null when fewer than two songs exist`() {
        assertNull(engine.pickMatchup(emptyList()))
        assertNull(engine.pickMatchup(listOf(song(id = "one"))))
    }

    @Test
    fun `returns two distinct songs for a matchup`() {
        val matchup = engine.pickMatchup(
            listOf(song(id = "one"), song(id = "two"), song(id = "three"))
        )

        assertNotNull(matchup)
        checkNotNull(matchup)
        assertNotEquals(matchup.left.id, matchup.right.id)
    }

    @Test
    fun `winner and loser use independent double precision responsiveness factors`() {
        val winner = subject(id = "one", elo = BASE_RATING.toDouble())
        val loser = subject(
            id = "two",
            elo = BASE_RATING.toDouble(),
            responsivenessEpoch = ResponsivenessEpoch.EDITED
        )

        val result = engine.updateRatings(winner, loser, ratedAt = 1234L)

        assertEquals(64.0, result.winnerEffectiveK, 0.000001)
        assertEquals(40.0, result.loserEffectiveK, 0.000001)
        assertEquals(1232.0, result.winner.elo, 0.000001)
        assertEquals(1180.0, result.loser.elo, 0.000001)
        assertEquals(1, result.winner.wins)
        assertEquals(1, result.loser.losses)
        assertEquals(1, result.winner.completedMatchupsInEpoch)
        assertEquals(1234L, result.winner.lastRatedAt)
        assertEquals(1234L, result.loser.lastRatedAt)
    }

    private fun subject(
        id: String,
        elo: Double,
        responsivenessEpoch: ResponsivenessEpoch = ResponsivenessEpoch.NEW
    ): RankingSubject = RankingSubject(
        id = id,
        elo = elo,
        responsivenessEpoch = responsivenessEpoch
    )

    private fun song(id: String, rating: Int = BASE_RATING): Song {
        return Song(
            id = id,
            title = id,
            artist = "Artist $id",
            album = "",
            artworkUrl = null,
            createdAt = 0L,
            rating = rating
        )
    }
}
