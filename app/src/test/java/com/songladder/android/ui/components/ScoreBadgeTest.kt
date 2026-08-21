package com.songladder.android.ui.components

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoreBadgeTest {
    @Test
    fun `min score renders as red`() {
        assertEquals(Color(0xFFE53935), scoreGradientColor(10))
    }

    @Test
    fun `max score renders as green`() {
        assertEquals(Color(0xFF43A047), scoreGradientColor(100))
    }

    @Test
    fun `midpoint score renders as yellow`() {
        assertEquals(Color(0xFFFDD835), scoreGradientColor(55))
    }

    @Test
    fun `color progresses monotonically from red through yellow to green`() {
        val red = scoreGradientColor(10)
        val quarter = scoreGradientColor(32)
        val midpoint = scoreGradientColor(55)
        val threeQuarter = scoreGradientColor(77)
        val green = scoreGradientColor(100)

        // Red channel steps down from red(0xE5) to yellow(0xFD) then to green(0x43).
        assertTrue(quarter.green > red.green)
        assertTrue(midpoint.green > quarter.green)
        assertTrue(threeQuarter.red < midpoint.red)
        assertTrue(green.red < threeQuarter.red)
    }
}
