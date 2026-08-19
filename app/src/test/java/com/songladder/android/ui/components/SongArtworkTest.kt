package com.songladder.android.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class SongArtworkTest {
    @Test
    fun `artwork urls are upgraded to a high resolution source`() {
        assertEquals(
            "https://example.test/image/1200x1200bb-60.jpg",
            "https://example.test/image/100x100bb-60.jpg".upgradeArtworkUrl()
        )
    }

    @Test
    fun `non itunes artwork urls are preserved`() {
        val url = "https://example.test/thumbnail.jpg"

        assertEquals(url, url.upgradeArtworkUrl())
    }
}
