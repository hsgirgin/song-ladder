package com.songladder.android.domain.engine

import com.songladder.android.domain.model.MatchupEvent
import com.songladder.android.domain.model.MatchupOutcome
import com.songladder.android.domain.model.RankingSubject
import com.songladder.android.domain.model.SuggestionDismissal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test scenarios rely on explicitly persisted per-event K-factors (as real
 * matchup events always carry, see EloMatchupEngine.applyWinEvent) so elo
 * movement is exact and hand-verifiable rather than depending on epoch decay:
 * K=0 pins a subject's elo exactly at its current seed regardless of the
 * opponent, letting most scenarios be constructed without floating-point
 * guesswork.
 */
class SuggestionEngineTest {
    private val engine = SuggestionEngine()

    @Test
    fun `fewer than five comparisons produces no suggestion`() {
        val subjects = listOf(subject("hero", null)) + (1..4).map { subject("opp$it", 50) }
        val events = (1..4).map { winEvent(it.toLong(), "hero", "opp$it", 0.0) }

        val suggestions = engine.computeSuggestions(subjects, events, emptyList())

        assertTrue(suggestions.none { it.subjectId == "hero" })
    }

    @Test
    fun `an unrated opponent among the last five comparisons suppresses the suggestion`() {
        val subjects = listOf(subject("hero", null)) +
            (1..4).map { subject("opp$it", 50) } +
            listOf(subject("opp5", null))
        val events = (1..5).map { winEvent(it.toLong(), "hero", "opp$it", 0.0) }

        val suggestions = engine.computeSuggestions(subjects, events, emptyList())

        assertTrue(suggestions.none { it.subjectId == "hero" })
    }

    @Test
    fun `an unstable trajectory across the window suppresses the suggestion`() {
        val subjects = listOf(subject("hero", null)) +
            (1..4).map { subject("opp$it", 50) } +
            listOf(subject("jump-opp", 10))
        val events = (1..4).map { winEvent(it.toLong(), "hero", "opp$it", 0.0) } +
            winEvent(5L, "hero", "jump-opp", winnerK = 500.0, loserK = 0.0)

        val suggestions = engine.computeSuggestions(subjects, events, emptyList())

        assertTrue(suggestions.none { it.subjectId == "hero" })
    }

    @Test
    fun `a stable trajectory for an unrated subject produces a pending suggestion`() {
        val subjects = listOf(subject("hero", null)) + (1..5).map { subject("opp$it", 50) }
        val events = (1..5).map { winEvent(it.toLong(), "hero", "opp$it", 0.0) }

        val suggestion = engine.computeSuggestions(subjects, events, emptyList()).single { it.subjectId == "hero" }

        assertEquals(55, suggestion.suggestedScoreTenths)
        assertNull(suggestion.scoreGapTenths)
        assertEquals(5, suggestion.comparisonCount)
    }

    @Test
    fun `a stable trajectory that agrees with the existing score is suppressed`() {
        val subjects = listOf(subject("hero", 55)) + (1..5).map { subject("opp$it", 50) }
        val events = (1..5).map { winEvent(it.toLong(), "hero", "opp$it", 0.0) }

        val suggestions = engine.computeSuggestions(subjects, events, emptyList())

        assertTrue(suggestions.none { it.subjectId == "hero" })
    }

    @Test
    fun `a stable trajectory that disagrees with the existing score is eligible`() {
        val (subjects, events) = disagreementScenario(heroId = "hero", sequenceOffset = 0)

        val suggestion = engine.computeSuggestions(subjects, events, emptyList()).single { it.subjectId == "hero" }

        assertEquals(23, suggestion.suggestedScoreTenths)
        assertEquals(13, suggestion.scoreGapTenths)
        assertEquals(6, suggestion.comparisonCount)
        assertEquals(6L, suggestion.lastEventSequenceId)
    }

    @Test
    fun `a dismissal with no new evidence keeps the suggestion suppressed`() {
        val (subjects, events) = disagreementScenario(heroId = "hero", sequenceOffset = 0)
        val dismissals = listOf(SuggestionDismissal("hero", dismissedAtSequenceId = 6L, dismissedScoreTenths = 23))

        val suggestions = engine.computeSuggestions(subjects, events, dismissals)

        assertTrue(suggestions.none { it.subjectId == "hero" })
    }

    @Test
    fun `a dismissal clears once new evidence moves the estimate materially`() {
        val (subjects, events) = disagreementScenario(heroId = "hero", sequenceOffset = 0)
        val dismissals = listOf(SuggestionDismissal("hero", dismissedAtSequenceId = 4L, dismissedScoreTenths = 15))

        val suggestion = engine.computeSuggestions(subjects, events, dismissals).single { it.subjectId == "hero" }

        assertEquals(23, suggestion.suggestedScoreTenths)
    }

    @Test
    fun `disagreement suggestions sort ahead of pending unrated suggestions`() {
        val (disagreeSubjects, disagreeEvents) = disagreementScenario(heroId = "hero-a", sequenceOffset = 0)
        val pendingSubjects = listOf(subject("hero-b", null)) + (1..5).map { subject("hero-b-opp$it", 50) }
        val pendingEvents = (1..5).map { winEvent(100L + it, "hero-b", "hero-b-opp$it", 0.0) }

        val suggestions = engine.computeSuggestions(
            disagreeSubjects + pendingSubjects,
            disagreeEvents + pendingEvents,
            emptyList()
        )

        assertEquals(listOf("hero-a", "hero-b"), suggestions.map { it.subjectId })
    }

    /**
     * hero starts rated at 1.0 (scoreTenths=10, seed elo 840). A single K=200
     * win against an opponent pinned at the same elo (expected exactly 0.5)
     * moves hero to exactly elo 940. Five further K=0 wins leave it pinned
     * there, so the trailing-five window is [940,940,940,940,940] -
     * scoreTenthsForElo(940) = 23, disagreeing with the current 10 by 13.
     */
    private fun disagreementScenario(heroId: String, sequenceOffset: Long): Pair<List<RankingSubject>, List<MatchupEvent>> {
        val subjects = listOf(subject(heroId, 10), subject("$heroId-zero", 10)) +
            (1..5).map { subject("$heroId-opp$it", 50) }
        val events = listOf(
            winEvent(sequenceOffset + 1, heroId, "$heroId-zero", winnerK = 200.0, loserK = 0.0)
        ) + (1..5).map { winEvent(sequenceOffset + 1 + it, heroId, "$heroId-opp$it", 0.0) }
        return subjects to events
    }

    private fun winEvent(
        sequenceId: Long,
        winnerId: String,
        loserId: String,
        winnerK: Double,
        loserK: Double = winnerK
    ): MatchupEvent = MatchupEvent(
        sequenceId = sequenceId,
        occurredAt = sequenceId,
        firstSubjectId = winnerId,
        secondSubjectId = loserId,
        outcome = MatchupOutcome.WIN,
        winnerSubjectId = winnerId,
        loserSubjectId = loserId,
        winnerEffectiveK = winnerK,
        loserEffectiveK = loserK
    )

    private fun subject(id: String, scoreTenths: Int?): RankingSubject =
        RankingSubject(id = id, scoreTenths = scoreTenths)
}
