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

    @Test
    fun `youtube artwork urls are upgraded to a higher resolution source`() {
        assertEquals(
            "https://lh3.googleusercontent.com/example=w1200-h1200-l90-rj",
            "https://lh3.googleusercontent.com/example=w60-h60-l90-rj".upgradeArtworkUrl()
        )
        assertEquals(
            "https://i.ytimg.com/vi/example/sddefault.jpg=s1200-c-k-c0x00ffffff-no-rj",
            "https://i.ytimg.com/vi/example/sddefault.jpg=s60-c-k-c0x00ffffff-no-rj".upgradeArtworkUrl()
        )
    }
}
