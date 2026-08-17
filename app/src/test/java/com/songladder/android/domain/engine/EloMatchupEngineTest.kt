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
    fun `includes an unrated song on every fifth selection opportunity`() {
        val selection = EloMatchupEngine(Random(1)).selectMatchup(
            songs = listOf(
                song(id = "rated-one", scoreTenths = 80),
                song(id = "rated-two", scoreTenths = 90),
                song(id = "unrated")
            ),
            events = listOf(
                event(1, "rated-one", "rated-two"),
                event(2, "rated-one", "rated-two"),
                event(3, "rated-one", "rated-two"),
                event(4, "rated-one", "rated-two")
            )
        )

        assertTrue(selection.matchup?.left?.scoreTenths == null || selection.matchup?.right?.scoreTenths == null)
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
        rating: Int = BASE_RATING
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
            rating = rating
        )
    }
}
