package com.songladder.android.domain.engine

import com.songladder.android.domain.model.BASE_RATING
import com.songladder.android.domain.model.Matchup
import com.songladder.android.domain.model.MatchupEvent
import com.songladder.android.domain.model.MatchupOutcome
import com.songladder.android.domain.model.RankingSubject
import com.songladder.android.domain.model.ResponsivenessEpoch
import com.songladder.android.domain.model.Song
import com.songladder.android.domain.model.formatScoreTenths
import com.songladder.android.domain.model.validateScoreTenths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

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
    fun `winner and loser use independent double precision responsiveness factors without changing score timestamps`() {
        val winner = subject(id = "one", elo = BASE_RATING.toDouble()).copy(lastRatedAt = 111L)
        val loser = subject(
            id = "two",
            elo = BASE_RATING.toDouble(),
            responsivenessEpoch = ResponsivenessEpoch.EDITED
        ).copy(lastRatedAt = 222L)

        val result = engine.updateRatings(winner, loser, ratedAt = 1234L)

        assertEquals(64.0, result.winnerEffectiveK, 0.000001)
        assertEquals(40.0, result.loserEffectiveK, 0.000001)
        assertEquals(1232.0, result.winner.elo, 0.000001)
        assertEquals(1180.0, result.loser.elo, 0.000001)
        assertEquals(1, result.winner.wins)
        assertEquals(1, result.loser.losses)
        assertEquals(1, result.winner.completedMatchupsInEpoch)
        assertEquals(111L, result.winner.lastRatedAt)
        assertEquals(222L, result.loser.lastRatedAt)
    }

    @Test
    fun `validates and formats integer tenths scores`() {
        validateScoreTenths(10)
        validateScoreTenths(100)

        assertEquals("8.7", formatScoreTenths(87))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects scores outside the one to ten range`() {
        validateScoreTenths(101)
    }

    @Test
    fun `prefers exact score pairs before closest score pairs`() {
        val matchup = EloMatchupEngine(Random(1)).pickMatchup(
            listOf(
                song(id = "one", scoreTenths = 80, elo = 1400.0),
                song(id = "two", scoreTenths = 80, elo = 1200.0),
                song(id = "three", scoreTenths = 90, elo = 1200.0)
            )
        )

        assertEquals(setOf("one", "two"), setOf(matchup?.left?.id, matchup?.right?.id))
    }

    @Test
    fun `blocked two-song exact-score group excludes both songs from fallback pairs`() {
        val songs = listOf(
            song(id = "one", scoreTenths = 80),
            song(id = "two", scoreTenths = 80),
            song(id = "three", scoreTenths = 90),
            song(id = "four", scoreTenths = 70)
        )

        val selection = EloMatchupEngine(Random(1)).selectMatchup(
            songs = songs,
            events = listOf(event(1, "one", "two"))
        )

        assertEquals(setOf("three", "four"), setOf(
            selection.matchup?.left?.id,
            selection.matchup?.right?.id
        ))

        val continued = EloMatchupEngine(Random(1)).selectMatchup(
            songs = songs,
            events = listOf(
                event(1, "one", "two"),
                event(2, "three", "four")
            ),
            continueAnyway = true
        )
        assertTrue(setOf(continued.matchup?.left?.id, continued.matchup?.right?.id) in setOf(
            setOf("one", "two"),
            setOf("three", "four")
        ))
    }

    @Test
    fun `reports caught up after the last three unordered pairs are blocked`() {
        val songs = listOf(
            song(id = "one", scoreTenths = 80),
            song(id = "two", scoreTenths = 80),
            song(id = "three", scoreTenths = 90)
        )
        val events = listOf(
            event(1, "one", "two"),
            event(2, "two", "three"),
            event(3, "one", "three")
        )

        val caughtUp = EloMatchupEngine(Random(1)).selectMatchup(songs, events)
        val continued = EloMatchupEngine(Random(1)).selectMatchup(
            songs = songs,
            events = events,
            continueAnyway = true
        )

        assertTrue(caughtUp.caughtUp)
        assertNull(caughtUp.matchup)
        assertNotNull(continued.matchup)
    }

    @Test
    fun `continue anyway chooses one blocked pair without applying normal preference`() {
        val songs = listOf(
            song(id = "one", scoreTenths = 80, elo = 1000.0),
            song(id = "two", scoreTenths = 80, elo = 1200.0),
            song(id = "three", scoreTenths = 90, elo = 1200.0)
        )
        val events = listOf(
            event(1, "one", "two"),
            event(2, "two", "three"),
            event(3, "one", "three")
        )

        val selection = EloMatchupEngine(Random(1)).selectMatchup(
            songs = songs,
            events = events,
            continueAnyway = true
        )

        assertTrue(selection.matchup != null)
        assertTrue(
            setOf(selection.matchup?.left?.id, selection.matchup?.right?.id) in setOf(
                setOf("one", "two"), setOf("two", "three"), setOf("one", "three")
            )
        )
    }

    @Test
    fun `keeps near-tied score pairs eligible within tolerance so the elo-closest match wins`() {
        // Strict score-difference minimum (1) only admits x-y, but the tolerance band lets
        // x-z (difference 4) in too, and x-z has by far the closest elo -- so it should win
        // the final tie-break instead of x-y being the sole, forced candidate.
        val x = song(id = "x", scoreTenths = 70, elo = 1200.0)
        val y = song(id = "y", scoreTenths = 71, elo = 1400.0)
        val z = song(id = "z", scoreTenths = 74, elo = 1210.0)

        val selection = EloMatchupEngine(Random(1)).selectMatchup(songs = listOf(x, y, z))

        val matchup = requireNotNull(selection.matchup)
        assertEquals(setOf("x", "z"), setOf(matchup.left.id, matchup.right.id))
    }

    @Test
    fun `repeat-avoidance window scales up with a larger eligible pool`() {
        val songs = (1..6).map { song(id = "s$it", scoreTenths = 80) }
        // 6 same-score songs give 15 eligible pairs, scaling the block window to 7. Seven
        // distinct prior pairs should all stay blocked, unlike the old fixed window of 3.
        val shownPairs = listOf(
            "s1" to "s2", "s1" to "s3", "s1" to "s4", "s1" to "s5",
            "s1" to "s6", "s2" to "s3", "s2" to "s4"
        )
        val events = shownPairs.mapIndexed { index, (first, second) ->
            event((index + 1).toLong(), first, second)
        }

        val selection = EloMatchupEngine(Random(1)).selectMatchup(songs = songs, events = events)

        val matchup = requireNotNull(selection.matchup)
        val selectedIds = setOf(matchup.left.id, matchup.right.id)
        assertTrue(shownPairs.none { (first, second) -> setOf(first, second) == selectedIds })
    }

    @Test
    fun `falls back to nearest score difference when no exact pair exists`() {
        val selection = EloMatchupEngine(Random(1)).selectMatchup(
            songs = listOf(
                song(id = "low", scoreTenths = 70),
                song(id = "middle", scoreTenths = 80),
                song(id = "high", scoreTenths = 90)
            )
        )

        val scores = listOfNotNull(selection.matchup?.left?.scoreTenths, selection.matchup?.right?.scoreTenths)
        assertEquals(2, scores.size)
        assertEquals(10, kotlin.math.abs(scores[0] - scores[1]))
    }

    @Test
    fun `includes a single unrated song on every fourth selection opportunity`() {
        val selection = EloMatchupEngine(Random(1)).selectMatchup(
            songs = listOf(
                song(id = "rated-one", scoreTenths = 80),
                song(id = "rated-two", scoreTenths = 90),
                song(id = "unrated")
            ),
            events = listOf(
                event(1, "rated-one", "rated-two"),
                event(2, "rated-one", "rated-two"),
                event(3, "rated-one", "rated-two")
            )
        )

        assertTrue(selection.matchup?.left?.scoreTenths == null || selection.matchup?.right?.scoreTenths == null)
    }

    @Test
    fun `surfaces unrated songs more often as the unrated backlog grows`() {
        val songs = listOf(song(id = "rated-one", scoreTenths = 80), song(id = "rated-two", scoreTenths = 90)) +
            (1..5).map { song(id = "unrated-$it") }

        // With 5 unrated songs the interval is every 2nd matchup opportunity.
        val selection = EloMatchupEngine(Random(1)).selectMatchup(
            songs = songs,
            events = listOf(event(1, "rated-one", "rated-two"))
        )

        assertTrue(selection.matchup?.left?.scoreTenths == null || selection.matchup?.right?.scoreTenths == null)
    }

    @Test
    fun `uses the moderate interval for unrated backlogs sized two through four`() {
        for (unratedCount in 2..4) {
            val songs = listOf(song(id = "rated-one", scoreTenths = 80), song(id = "rated-two", scoreTenths = 90)) +
                (1..unratedCount).map { song(id = "unrated-$it") }

            // Interval is 3 for these backlog sizes: (matchupCount + 1) % 3 == 0 at matchupCount 2.
            val onInterval = EloMatchupEngine(Random(1)).selectMatchup(
                songs = songs,
                events = listOf(
                    event(1, "rated-one", "rated-two"),
                    event(2, "rated-one", "rated-two")
                )
            )
            assertTrue(
                "expected an unrated song for backlog size $unratedCount",
                onInterval.matchup?.left?.scoreTenths == null || onInterval.matchup?.right?.scoreTenths == null
            )

            // No prior events here: with only two rated songs, an event pairing them would
            // block the sole rated-rated pair and force a fallback into unrated pairs,
            // which would defeat the point of this off-interval assertion.
            val offInterval = EloMatchupEngine(Random(1)).selectMatchup(
                songs = songs,
                events = emptyList()
            )
            assertTrue(
                "expected only rated songs for backlog size $unratedCount",
                offInterval.matchup?.left?.scoreTenths != null && offInterval.matchup?.right?.scoreTenths != null
            )
        }
    }

    @Test
    fun `keeps a slightly-battled unrated song eligible for a mixed pairing alongside a fresh import`() {
        val slightlyBattled = song(id = "slightly-battled", wins = 1, losses = 1)
        val fresh = song(id = "fresh")

        val selection = EloMatchupEngine(Random(1)).selectMatchup(
            songs = listOf(
                song(id = "rated-one", scoreTenths = 80),
                song(id = "rated-two", scoreTenths = 90),
                slightlyBattled,
                fresh
            ),
            events = listOf(
                event(1, "rated-one", "rated-two"),
                event(2, "rated-one", "rated-two")
            )
        )

        val matchup = requireNotNull(selection.matchup)
        val ids = setOf(matchup.left.id, matchup.right.id)
        // A mixed pair (one rated, one unrated) is preferred over pairing the two unrated
        // songs against each other, so either unrated song may be picked here -- but always
        // alongside a rated song, never the pure "fresh" vs "slightly-battled" pair.
        assertTrue(
            ids == setOf("rated-one", "fresh") ||
                ids == setOf("rated-two", "fresh") ||
                ids == setOf("rated-one", "slightly-battled") ||
                ids == setOf("rated-two", "slightly-battled")
        )
    }

    @Test
    fun `prefers a rated-unrated pairing over pairing two unrated songs together`() {
        val songs = listOf(
            song(id = "rated", scoreTenths = 80),
            song(id = "unrated-one"),
            song(id = "unrated-two")
        )

        // Filler events unrelated to any real pair here, only to reach the unrated-inclusion
        // interval without accidentally blocking one of the mixed pairs under test.
        val selection = EloMatchupEngine(Random(1)).selectMatchup(
            songs = songs,
            events = listOf(
                event(1, "filler-a", "filler-b"),
                event(2, "filler-a", "filler-b")
            )
        )

        val matchup = requireNotNull(selection.matchup)
        assertTrue("rated" in setOf(matchup.left.id, matchup.right.id))
    }

    @Test
    fun `prefers unrated songs with the fewest lifetime battles over recently added ones`() {
        val heavilyBattled = song(id = "battled", wins = 10, losses = 10)
        val neverBattled = song(id = "fresh")
        val selection = EloMatchupEngine(Random(1)).selectMatchup(
            songs = listOf(
                song(id = "rated-one", scoreTenths = 80),
                song(id = "rated-two", scoreTenths = 90),
                heavilyBattled,
                neverBattled
            ),
            events = listOf(
                event(1, "rated-one", "rated-two"),
                event(2, "rated-one", "rated-two")
            )
        )

        val matchup = requireNotNull(selection.matchup)
        val ids = setOf(matchup.left.id, matchup.right.id)
        assertTrue("fresh" in ids)
        assertTrue("battled" !in ids)
    }

    @Test
    fun `prefers an unrated song already partway toward a suggestion over an untouched one`() {
        // Regression test for #49: an unrated song already partway through its five
        // rated-opponent comparisons must be pushed to completion before a fresh,
        // never-compared unrated song is given a turn - otherwise a large backlog
        // spreads comparisons so thin that no song ever accumulates enough to
        // produce a suggestion.
        val ratedSongs = listOf(
            song(id = "rated-a", scoreTenths = 10),
            song(id = "rated-b", scoreTenths = 20),
            song(id = "rated-c", scoreTenths = 30),
            song(id = "rated-d", scoreTenths = 40)
        )
        val started = song(id = "started")
        val fresh = song(id = "fresh")
        val filler = Matchup(left = ratedSongs[0], right = ratedSongs[1])
        val events = listOf(
            winEvent(1L, winnerId = "started", loserId = "rated-a"),
            winEvent(2L, winnerId = "started", loserId = "rated-b"),
            winEvent(3L, winnerId = "started", loserId = "rated-c")
        )

        val selection = EloMatchupEngine(Random(1)).selectMatchup(
            songs = ratedSongs + listOf(started, fresh),
            events = events,
            displayedMatchups = listOf(filler),
            displayedMatchupCount = 2
        )

        val matchup = requireNotNull(selection.matchup)
        val ids = setOf(matchup.left.id, matchup.right.id)
        assertTrue("started" in ids)
        assertTrue("fresh" !in ids)
    }

    @Test
    fun `replays winner events from score seeds using recorded effective factors`() {
        val result = EloMatchupEngine().replay(
            subjects = listOf(subject("one", 1200.0), subject("two", 1200.0)),
            events = listOf(
                MatchupEvent(
                    sequenceId = 1L,
                    occurredAt = 10L,
                    firstSubjectId = "one",
                    secondSubjectId = "two",
                    outcome = MatchupOutcome.WIN,
                    winnerSubjectId = "one",
                    loserSubjectId = "two",
                    winnerEffectiveK = 64.0,
                    loserEffectiveK = 40.0
                )
            )
        ).associateBy { it.id }

        assertEquals(1232.0, result.getValue("one").elo, 0.000001)
        assertEquals(1180.0, result.getValue("two").elo, 0.000001)
        assertEquals(1, result.getValue("one").wins)
        assertEquals(1, result.getValue("two").losses)
    }

    @Test
    fun `replay preserves last rated timestamps from manual scores`() {
        val result = EloMatchupEngine().replay(
            subjects = listOf(
                subject("one").copy(lastRatedAt = 100L),
                subject("two").copy(lastRatedAt = 100L)
            ),
            events = listOf(
                MatchupEvent(
                    sequenceId = 1L,
                    occurredAt = 200L,
                    firstSubjectId = "one",
                    secondSubjectId = "two",
                    outcome = MatchupOutcome.WIN,
                    winnerSubjectId = "one",
                    loserSubjectId = "two",
                    winnerEffectiveK = 64.0,
                    loserEffectiveK = 64.0
                )
            )
        ).associateBy { it.id }

        assertEquals(100L, result.getValue("one").lastRatedAt)
        assertEquals(100L, result.getValue("two").lastRatedAt)
    }

    @Test
    fun `replayWithHistory returns the same final subjects as replay`() {
        val subjects = listOf(subject("one", 1200.0), subject("two", 1150.0), subject("three", 1250.0))
        val events = listOf(
            winEvent(1L, winnerId = "one", loserId = "two"),
            winEvent(2L, winnerId = "one", loserId = "three"),
            winEvent(3L, winnerId = "two", loserId = "three")
        )
        val engine = EloMatchupEngine()

        val direct = engine.replay(subjects, events).associateBy { it.id }
        val viaHistory = engine.replayWithHistory(subjects, events).subjects.associateBy { it.id }

        subjects.forEach { original ->
            assertEquals(direct.getValue(original.id).elo, viaHistory.getValue(original.id).elo, 0.000001)
            assertEquals(direct.getValue(original.id).wins, viaHistory.getValue(original.id).wins)
            assertEquals(direct.getValue(original.id).losses, viaHistory.getValue(original.id).losses)
        }
    }

    @Test
    fun `replayWithHistory records elo after each of a subject's own win events in order`() {
        val result = EloMatchupEngine().replayWithHistory(
            subjects = listOf(subject("one", 1200.0), subject("two", 1200.0), subject("three", 1200.0)),
            events = listOf(
                MatchupEvent(
                    sequenceId = 1L,
                    occurredAt = 10L,
                    firstSubjectId = "one",
                    secondSubjectId = "two",
                    outcome = MatchupOutcome.WIN,
                    winnerSubjectId = "one",
                    loserSubjectId = "two",
                    winnerEffectiveK = 64.0,
                    loserEffectiveK = 64.0
                ),
                MatchupEvent(
                    sequenceId = 2L,
                    occurredAt = 20L,
                    firstSubjectId = "one",
                    secondSubjectId = "three",
                    outcome = MatchupOutcome.WIN,
                    winnerSubjectId = "one",
                    loserSubjectId = "three",
                    winnerEffectiveK = 64.0,
                    loserEffectiveK = 64.0
                )
            )
        )

        val oneHistory = result.eloHistoryBySubject.getValue("one")
        assertEquals(2, oneHistory.size)
        assertEquals(1232.0, oneHistory[0], 0.000001)
        assertTrue(oneHistory[1] > oneHistory[0])
        assertEquals(result.subjects.first { it.id == "one" }.elo, oneHistory.last(), 0.000001)

        assertEquals(1, result.eloHistoryBySubject.getValue("two").size)
        assertTrue(!result.eloHistoryBySubject.containsKey("missing"))
    }

    @Test
    fun `displayed matchups are used for cooldown before an event is recorded`() {
        val songs = listOf(
            song(id = "one", scoreTenths = 80),
            song(id = "two", scoreTenths = 80),
            song(id = "three", scoreTenths = 90),
            song(id = "four", scoreTenths = 70)
        )
        val displayed = Matchup(left = songs[0], right = songs[1])

        val selection = EloMatchupEngine(Random(1)).selectMatchup(
            songs = songs,
            displayedMatchups = listOf(displayed),
            displayedMatchupCount = 1
        )

        assertEquals(
            setOf("three", "four"),
            setOf(selection.matchup?.left?.id, selection.matchup?.right?.id)
        )
    }

    @Test
    fun `falls back to an unrated pair when the only rated pair is cooled down`() {
        val songs = listOf(
            song(id = "one", scoreTenths = 80),
            song(id = "two", scoreTenths = 80),
            song(id = "three"),
            song(id = "four")
        )
        val displayed = Matchup(left = songs[0], right = songs[1])

        val selection = EloMatchupEngine(Random(1)).selectMatchup(
            songs = songs,
            displayedMatchups = listOf(displayed),
            displayedMatchupCount = 1
        )

        assertTrue(selection.matchup != null)
        assertTrue(
            selection.matchup?.left?.scoreTenths == null ||
                selection.matchup?.right?.scoreTenths == null
        )
    }

    @Test
    fun `replay counts only events after the persisted responsiveness epoch boundary`() {
        val result = EloMatchupEngine().replay(
            subjects = listOf(
                subject("one", responsivenessEpoch = ResponsivenessEpoch.EDITED).copy(responsivenessEpochSequence = 1L),
                subject("two")
            ),
            events = listOf(
                MatchupEvent(1L, 10L, "one", "two", MatchupOutcome.WIN, "one", "two", 40.0, 64.0),
                MatchupEvent(2L, 10L, "one", "two", MatchupOutcome.WIN, "one", "two", 40.0, 64.0)
            )
        ).associateBy { it.id }

        assertEquals(1, result.getValue("one").completedMatchupsInEpoch)
    }

    private fun event(sequenceId: Long, firstId: String, secondId: String): MatchupEvent =
        MatchupEvent(
            sequenceId = sequenceId,
            occurredAt = sequenceId,
            firstSubjectId = firstId,
            secondSubjectId = secondId,
            outcome = MatchupOutcome.SKIP
        )

    private fun winEvent(sequenceId: Long, winnerId: String, loserId: String): MatchupEvent =
        MatchupEvent(
            sequenceId = sequenceId,
            occurredAt = sequenceId,
            firstSubjectId = winnerId,
            secondSubjectId = loserId,
            outcome = MatchupOutcome.WIN,
            winnerSubjectId = winnerId,
            loserSubjectId = loserId,
            winnerEffectiveK = 64.0,
            loserEffectiveK = 64.0
        )

    private fun subject(
        id: String,
        elo: Double = BASE_RATING.toDouble(),
        responsivenessEpoch: ResponsivenessEpoch = ResponsivenessEpoch.NEW
    ): RankingSubject = RankingSubject(
        id = id,
        elo = elo,
        responsivenessEpoch = responsivenessEpoch
    )

    private fun song(
        id: String,
        scoreTenths: Int? = null,
        elo: Double = BASE_RATING.toDouble(),
        rating: Int = BASE_RATING,
        wins: Int = 0,
        losses: Int = 0
    ): Song {
        return Song(
            id = id,
            title = id,
            artist = "Artist $id",
            album = "",
            artworkUrl = null,
            createdAt = 0L,
            scoreTenths = scoreTenths,
            elo = elo,
            rating = rating,
            wins = wins,
            losses = losses
        )
    }
}
