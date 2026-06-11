package com.songladder.android.data.local

import com.songladder.android.domain.model.ExportPayload
import com.songladder.android.domain.model.SongExport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SongLadderJsonPorterTest {
    private val porter = SongLadderJsonPorter()

    @Test
    fun `encodes and decodes export payload`() {
        val payload = ExportPayload(
            songs = listOf(
                SongExport(
                    id = "song-1",
                    externalId = "spotify-1",
                    sourceType = "SPOTIFY",
                    title = "Nights",
                    artist = "Frank Ocean",
                    album = "Blonde",
                    artworkUrl = null,
                    createdAt = 1234L,
                    rating = 1240,
                    wins = 8,
                    losses = 2,
                    skips = 1,
                    lastRankedAt = 5678L
                )
            ),
            matchCount = 10,
            skipCount = 3
        )

        val encoded = porter.encode(payload)
        val decoded = porter.decode(encoded)

        assertTrue(encoded.contains("Nights"))
        assertEquals(payload, decoded)
    }

    @Test
    fun `normalizes imported blank titles and artists`() {
        val payload = ExportPayload(
            songs = listOf(
                SongExport(
                    id = "song-2",
                    externalId = null,
                    sourceType = "IMPORT",
                    title = "",
                    artist = "",
                    album = "",
                    artworkUrl = null,
                    createdAt = 0L
                )
            )
        )

        val (songs, stats) = payload.toEntities()

        assertEquals("Untitled track", songs.single().title)
        assertEquals("Unknown artist", songs.single().artist)
        assertEquals("song-2", stats.single().songId)
    }
}
