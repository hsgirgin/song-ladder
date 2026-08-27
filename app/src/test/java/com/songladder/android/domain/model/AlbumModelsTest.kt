package com.songladder.android.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlbumModelsTest {
    @Test
    fun `normalized album id combines artist and album deterministically`() {
        assertEquals(
            "frank ocean::blonde",
            normalizedAlbumId("frank ocean", "blonde")
        )
        assertEquals(
            normalizedAlbumId("frank ocean", "blonde"),
            normalizedAlbumId("frank ocean", "blonde")
        )
    }

    @Test
    fun `score is unranked when the release has not been matched yet`() {
        assertNull(computeAlbumScoreTenths(listOf(80, 90, 100), providerTrackCount = null))
    }

    @Test
    fun `two track release needs only one rated track`() {
        assertNull(computeAlbumScoreTenths(emptyList(), providerTrackCount = 2))
        assertEquals(80, computeAlbumScoreTenths(listOf(80), providerTrackCount = 2))
    }

    @Test
    fun `three track release needs two rated tracks`() {
        assertNull(computeAlbumScoreTenths(listOf(80), providerTrackCount = 3))
        assertEquals(85, computeAlbumScoreTenths(listOf(80, 90), providerTrackCount = 3))
    }

    @Test
    fun `twelve track release needs six rated tracks not a flat three`() {
        assertNull(computeAlbumScoreTenths(List(5) { 80 }, providerTrackCount = 12))
        assertEquals(80, computeAlbumScoreTenths(List(6) { 80 }, providerTrackCount = 12))
    }

    @Test
    fun `single track release is excluded from scoring entirely by callers, not by this function`() {
        // computeAlbumScoreTenths itself has no special case for a single: with
        // providerTrackCount = 1, ceil(1 / 2.0) = 1, so a rated single would score
        // as soon as its one track is rated. Filtering singles out of the Albums
        // tab is a query-layer concern (Slice 3), not a scoring-function concern.
        assertEquals(80, computeAlbumScoreTenths(listOf(80), providerTrackCount = 1))
    }

    @Test
    fun `average rounds to nearest tenth using standard rounding`() {
        assertEquals(83, computeAlbumScoreTenths(listOf(80, 85), providerTrackCount = 2))
    }

    @Test
    fun `zero or negative provider track count is unranked rather than crashing`() {
        assertNull(computeAlbumScoreTenths(emptyList(), providerTrackCount = 0))
        assertNull(computeAlbumScoreTenths(listOf(80), providerTrackCount = 0))
        assertNull(computeAlbumScoreTenths(emptyList(), providerTrackCount = -1))
    }

    @Test
    fun `no rated tracks is unranked rather than dividing by zero`() {
        assertNull(computeAlbumScoreTenths(emptyList(), providerTrackCount = 5))
    }
}
