package com.songladder.android.domain.engine

import com.songladder.android.domain.model.EloMatchupResult
import com.songladder.android.domain.model.EloReplayResult
import com.songladder.android.domain.model.Matchup
import com.songladder.android.domain.model.MatchupEvent
import com.songladder.android.domain.model.MatchupOutcome
import com.songladder.android.domain.model.MatchupSelection
import com.songladder.android.domain.model.RankingSubject
import com.songladder.android.domain.model.ResponsivenessEpoch
import com.songladder.android.domain.model.Song
import com.songladder.android.domain.model.seedEloForScore
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.random.Random

class EloMatchupEngine(
    private val random: Random = Random.Default
) {
    fun pickMatchup(
        songs: List<Song>,
        previousMatchup: Matchup? = null
    ): Matchup? = selectMatchup(
        songs = songs,
        previousMatchup = previousMatchup
    ).matchup

    fun selectMatchup(
        songs: List<Song>,
        events: List<MatchupEvent> = emptyList(),
        displayedMatchups: List<Matchup> = emptyList(),
        displayedMatchupCount: Int = displayedMatchups.size,
        previousMatchup: Matchup? = null,
        continueAnyway: Boolean = false
    ): MatchupSelection {
        if (songs.size < 2) return MatchupSelection(matchup = null)

        val rated = songs.filter { it.scoreTenths != null }
        val unrated = songs.filter { it.scoreTenths == null }
        val matchupCount = if (displayedMatchups.isEmpty()) events.size else displayedMatchupCount
        val unratedInterval = when {
            unrated.size >= 5 -> 2
            unrated.size >= 2 -> 3
            else -> 4
        }
        val includeUnrated = rated.isNotEmpty() &&
            unrated.isNotEmpty() &&
            (matchupCount + 1) % unratedInterval == 0

        val candidatePairs = allPairs(songs).let { pairs ->
            when {
                rated.isEmpty() -> pairs
                includeUnrated -> pairs.filter { it.hasUnrated() }.ifEmpty { pairs }
                rated.size >= 2 -> pairs.filter { it.bothRated() }.ifEmpty { pairs }
                else -> pairs
            }
        }

        val recentDisplayed = displayedMatchups.takeLast(3)
        val blocked = recentDisplayed
            .map { it.pairKey() }
            .ifEmpty {
                events
                    .sortedBy { it.sequenceId }
                    .takeLast(3)
                    .map { it.pairKey() }
            }
            .toMutableSet()
        previousMatchup?.let { blocked += it.pairKey() }
        val temporarilyExcludedSubjectIds = rated
            .groupBy { it.scoreTenths }
            .values
            .filter { group -> group.size == 2 }
            .filter { group ->
                PairKey(
                    group[0].rankingSubjectId,
                    group[1].rankingSubjectId
                ) in blocked
            }
            .flatMap { group -> group.map(Song::rankingSubjectId) }
            .toSet()
        val eligiblePairs = candidatePairs.filterNot { pair ->
            pair.first.rankingSubjectId in temporarilyExcludedSubjectIds ||
                pair.second.rankingSubjectId in temporarilyExcludedSubjectIds
        }
        var availablePairs = eligiblePairs.filterNot { it.key() in blocked }
        if (availablePairs.isEmpty() && !continueAnyway) {
            availablePairs = allPairs(songs)
                .filterNot { pair ->
                    pair.first.rankingSubjectId in temporarilyExcludedSubjectIds ||
                        pair.second.rankingSubjectId in temporarilyExcludedSubjectIds
                }
                .filterNot { it.key() in blocked }
        }

        if (availablePairs.isEmpty() && continueAnyway) {
            val forcedCandidates = candidatePairs.filter { it.key() in blocked }.ifEmpty { candidatePairs }
            val forcedPair = forcedCandidates[random.nextInt(forcedCandidates.size)]
            return MatchupSelection(matchup = Matchup(left = forcedPair.first, right = forcedPair.second))
        }
        val selectablePairs = when {
            availablePairs.isNotEmpty() -> availablePairs
            else -> return MatchupSelection(matchup = null, caughtUp = candidatePairs.isNotEmpty())
        }

        val preferredPairs = preferredPairs(selectablePairs, includeUnrated)
        val exposureIds = if (displayedMatchups.isEmpty()) {
            events.flatMap { event -> listOf(event.firstSubjectId, event.secondSubjectId) }
        } else {
            displayedMatchups.flatMap { matchup ->
                listOf(matchup.left.rankingSubjectId, matchup.right.rankingSubjectId)
            }
        }
        val exposure = exposureIds
            .groupingBy { it }
            .eachCount()
        val selected = chooseTie(preferredPairs, exposure)
        return MatchupSelection(
            matchup = selected?.let { Matchup(left = it.first, right = it.second) },
            caughtUp = false
        )
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
                completedMatchupsInEpoch = winner.completedMatchupsInEpoch + 1
            ),
            loser = loser.copy(
                elo = loser.elo + loserEffectiveK * (0 - loserExpected),
                losses = loser.losses + 1,
                completedMatchupsInEpoch = loser.completedMatchupsInEpoch + 1
            ),
            winnerEffectiveK = winnerEffectiveK,
            loserEffectiveK = loserEffectiveK,
            ratedAt = ratedAt
        )
    }

    fun replay(
        subjects: List<RankingSubject>,
        events: List<MatchupEvent>
    ): List<RankingSubject> = replayWithHistory(subjects, events).subjects

    /**
     * Same replay as [replay], but also returns each subject's elo value
     * immediately after each of its own WIN events, in chronological order.
     * Used by [com.songladder.android.domain.engine.SuggestionEngine] to check
     * whether a subject's implied score has stabilized over its recent matchups.
     */
    fun replayWithHistory(
        subjects: List<RankingSubject>,
        events: List<MatchupEvent>
    ): EloReplayResult {
        val replayed = subjects.associate { subject ->
            subject.id to subject.copy(
                elo = seedEloForScore(subject.scoreTenths),
                wins = 0,
                losses = 0,
                skips = 0,
                completedMatchupsInEpoch = 0
            )
        }.toMutableMap()
        val history = mutableMapOf<String, MutableList<Double>>()

        events.sortedBy { it.sequenceId }.forEach { event ->
            when (event.outcome) {
                MatchupOutcome.SKIP -> {
                    incrementSubject(replayed, event.firstSubjectId) { it.copy(skips = it.skips + 1) }
                    incrementSubject(replayed, event.secondSubjectId) { it.copy(skips = it.skips + 1) }
                }

                MatchupOutcome.WIN -> {
                    val winnerId = requireNotNull(event.winnerSubjectId) {
                        "Winner events must include a winner subject."
                    }
                    val loserId = requireNotNull(event.loserSubjectId) {
                        "Winner events must include a loser subject."
                    }
                    applyWinEvent(replayed, event, winnerId, loserId)
                    history.getOrPut(winnerId) { mutableListOf() }.add(requireNotNull(replayed[winnerId]).elo)
                    history.getOrPut(loserId) { mutableListOf() }.add(requireNotNull(replayed[loserId]).elo)
                }

                MatchupOutcome.UNKNOWN -> Unit
            }
        }

        val finalSubjects = subjects.map { original ->
            val current = requireNotNull(replayed[original.id])
                current.copy(
                    responsivenessEpoch = original.responsivenessEpoch,
                completedMatchupsInEpoch = events
                    .asSequence()
                    .filter { it.outcome == MatchupOutcome.WIN }
                    .filter { event ->
                        event.winnerSubjectId == original.id || event.loserSubjectId == original.id
                    }
                    .count { event -> event.sequenceId > original.responsivenessEpochSequence }
            )
        }
        return EloReplayResult(subjects = finalSubjects, eloHistoryBySubject = history)
    }

    private fun applyWinEvent(
        replayed: MutableMap<String, RankingSubject>,
        event: MatchupEvent,
        winnerId: String,
        loserId: String
    ) {
        val winner = requireNotNull(replayed[winnerId]) {
            "Winner subject $winnerId was not found."
        }
        val loser = requireNotNull(replayed[loserId]) {
            "Loser subject $loserId was not found."
        }
        val winnerK = event.winnerEffectiveK ?: effectiveK(winner)
        val loserK = event.loserEffectiveK ?: effectiveK(loser)
        val winnerExpected = expectedScore(winner.elo, loser.elo)
        val loserExpected = expectedScore(loser.elo, winner.elo)
        replayed[winnerId] = winner.copy(
            elo = winner.elo + winnerK * (1 - winnerExpected),
            wins = winner.wins + 1,
            completedMatchupsInEpoch = winner.completedMatchupsInEpoch + 1
        )
        replayed[loserId] = loser.copy(
            elo = loser.elo + loserK * (0 - loserExpected),
            losses = loser.losses + 1,
            completedMatchupsInEpoch = loser.completedMatchupsInEpoch + 1
        )
    }

    fun effectiveK(subject: RankingSubject): Double = when (subject.responsivenessEpoch) {
        ResponsivenessEpoch.NEW -> 16.0 + 48.0 * exp(-subject.completedMatchupsInEpoch / 4.0)
        ResponsivenessEpoch.EDITED -> 16.0 + 24.0 * exp(-subject.completedMatchupsInEpoch / 4.0)
    }

    private fun preferredPairs(
        pairs: List<Pair<Song, Song>>,
        includeUnrated: Boolean
    ): List<Pair<Song, Song>> {
        if (includeUnrated) {
            val unratedPairs = pairs.filter { it.hasUnrated() }.ifEmpty { pairs }
            val minimumLifetimeBattles = unratedPairs.minOf { it.lifetimeBattles() }
            return unratedPairs.filter { it.lifetimeBattles() == minimumLifetimeBattles }
        }

        val ratedPairs = pairs.filter { it.bothRated() }
        if (ratedPairs.isEmpty()) return pairs

        val exactScorePairs = ratedPairs.filter { it.first.scoreTenths == it.second.scoreTenths }
        val scoredPairs = exactScorePairs.ifEmpty { ratedPairs }
        val minimumScoreDifference = scoredPairs.minOf { it.scoreDifference() }
        return scoredPairs.filter { it.scoreDifference() == minimumScoreDifference }
    }

    private fun chooseTie(
        pairs: List<Pair<Song, Song>>,
        exposure: Map<String, Int>
    ): Pair<Song, Song>? {
        if (pairs.isEmpty()) return null
        val minimumEloDifference = pairs.minOf { abs(it.first.elo - it.second.elo) }
        val closestElo = pairs.filter { abs(it.first.elo - it.second.elo) == minimumEloDifference }
        val minimumExposure = closestElo.minOf { exposure(it, exposure) }
        val lowestExposure = closestElo.filter { exposure(it, exposure) == minimumExposure }
        return lowestExposure[random.nextInt(lowestExposure.size)]
    }

    private fun exposure(pair: Pair<Song, Song>, exposure: Map<String, Int>): Int =
        exposure.getOrDefault(pair.first.rankingSubjectId, 0) +
            exposure.getOrDefault(pair.second.rankingSubjectId, 0)

    private fun allPairs(songs: List<Song>): List<Pair<Song, Song>> = buildList {
        songs.forEachIndexed { firstIndex, first ->
            songs.drop(firstIndex + 1).forEach { second -> add(first to second) }
        }
    }

    private fun incrementSubject(
        subjects: MutableMap<String, RankingSubject>,
        subjectId: String,
        update: (RankingSubject) -> RankingSubject
    ) {
        subjects[subjectId]?.let { subjects[subjectId] = update(it) }
    }

    private fun expectedScore(playerElo: Double, opponentElo: Double): Double =
        1.0 / (1 + 10.0.pow((opponentElo - playerElo) / 400.0))

    private fun Pair<Song, Song>.bothRated(): Boolean =
        first.scoreTenths != null && second.scoreTenths != null

    private fun Pair<Song, Song>.hasUnrated(): Boolean =
        first.scoreTenths == null || second.scoreTenths == null

    private fun Pair<Song, Song>.lifetimeBattles(): Int =
        (if (first.scoreTenths == null) first.wins + first.losses else 0) +
            (if (second.scoreTenths == null) second.wins + second.losses else 0)

    private fun Pair<Song, Song>.scoreDifference(): Int =
        abs((first.scoreTenths ?: 55) - (second.scoreTenths ?: 55))

    private fun Pair<Song, Song>.key(): PairKey =
        PairKey(first.rankingSubjectId, second.rankingSubjectId)

    private fun Matchup.pairKey(): PairKey = PairKey(left.rankingSubjectId, right.rankingSubjectId)

    private fun MatchupEvent.pairKey(): PairKey = PairKey(firstSubjectId, secondSubjectId)

    private data class PairKey(val first: String, val second: String) {
        init {
            require(first != second) { "A matchup pair requires two distinct subjects." }
        }

        private val normalized: Set<String> = setOf(first, second)

        override fun equals(other: Any?): Boolean =
            other is PairKey && normalized == other.normalized

        override fun hashCode(): Int = normalized.hashCode()
    }
}
