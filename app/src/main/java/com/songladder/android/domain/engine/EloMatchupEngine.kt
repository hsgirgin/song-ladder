package com.songladder.android.domain.engine

import com.songladder.android.domain.model.K_FACTOR
import com.songladder.android.domain.model.Matchup
import com.songladder.android.domain.model.Song
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

class EloMatchupEngine {
    fun pickMatchup(
        songs: List<Song>,
        previousMatchup: Matchup? = null
    ): Matchup? {
        if (songs.size < 2) return null

        val ranked = songs.sortedWith(compareByDescending<Song> { it.rating }.thenBy { it.title })
        val leftIndex = Random.nextInt(ranked.size)
        val window = ranked.indices.filter { it != leftIndex }.sortedBy { abs(it - leftIndex) }.take(3)
        val rightIndex = window.random()
        val candidate = Matchup(left = ranked[leftIndex], right = ranked[rightIndex])

        if (previousMatchup == null) return candidate

        val sameAsPrevious =
            candidate.left.id == previousMatchup.left.id && candidate.right.id == previousMatchup.right.id ||
                candidate.left.id == previousMatchup.right.id && candidate.right.id == previousMatchup.left.id

        return if (sameAsPrevious && songs.size > 2) {
            pickMatchup(songs, previousMatchup = null)
        } else {
            candidate
        }
    }

    fun updateRatings(winner: Song, loser: Song): Pair<Song, Song> {
        val winnerExpected = expectedScore(winner.rating, loser.rating)
        val loserExpected = expectedScore(loser.rating, winner.rating)
        return winner.copy(
            rating = (winner.rating + K_FACTOR * (1 - winnerExpected)).roundToInt(),
            wins = winner.wins + 1,
            lastRankedAt = System.currentTimeMillis()
        ) to loser.copy(
            rating = (loser.rating + K_FACTOR * (0 - loserExpected)).roundToInt(),
            losses = loser.losses + 1,
            lastRankedAt = System.currentTimeMillis()
        )
    }

    private fun expectedScore(playerRating: Int, opponentRating: Int): Double {
        return 1.0 / (1 + 10.0.pow((opponentRating - playerRating) / 400.0))
    }
}
