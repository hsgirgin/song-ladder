package com.songladder.android.ui.leaderboard

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.songladder.android.domain.model.Song
import com.songladder.android.ui.theme.SongLadderTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LeaderboardScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun sortControls_exposeSelectionAndEmitSelectedMode() {
        var selectedMode: LeaderboardSortMode? = null

        composeRule.setContent {
            SongLadderTheme {
                LeaderboardSortControls(
                    selectedMode = LeaderboardSortMode.TOP_RATED,
                    onModeSelected = { selectedMode = it },
                    modifier = Modifier.width(220.dp)
                )
            }
        }

        composeRule.onNodeWithText("Top rated").assertIsSelected()
        composeRule.onNodeWithText("Most skipped")
            .assertIsNotSelected()
            .performClick()

        composeRule.runOnIdle {
            assertEquals(LeaderboardSortMode.MOST_SKIPPED, selectedMode)
        }
    }

    @Test
    fun leaderboardRow_displaysAllStatisticsAtCompactWidth() {
        composeRule.setContent {
            SongLadderTheme {
                LeaderboardRow(
                    index = 0,
                    song = Song(
                        id = "song-1",
                        title = "A song",
                        artist = "An artist",
                        createdAt = 0L,
                        rating = 1234,
                        wins = 12,
                        losses = 3,
                        skips = 5
                    ),
                    modifier = Modifier.width(240.dp)
                )
            }
        }

        composeRule.onNodeWithText("Rating 1234").assertIsDisplayed()
        composeRule.onNodeWithText("12 wins, 3 losses").assertIsDisplayed()
        composeRule.onNodeWithText("15 matches · More established").assertIsDisplayed()
        composeRule.onNodeWithText("5 skips").assertIsDisplayed()
    }
}
