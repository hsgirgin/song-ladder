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
import com.songladder.android.domain.model.MAX_ELO
import com.songladder.android.domain.model.MIN_ELO
import com.songladder.android.domain.model.libraryAnchorScoreTenths
import com.songladder.android.domain.model.seedEloForScore
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.random.Random

class EloMatchupEngine(
    private val random: Random = Random.Default
) {
    companion object {
        private const val LARGE_UNRATED_BACKLOG = 5
        private const val MODERATE_UNRATED_BACKLOG = 2
        private const val MASSIVE_UNRATED_BACKLOG = 15
        private const val FREQUENT_UNRATED_INTERVAL = 2
        private const val MODERATE_UNRATED_INTERVAL = 3
        private const val SPARSE_UNRATED_INTERVAL = 4

        // Songs with a few more battles than the current minimum still get a look-in, so a
        // partially-battled unrated song isn't locked out forever by a steady trickle of
        // brand-new zero-battle imports.
        private const val UNRATED_BATTLE_TOLERANCE = 2

        // How many trailing rated-opponent comparisons count toward "in progress" - matches
        // SuggestionEngine.STABILITY_WINDOW, since that's the number a song actually needs
        // before it can ever produce a suggestion. Kept as a local literal (rather than a
        // cross-module reference) since the two engines are allowed to evolve independently.
        private const val SUGGESTION_STABILITY_WINDOW = 5

        // Unrated songs already partway toward a suggestion stay within this many
        // comparisons of the current leader to still be picked, so a couple of songs finish
        // out together rather than strictly one at a time.
        private const val PROGRESS_CONCENTRATION_TOLERANCE = 1

        // The repeat-avoidance window scales with the eligible pool so a small cluster of
        // similarly-scored songs isn't limited to the same fixed memory as a large one, while a
        // floor keeps small libraries behaving exactly as before and a cap keeps the window from
        // starving small pools into forced repeats.
        private const val MIN_BLOCK_WINDOW = 3
        // Visible to callers so they know how much matchup history to retain for
        // selectMatchup's displayedMatchups window to actually scale up to this cap.
        internal const val MAX_BLOCK_WINDOW = 8
        private const val BLOCK_WINDOW_DIVISOR = 2

        // Pairs within this many score-tenths of the tightest cluster are still eligible, so a
        // cluster too small to avoid repeats on its own has other close-enough pairs to draw on.
        private const val SCORE_DIFFERENCE_TOLERANCE = 5
    }

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
        // At a very large backlog, anchor every matchup to a rated song so every comparison
        // counts toward SuggestionEngine's stabilization window -- no rated-vs-rated and no
        // unrated-vs-unrated matchups until the backlog drops back below this size.
        val forceRatedVsUnrated = rated.isNotEmpty() && unrated.size >= MASSIVE_UNRATED_BACKLOG
        // Larger unrated backlogs surface unrated songs more frequently; the interval tapers
        // back down as the backlog clears.
        val unratedInterval = when {
            unrated.size >= LARGE_UNRATED_BACKLOG -> FREQUENT_UNRATED_INTERVAL
            unrated.size >= MODERATE_UNRATED_BACKLOG -> MODERATE_UNRATED_INTERVAL
            else -> SPARSE_UNRATED_INTERVAL
        }
        val includeUnrated = forceRatedVsUnrated ||
            (rated.isNotEmpty() && unrated.isNotEmpty() && (matchupCount + 1) % unratedInterval == 0)

        val candidatePairs = allPairs(songs).let { pairs ->
            when {
                rated.isEmpty() -> pairs
                forceRatedVsUnrated -> pairs.filter { it.isMixed() }
                includeUnrated -> pairs.preferringUnrated()
                rated.size >= 2 -> pairs.filter { it.bothRated() }.ifEmpty { pairs }
                else -> pairs
            }
        }

        val sortedEvents by lazy { events.sortedBy { it.sequenceId } }
        val blockWindow = (candidatePairs.size / BLOCK_WINDOW_DIVISOR)
            .coerceIn(MIN_BLOCK_WINDOW, MAX_BLOCK_WINDOW)
        val recentDisplayed = displayedMatchups.takeLast(blockWindow)
        val blocked = recentDisplayed
            .map { it.pairKey() }
            .ifEmpty {
                sortedEvents
                    .takeLast(blockWindow)
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
            // Relaxing the block/cooldown filters must not also relax the mixed-only
            // requirement below, or a stale cooldown on the rated side could let an
            // unrated-vs-unrated pair through while the backlog is still massive.
            val relaxedPairs = if (forceRatedVsUnrated) allPairs(songs).filter { it.isMixed() } else allPairs(songs)
            availablePairs = relaxedPairs
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

        val unratedStreaks: Map<String, Int> by lazy {
            val songsById = songs.associateBy { it.rankingSubjectId }
            val sortedWinEvents = events
                .asSequence()
                .filter { it.outcome == MatchupOutcome.WIN }
                .sortedBy { it.sequenceId }
                .toList()
            unrated.associate { it.rankingSubjectId to ratedStreak(it.rankingSubjectId, songsById, sortedWinEvents) }
        }
        val preferredPairs = preferredPairs(selectablePairs, includeUnrated, unratedStreaks)
        val lastSeen = if (displayedMatchups.isEmpty()) {
            val relevantSubjectIds = selectablePairs
                .flatMapTo(mutableSetOf()) { listOf(it.first.rankingSubjectId, it.second.rankingSubjectId) }
            val seen = mutableMapOf<String, Long>()
            for (event in sortedEvents.asReversed()) {
                if (seen.size >= relevantSubjectIds.size) break
                if (event.firstSubjectId in relevantSubjectIds) seen.putIfAbsent(event.firstSubjectId, event.sequenceId)
                if (event.secondSubjectId in relevantSubjectIds) seen.putIfAbsent(event.secondSubjectId, event.sequenceId)
            }
            seen
        } else {
            displayedMatchups.withIndex()
                .flatMap { (index, matchup) ->
                    listOf(
                        matchup.left.rankingSubjectId to index.toLong(),
                        matchup.right.rankingSubjectId to index.toLong()
                    )
                }
                .toMap()
        }
        val selected = chooseTie(preferredPairs, lastSeen)
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
                elo = (winner.elo + winnerEffectiveK * (1 - winnerExpected)).coerceIn(MIN_ELO, MAX_ELO),
                wins = winner.wins + 1,
                completedMatchupsInEpoch = winner.completedMatchupsInEpoch + 1
            ),
            loser = loser.copy(
                elo = (loser.elo + loserEffectiveK * (0 - loserExpected)).coerceIn(MIN_ELO, MAX_ELO),
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
        val anchorTenths = libraryAnchorScoreTenths(subjects)
        val replayed = subjects.associate { subject ->
            subject.id to subject.copy(
                elo = seedEloForScore(subject.scoreTenths, anchorTenths),
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
            elo = (winner.elo + winnerK * (1 - winnerExpected)).coerceIn(MIN_ELO, MAX_ELO),
            wins = winner.wins + 1,
            completedMatchupsInEpoch = winner.completedMatchupsInEpoch + 1
        )
        replayed[loserId] = loser.copy(
            elo = (loser.elo + loserK * (0 - loserExpected)).coerceIn(MIN_ELO, MAX_ELO),
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
        includeUnrated: Boolean,
        unratedStreaks: Map<String, Int>
    ): List<Pair<Song, Song>> {
        if (includeUnrated) {
            val unratedPairs = pairs.preferringUnrated()

            // Finish songs that are already partway toward a suggestion before starting new
            // ones: spreading turns evenly across the whole backlog (the old lifetimeBattles
            // behavior below) means any single song only gets picked once every N unrated
            // rounds, so with a large backlog no song ever accumulates the five
            // rated-opponent comparisons a suggestion needs. See issue #49.
            val maxProgress = unratedPairs.maxOf { it.unratedProgress(unratedStreaks) }
            if (maxProgress > 0) {
                return unratedPairs.filter {
                    it.unratedProgress(unratedStreaks) >= maxProgress - PROGRESS_CONCENTRATION_TOLERANCE
                }
            }

            val minimumLifetimeBattles = unratedPairs.minOf { it.lifetimeBattles() }
            return unratedPairs.filter {
                it.lifetimeBattles() <= minimumLifetimeBattles + UNRATED_BATTLE_TOLERANCE
            }
        }

        val ratedPairs = pairs.filter { it.bothRated() }
        if (ratedPairs.isEmpty()) return pairs

        // Measured against the closest pair overall (not just exact ties) so a cluster too
        // small to avoid repeats on its own - e.g. a single exact-tie pair - still pulls in
        // other close-enough pairs within tolerance, per this constant's contract above.
        val minimumScoreDifference = ratedPairs.minOf { it.scoreDifference() }
        return ratedPairs.filter { it.scoreDifference() <= minimumScoreDifference + SCORE_DIFFERENCE_TOLERANCE }
    }

    private fun chooseTie(
        pairs: List<Pair<Song, Song>>,
        lastSeen: Map<String, Long>
    ): Pair<Song, Song>? {
        if (pairs.isEmpty()) return null
        val minimumEloDifference = pairs.minOf { abs(it.first.elo - it.second.elo) }
        val closestElo = pairs.filter { abs(it.first.elo - it.second.elo) == minimumEloDifference }
        val minimumRecency = closestElo.minOf { recency(it, lastSeen) }
        val leastRecentlyShown = closestElo.filter { recency(it, lastSeen) == minimumRecency }
        return leastRecentlyShown[random.nextInt(leastRecentlyShown.size)]
    }

    // The more recently either song in a pair last appeared, the higher this value — so
    // preferring the minimum surfaces pairs whose songs have gone longest without a turn,
    // rather than a lifetime count that flattens out and stops discriminating over a long
    // session.
    private fun recency(pair: Pair<Song, Song>, lastSeen: Map<String, Long>): Long =
        maxOf(
            lastSeen.getOrDefault(pair.first.rankingSubjectId, -1L),
            lastSeen.getOrDefault(pair.second.rankingSubjectId, -1L)
        )

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

    // Exactly one side unrated: pairing against a rated song anchors the unrated song directly
    // to the calibrated ladder. An unrated-vs-unrated pair only fixes their order relative to
    // each other, so it's used only when no rated song is available to anchor against.
    private fun Pair<Song, Song>.isMixed(): Boolean =
        (first.scoreTenths == null) != (second.scoreTenths == null)

    private fun List<Pair<Song, Song>>.preferringUnrated(): List<Pair<Song, Song>> {
        val mixed = filter { it.isMixed() }
        return mixed.ifEmpty { filter { it.hasUnrated() }.ifEmpty { this } }
    }

    // Counts the subject's trailing win-outcome comparisons that were against rated
    // opponents, stopping at the first unrated opponent encountered (walking backwards) or
    // once SUGGESTION_STABILITY_WINDOW is reached, whichever comes first. Mirrors
    // SuggestionEngine's own "last five comparisons must all be against rated opponents"
    // gate, so a song's progress here tracks its actual distance from a suggestion.
    private fun ratedStreak(
        subjectId: String,
        songsById: Map<String, Song>,
        sortedWinEvents: List<MatchupEvent>
    ): Int {
        var streak = 0
        for (event in sortedWinEvents.asReversed()) {
            val opponentId = when (subjectId) {
                event.winnerSubjectId -> event.loserSubjectId
                event.loserSubjectId -> event.winnerSubjectId
                else -> null
            } ?: continue
            val opponentRated = songsById[opponentId]?.scoreTenths != null
            if (!opponentRated) break
            streak++
            if (streak >= SUGGESTION_STABILITY_WINDOW) break
        }
        return streak
    }

    // The rated-comparison streak of whichever side is unrated; 0 for an unrated-vs-unrated
    // pair, since pairing two unrated songs together doesn't advance either one's streak
    // toward a suggestion.
    private fun Pair<Song, Song>.unratedProgress(unratedStreaks: Map<String, Int>): Int = when {
        first.scoreTenths == null && second.scoreTenths != null -> unratedStreaks[first.rankingSubjectId] ?: 0
        second.scoreTenths == null && first.scoreTenths != null -> unratedStreaks[second.rankingSubjectId] ?: 0
        else -> 0
    }

    // Sums battles from both sides when both are unrated, so an unrated-vs-unrated pair reads as
    // "more battled" than an unrated-vs-rated pair with the same per-song count. That's
    // deliberate: pairing an unrated song against a rated one anchors it to the calibrated
    // ladder, while two unrated songs battling only fixes their order relative to each other.
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
