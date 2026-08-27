package com.songladder.android.domain.engine

import com.songladder.android.domain.model.MatchupEvent
import com.songladder.android.domain.model.MatchupOutcome
import com.songladder.android.domain.model.RankingSubject
import com.songladder.android.domain.model.Suggestion
import com.songladder.android.domain.model.SuggestionDismissal
import com.songladder.android.domain.model.ratedSubjects
import com.songladder.android.domain.model.scoreTenthsForElo
import kotlin.math.abs

private const val STABILITY_WINDOW = 5
private const val STABILITY_THRESHOLD_TENTHS = 5
private const val DISAGREEMENT_THRESHOLD_TENTHS = 5

/**
 * Derives score suggestions from comparison evidence. A suggestion only
 * surfaces when the subject's last five comparisons were all against rated
 * opponents, the implied score has stabilized (moved <= 0.5 across those
 * five), and the subject has been directly compared against its nearest
 * rated neighbor at that score - so a short, locally-stable run of
 * comparisons can't place a song above/below a neighboring song it never
 * actually played. It never writes a score itself - callers must explicitly
 * accept a suggestion via the normal score-save path.
 */
class SuggestionEngine(
    private val matchupEngine: EloMatchupEngine = EloMatchupEngine()
) {
    fun computeSuggestions(
        subjects: List<RankingSubject>,
        events: List<MatchupEvent>,
        dismissals: List<SuggestionDismissal>
    ): List<Suggestion> {
        val subjectsById = subjects.associateBy { it.id }
        val dismissalsById = dismissals.associateBy { it.subjectId }
        val rated = ratedSubjects(subjects)
        val history = matchupEngine.replayWithHistory(subjects, events).eloHistoryBySubject

        val comparisonEventsBySubject: Map<String, List<MatchupEvent>> = run {
            val bucket = HashMap<String, MutableList<MatchupEvent>>()
            events
                .asSequence()
                .filter { it.outcome == MatchupOutcome.WIN }
                .sortedBy { it.sequenceId }
                .forEach { event ->
                    event.winnerSubjectId?.let { bucket.getOrPut(it) { mutableListOf() }.add(event) }
                    event.loserSubjectId?.let { bucket.getOrPut(it) { mutableListOf() }.add(event) }
                }
            bucket
        }

        val suggestions = subjects.mapNotNull { subject ->
            if (subject.tombstone != null) return@mapNotNull null
            val ownComparisonEvents = comparisonEventsBySubject[subject.id].orEmpty()
            if (ownComparisonEvents.size < STABILITY_WINDOW) return@mapNotNull null

            val recentEvents = ownComparisonEvents.takeLast(STABILITY_WINDOW)
            val allOpponentsRated = recentEvents.all { event ->
                subjectsById[event.opponentOf(subject.id)]?.scoreTenths != null
            }
            if (!allOpponentsRated) return@mapNotNull null

            val trajectory = history[subject.id]?.takeLast(STABILITY_WINDOW).orEmpty()
            if (trajectory.size < STABILITY_WINDOW) return@mapNotNull null
            val candidateScores = trajectory.map(::scoreTenthsForElo)
            val stable = (candidateScores.max() - candidateScores.min()) <= STABILITY_THRESHOLD_TENTHS
            if (!stable) return@mapNotNull null

            val suggestedScoreTenths = candidateScores.last()
            val currentScoreTenths = subject.scoreTenths
            val scoreGapTenths = currentScoreTenths?.let { abs(suggestedScoreTenths - it) }
            val eligible = currentScoreTenths == null || (scoreGapTenths != null && scoreGapTenths >= DISAGREEMENT_THRESHOLD_TENTHS)
            if (!eligible) return@mapNotNull null

            val lastEventSequenceId = recentEvents.last().sequenceId
            val dismissal = dismissalsById[subject.id]
            if (dismissal != null) {
                val hasNewEvidence = lastEventSequenceId > dismissal.dismissedAtSequenceId
                val movedMaterially = abs(suggestedScoreTenths - dismissal.dismissedScoreTenths) >= STABILITY_THRESHOLD_TENTHS
                if (!hasNewEvidence || !movedMaterially) return@mapNotNull null
            }

            // Checked last since it's the most expensive gate - no point scanning the rated
            // ladder for subjects that would've been filtered out by a cheaper check anyway.
            val directOpponentIds = ownComparisonEvents.mapNotNullTo(mutableSetOf()) { it.opponentOf(subject.id) }
            val otherRatedSubjects = rated.filter { it.id != subject.id }
            if (nearestUncomparedNeighbor(suggestedScoreTenths, otherRatedSubjects, directOpponentIds) != null) {
                return@mapNotNull null
            }

            Suggestion(
                subjectId = subject.id,
                suggestedScoreTenths = suggestedScoreTenths,
                comparisonCount = ownComparisonEvents.size,
                scoreGapTenths = scoreGapTenths,
                lastEventSequenceId = lastEventSequenceId
            )
        }

        return suggestions.sortedWith(
            compareByDescending<Suggestion> { it.scoreGapTenths != null }
                .thenByDescending { it.scoreGapTenths ?: 0 }
                .thenByDescending { it.lastEventSequenceId }
        )
    }

    /**
     * A representative of the nearest-scored rated group to [suggestedScoreTenths] that was
     * never directly compared against, if any. Only the group(s) at the single smallest
     * distance are checked - not every rated subject - since requiring a comparison against
     * something far on the side of a placement the subject isn't actually claiming to cross
     * would suppress nearly every legitimate suggestion in a reasonably-sized library.
     *
     * A given distance can only be achieved by at most two distinct scores (one just below
     * [suggestedScoreTenths], one just above), so this checks at most those two groups. Each
     * is satisfied if *any* of its members was directly compared - multiple rated subjects
     * sharing the exact same nearest score are one piece of evidence, not one requirement
     * per song, but the below-group and above-group (when both tie for nearest) are
     * independent and both must be satisfied.
     */
    private fun nearestUncomparedNeighbor(
        suggestedScoreTenths: Int,
        otherRatedSubjects: List<RankingSubject>,
        directOpponentIds: Set<String>
    ): RankingSubject? {
        if (otherRatedSubjects.isEmpty()) return null
        val minDistance = otherRatedSubjects.minOf { abs(it.scoreTenths!! - suggestedScoreTenths) }
        val nearestGroupsByScore = otherRatedSubjects
            .filter { abs(it.scoreTenths!! - suggestedScoreTenths) == minDistance }
            .groupBy { it.scoreTenths }
        return nearestGroupsByScore.values
            .firstOrNull { group -> group.none { it.id in directOpponentIds } }
            ?.first()
    }

    private fun MatchupEvent.opponentOf(subjectId: String): String? =
        if (winnerSubjectId == subjectId) loserSubjectId else winnerSubjectId
}
