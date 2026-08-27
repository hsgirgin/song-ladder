package com.songladder.android.domain.engine

import com.songladder.android.domain.model.MatchupEvent
import com.songladder.android.domain.model.MatchupOutcome
import com.songladder.android.domain.model.RankingSubject
import com.songladder.android.domain.model.Suggestion
import com.songladder.android.domain.model.SuggestionDismissal
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
        val ratedSubjects = subjects.filter { it.tombstone == null && it.scoreTenths != null }
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
                val opponentId = if (event.winnerSubjectId == subject.id) event.loserSubjectId else event.winnerSubjectId
                subjectsById[opponentId]?.scoreTenths != null
            }
            if (!allOpponentsRated) return@mapNotNull null

            val trajectory = history[subject.id]?.takeLast(STABILITY_WINDOW).orEmpty()
            if (trajectory.size < STABILITY_WINDOW) return@mapNotNull null
            val candidateScores = trajectory.map(::scoreTenthsForElo)
            val stable = (candidateScores.max() - candidateScores.min()) <= STABILITY_THRESHOLD_TENTHS
            if (!stable) return@mapNotNull null

            val suggestedScoreTenths = candidateScores.last()

            val directOpponentIds = ownComparisonEvents.mapNotNullTo(mutableSetOf()) { event ->
                if (event.winnerSubjectId == subject.id) event.loserSubjectId else event.winnerSubjectId
            }
            val otherRatedSubjects = ratedSubjects.filter { it.id != subject.id }
            if (nearestUncomparedNeighbor(suggestedScoreTenths, otherRatedSubjects, directOpponentIds) != null) {
                return@mapNotNull null
            }

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
     * The rated subject closest in score to [suggestedScoreTenths] (checking only the
     * single nearest neighbor, whichever side it falls on - not both brackets), if that
     * subject was never directly compared against. Requiring a comparison against the far
     * neighbor too - one on the side of a placement the subject isn't actually claiming to
     * cross - would suppress nearly every legitimate suggestion in a reasonably-sized
     * library, since there's almost always some uncompared song further out either way.
     */
    private fun nearestUncomparedNeighbor(
        suggestedScoreTenths: Int,
        otherRatedSubjects: List<RankingSubject>,
        directOpponentIds: Set<String>
    ): RankingSubject? {
        if (otherRatedSubjects.isEmpty()) return null
        val sorted = otherRatedSubjects.sortedBy { it.scoreTenths }
        val below = sorted.lastOrNull { it.scoreTenths!! <= suggestedScoreTenths }
        val above = sorted.firstOrNull { it.scoreTenths!! >= suggestedScoreTenths }
        val nearest = listOfNotNull(below, above)
            .minByOrNull { abs(it.scoreTenths!! - suggestedScoreTenths) }
        return nearest?.takeIf { it.id !in directOpponentIds }
    }
}
