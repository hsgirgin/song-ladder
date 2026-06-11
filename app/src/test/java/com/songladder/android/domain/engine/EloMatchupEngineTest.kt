package com.songladder.android.domain.engine

import com.songladder.android.domain.model.BASE_RATING
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
    fun `winner gains rating and loser loses rating`() {
        val winner = song(id = "one", rating = BASE_RATING)
        val loser = song(id = "two", rating = BASE_RATING)

        val (winnerUpdated, loserUpdated) = engine.updateRatings(winner, loser)

        assertEquals(1216, winnerUpdated.rating)
        assertEquals(1184, loserUpdated.rating)
        assertEquals(1, winnerUpdated.wins)
        assertEquals(1, loserUpdated.losses)
    }

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
