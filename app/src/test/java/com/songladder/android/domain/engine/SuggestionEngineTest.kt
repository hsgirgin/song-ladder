package com.songladder.android.domain.engine

import com.songladder.android.domain.model.MatchupEvent
import com.songladder.android.domain.model.MatchupOutcome
import com.songladder.android.domain.model.MusicSourceType
import com.songladder.android.domain.model.RankingSubject
import com.songladder.android.domain.model.SuggestionDismissal
import com.songladder.android.domain.model.Tombstone
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
        // hero seeds at the library anchor (the opponents' own 50), not a flat neutral 55,
        // and K=0 pins it there exactly - so the suggestion lands on the anchor.
        val subjects = listOf(subject("hero", null)) + (1..5).map { subject("opp$it", 50) }
        val events = (1..5).map { winEvent(it.toLong(), "hero", "opp$it", 0.0) }

        val suggestion = engine.computeSuggestions(subjects, events, emptyList()).single { it.subjectId == "hero" }

        assertEquals(50, suggestion.suggestedScoreTenths)
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
    fun `a suggestion is suppressed when its nearest rated neighbor was never compared`() {
        val (baseSubjects, events) = disagreementScenario(heroId = "hero", sequenceOffset = 0)
        // Suggested score is 23 (see disagreementScenario's doc comment); this neighbor at
        // 20 is closer to it than either compared subject (at 10 and 50) but was never
        // directly compared against hero.
        val subjects = baseSubjects + subject("closer-neighbor", 20)

        val suggestions = engine.computeSuggestions(subjects, events, emptyList())

        assertTrue(suggestions.none { it.subjectId == "hero" })
    }

    @Test
    fun `a distant uncompared subject does not suppress the suggestion`() {
        val (baseSubjects, events) = disagreementScenario(heroId = "hero", sequenceOffset = 0)
        // 90 is far from the suggested 23 -- much farther than the compared subject at 10
        // (distance 13) -- so it shouldn't block the suggestion.
        val subjects = baseSubjects + subject("distant-neighbor", 90)

        val suggestion = engine.computeSuggestions(subjects, events, emptyList()).single { it.subjectId == "hero" }

        assertEquals(23, suggestion.suggestedScoreTenths)
        assertEquals(13, suggestion.scoreGapTenths)
    }

    @Test
    fun `a subject is excluded from its own nearest-neighbor check`() {
        // hero starts rated at 40 (elo 1080). One K=88 win against a 90-rated opponent
        // (expected score exactly 1/11) moves hero to exactly elo 1160; four further K=0
        // wins against the same opponent pin it there. scoreTenthsForElo(1160) = 50, which
        // disagrees with hero's current 40 by 10 (>= the eligibility threshold).
        //
        // Without excluding hero from its own candidate neighbor list, hero itself (at 40,
        // distance 10 from the suggested 50) would look closer than the only other rated
        // subject here (the opponent, at 90, distance 40) -- and since a subject never
        // directly compares against itself, that would wrongly suppress the suggestion.
        val subjects = listOf(subject("hero", 40), subject("opp90", 90))
        val events = listOf(winEvent(1L, "hero", "opp90", winnerK = 88.0, loserK = 0.0)) +
            (2..5).map { winEvent(it.toLong(), "hero", "opp90", winnerK = 0.0, loserK = 0.0) }

        val suggestion = engine.computeSuggestions(subjects, events, emptyList()).single { it.subjectId == "hero" }

        assertEquals(50, suggestion.suggestedScoreTenths)
        assertEquals(10, suggestion.scoreGapTenths)
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
    fun `a tombstoned opponent among past comparisons still counts toward the window`() {
        val subjects = listOf(subject("hero", null)) +
            (1..4).map { subject("opp$it", 50) } +
            listOf(tombstonedSubject("opp5", 50))
        val events = (1..5).map { winEvent(it.toLong(), "hero", "opp$it", 0.0) }

        val suggestion = engine.computeSuggestions(subjects, events, emptyList()).single { it.subjectId == "hero" }

        // The tombstoned opp5 is excluded from the library anchor too, but the remaining
        // opp1..opp4 are all 50 anyway, so the anchor (and hence the suggestion) is still 50.
        assertEquals(50, suggestion.suggestedScoreTenths)
        assertEquals(5, suggestion.comparisonCount)
    }

    @Test
    fun `a tombstoned subject never produces a suggestion`() {
        val subjects = listOf(tombstonedSubject("hero", null)) + (1..5).map { subject("opp$it", 50) }
        val events = (1..5).map { winEvent(it.toLong(), "hero", "opp$it", 0.0) }

        val suggestions = engine.computeSuggestions(subjects, events, emptyList())

        assertTrue(suggestions.none { it.subjectId == "hero" })
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

    private fun tombstonedSubject(id: String, scoreTenths: Int?): RankingSubject =
        subject(id, scoreTenths).copy(
            tombstone = Tombstone(
                sourceType = MusicSourceType.MANUAL,
                externalId = null,
                normalizedTitle = "",
                normalizedArtist = "",
                scoreTenths = scoreTenths,
                seedElo = 1200.0,
                deletedAt = 0L
            )
        )
}
