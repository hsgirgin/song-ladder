package com.songladder.android.domain.engine

import com.songladder.android.domain.model.Matchup
import com.songladder.android.domain.model.EloMatchupResult
import com.songladder.android.domain.model.RankingSubject
import com.songladder.android.domain.model.ResponsivenessEpoch
import com.songladder.android.domain.model.Song
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
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

    fun updateRatings(
        winner: RankingSubject,
        loser: RankingSubject,
        ratedAt: Long = System.currentTimeMillis()
    ): EloMatchupResult {
        require(winner.id != loser.id) { "A matchup requires two distinct ranking subjects." }
        val winnerEffectiveK = effectiveK(winner)
        val loserEffectiveK = effectiveK(loser)
        val winnerExpected = expectedScore(winner.elo, loser.elo)
        val loserExpected = expectedScore(loser.elo, winner.elo)
        return EloMatchupResult(
            winner = winner.copy(
                elo = winner.elo + winnerEffectiveK * (1 - winnerExpected),
                wins = winner.wins + 1,
                completedMatchupsInEpoch = winner.completedMatchupsInEpoch + 1,
                lastRatedAt = ratedAt
            ),
            loser = loser.copy(
                elo = loser.elo + loserEffectiveK * (0 - loserExpected),
                losses = loser.losses + 1,
                completedMatchupsInEpoch = loser.completedMatchupsInEpoch + 1,
                lastRatedAt = ratedAt
            ),
            winnerEffectiveK = winnerEffectiveK,
            loserEffectiveK = loserEffectiveK,
            ratedAt = ratedAt
        )
    }

    private fun effectiveK(subject: RankingSubject): Double =
        when (subject.responsivenessEpoch) {
            ResponsivenessEpoch.NEW -> 16.0 + 48.0 * exp(-subject.completedMatchupsInEpoch / 4.0)
            ResponsivenessEpoch.EDITED -> 16.0 + 24.0 * exp(-subject.completedMatchupsInEpoch / 4.0)
        }

    private fun expectedScore(playerElo: Double, opponentElo: Double): Double =
        1.0 / (1 + 10.0.pow((opponentElo - playerElo) / 400.0))
}
