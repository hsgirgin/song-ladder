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
 * opponents and the implied score has stabilized (moved <= 0.5 across those
 * five). It never writes a score itself - callers must explicitly accept a
 * suggestion via the normal score-save path.
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
        val history = matchupEngine.replayWithHistory(subjects, events).eloHistoryBySubject

        val suggestions = subjects.mapNotNull { subject ->
            val ownWinEvents = events
                .asSequence()
                .filter { it.outcome == MatchupOutcome.WIN }
                .filter { it.winnerSubjectId == subject.id || it.loserSubjectId == subject.id }
                .sortedBy { it.sequenceId }
                .toList()
            if (ownWinEvents.size < STABILITY_WINDOW) return@mapNotNull null

            val recentEvents = ownWinEvents.takeLast(STABILITY_WINDOW)
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
                comparisonCount = ownWinEvents.size,
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
}
