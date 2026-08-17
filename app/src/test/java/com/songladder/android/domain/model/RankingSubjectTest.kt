package com.songladder.android.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class RankingSubjectTest {
    @Test
    fun `score seed uses explicit score and defaults unrated songs to base Elo`() {
        assertEquals(840.0, seedEloForScore(10), 0.0)
        assertEquals(1200.0, seedEloForScore(null), 0.0)
        assertEquals(1560.0, seedEloForScore(100), 0.0)
    }
}
